/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.archive.server.processing;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.archive.server.config.ArchiveProperties;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.file.FilePage;
import nu.fgv.archive.server.file.FilePageRepository;
import nu.fgv.archive.server.file.FileStorage;
import nu.fgv.archive.server.file.FileTypes;
import nu.fgv.archive.server.file.ProcessingStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Gives new files their page count, searchable text and first-page preview, one at a time, in the worker container only
 * ({@code archive.worker.enabled}). Each file is read outside a transaction; a file that fails three times is marked
 * failed and can still be downloaded. The {@code FILE_BACKFILL} job queues failed and incomplete files again.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class FileProcessor {

    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 20;
    private static final int MAX_ERROR = 1000;

    private final boolean enabled;
    private final ArchiveFileRepository repository;
    private final FilePageRepository pageRepository;
    private final FileStorage storage;
    private final ContentReader reader;
    private final TransactionTemplate transactionTemplate;

    public FileProcessor(final ArchiveProperties properties, final ArchiveFileRepository repository,
                         final FilePageRepository pageRepository, final FileStorage storage, final ContentReader reader,
                         final TransactionTemplate transactionTemplate) {
        this.enabled = properties.worker().enabled();
        this.repository = repository;
        this.pageRepository = pageRepository;
        this.storage = storage;
        this.reader = reader;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelay = 5, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "file-processing", lockAtMostFor = "PT30M")
    public void tick() {
        if (enabled) {
            processPending();
        }
    }

    /**
     * Processes every waiting file now, in this thread.
     *
     * @return how many files were processed
     */
    public int processPending() {
        int processed = 0;
        List<Work> batch;

        while (!(batch = next()).isEmpty()) {
            for (final Work work : batch) {
                process(work);
                processed++;
            }
        }
        return processed;
    }

    /**
     * Queues failed files, and PDFs without a page count or preview, again; and files whose preview has gone missing,
     * e.g. after a restore without previews.
     *
     * @return how many files were queued
     */
    @SchedulerLock(name = "file-backfill", lockAtMostFor = "PT30M")
    public int backfill() {
        return Objects.requireNonNull(transactionTemplate.execute(_ -> {
            int queued = repository.requeueIncomplete();

            for (final ArchiveFile file : repository.findByProcessingStatusAndHasPreviewTrue(ProcessingStatus.DONE)) {
                if (!Files.isRegularFile(storage.preview(file))) {
                    file.setProcessingStatus(ProcessingStatus.PENDING);
                    file.setProcessingAttempts(0);
                    queued++;
                }
            }
            log.info("Queued {} files for text and previews", queued);
            return queued;
        }));
    }

    private List<Work> next() {
        return Objects.requireNonNull(transactionTemplate.execute(_ ->
                repository.findByProcessingStatusOrderByIdAsc(ProcessingStatus.PENDING, PageRequest.of(0, BATCH)).stream()
                        .map(file -> {
                            file.setProcessingAttempts(file.getProcessingAttempts() + 1);
                            return new Work(file.getId(), storage.file(file), storage.preview(file), file.getContentType(),
                                    file.getProcessingAttempts());
                        })
                        .toList()));
    }

    private void process(final Work work) {
        try {
            final Result result = read(work);

            transactionTemplate.executeWithoutResult(_ -> repository.findById(work.fileId()).ifPresent(file -> {
                pageRepository.deleteByFile(file);
                for (int i = 0; i < result.texts().size(); i++) {
                    if (!result.texts().get(i).isBlank()) {
                        final FilePage page = new FilePage();

                        page.setFile(file);
                        page.setPage(result.paged() ? i + 1 : 0);
                        page.setText(result.texts().get(i));
                        pageRepository.save(page);
                    }
                }
                file.setPages(result.pages());
                file.setHasText(result.texts().stream().anyMatch(text -> !text.isBlank()));
                file.setHasPreview(result.preview());
                file.setProcessingStatus(ProcessingStatus.DONE);
                file.setProcessingError(null);
            }));
        } catch (final IOException | RuntimeException e) {
            final String message = String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());

            log.warn("Could not process file {} (attempt {}): {}", work.fileId(), work.attempt(), message);
            transactionTemplate.executeWithoutResult(_ -> repository.findById(work.fileId()).ifPresent(file -> {
                file.setProcessingError(message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message);
                if (work.attempt() >= MAX_ATTEMPTS) {
                    file.setProcessingStatus(ProcessingStatus.FAILED);
                }
            }));
        }
    }

    private Result read(final Work work) throws IOException {
        if (FileTypes.PDF.equals(work.contentType())) {
            final ContentReader.PdfContent content = reader.pdf(work.file(), work.preview());

            return new Result(content.pages(), content.texts(), true, content.rendered());
        }
        if (FileTypes.hasExtractableText(work.contentType())) {
            return new Result(null, List.of(reader.text(work.file())), false, false);
        }
        if (FileTypes.isImage(work.contentType())) {
            return new Result(null, List.of(), false, reader.imagePreview(work.file(), work.preview()));
        }
        return new Result(null, List.of(), false, false);
    }

    private record Work(Long fileId, Path file, Path preview, String contentType, int attempt) {
    }

    private record Result(Integer pages, List<String> texts, boolean paged, boolean preview) {
    }
}
