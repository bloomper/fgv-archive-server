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

package nu.fgv.archive.server.file;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.file.FileDtos.FileAddInput;
import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.FileDtos.FileGuessDto;
import nu.fgv.archive.server.file.FileDtos.FileInput;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MaterialVersionRepository;
import nu.fgv.archive.server.upload.UploadService;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The files of versions: adding them from finished uploads, with their type checked by content and their role and
 * instrument guessed from their names, editing, replacing, reordering and deleting them, and reading them for
 * downloads. The worker makes their text and previews afterwards.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class FileService {

    static final int MAX_TITLE = 255;
    static final int MAX_COMMENT = 1000;
    static final int MAX_DESK = 50;

    private final ArchiveFileRepository repository;
    private final MaterialVersionRepository versionRepository;
    private final FileRoleRepository roleRepository;
    private final InstrumentRepository instrumentRepository;
    private final FilePageRepository pageRepository;
    private final FileStorage storage;
    private final FileTypes types;
    private final UploadService uploadService;
    private final CallerService callerService;
    private final AuditService auditService;

    @SuppressWarnings("java:S107")
    public FileService(final ArchiveFileRepository repository, final MaterialVersionRepository versionRepository,
                       final FileRoleRepository roleRepository, final InstrumentRepository instrumentRepository,
                       final FilePageRepository pageRepository, final FileStorage storage, final FileTypes types,
                       final UploadService uploadService, final CallerService callerService, final AuditService auditService) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.roleRepository = roleRepository;
        this.instrumentRepository = instrumentRepository;
        this.pageRepository = pageRepository;
        this.storage = storage;
        this.types = types;
        this.uploadService = uploadService;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    /**
     * A name for downloads: the title, without characters file systems refuse, and the extension.
     */
    public static String downloadName(final String title, final String extension) {
        final String safe = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();

        return (safe.isEmpty() ? "fil" : safe) + "." + extension;
    }

    static String sha256(final Path file) throws IOException {
        final MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static @Nullable String comment(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.strip().length() > MAX_COMMENT) {
            throw new BadRequestException("error.file.commentTooLong", MAX_COMMENT);
        }
        return value.strip();
    }

    private static String title(final @Nullable String value, final String fallback) {
        final String title = StringUtils.hasText(value) ? value.strip() : fallback;

        if (title.length() > MAX_TITLE) {
            return title.substring(0, MAX_TITLE);
        }
        return title;
    }

    /**
     * The files of each version the caller sees, in order, with whether the caller may open them; with a part filter,
     * only the files it matches.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<FileDto>> filesOf(final Collection<UUID> versionIds, final PartFilter parts) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final List<MaterialVersion> versions = versionRepository.findByUuidIn(versionIds).stream()
                .filter(version -> MaterialDtos.sees(caller, version))
                .toList();
        final Map<Long, MaterialVersion> byId = versions.stream().collect(Collectors.toMap(MaterialVersion::getId, Function.identity()));
        final Map<UUID, List<FileDto>> result = new LinkedHashMap<>();

        versionIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (!versions.isEmpty()) {
            for (final ArchiveFile file : repository.findByVersionInOrderByPositionAscIdAsc(versions).stream().filter(parts::matches).toList()) {
                final MaterialVersion version = byId.get(file.getVersion().getId());

                result.get(version.getUuid()).add(FileDtos.toDto(file, MaterialDtos.canOpen(caller, version), caller.isEditor()));
            }
        }
        return result;
    }

    /**
     * Files as the caller sees them, by id, without checking that the caller sees their versions, for other services
     * that have checked.
     */
    @Transactional(readOnly = true)
    public Map<UUID, FileDto> byIds(final Collection<UUID> ids, final boolean canOpen) {
        final boolean editor = callerService.currentIfAny().map(Caller::isEditor).orElse(false);

        return ids.isEmpty() ? Map.of() : repository.findByUuidIn(ids).stream()
                .map(file -> FileDtos.toDto(file, canOpen, editor))
                .collect(Collectors.toMap(FileDto::id, Function.identity()));
    }

    @Transactional(readOnly = true)
    public @Nullable FileDto get(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return repository.findByUuid(id)
                .filter(file -> MaterialDtos.sees(caller, file.getVersion()))
                .map(file -> FileDtos.toDto(file, MaterialDtos.canOpen(caller, file.getVersion()), caller.isEditor()))
                .orElse(null);
    }

    /**
     * A file the caller may open, for downloads: not found when the caller does not see it, forbidden when it is
     * restricted and the caller is not an editor.
     */
    @Transactional(readOnly = true)
    public StoredFile open(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final ArchiveFile file = repository.findByUuid(id)
                .filter(found -> MaterialDtos.sees(caller, found.getVersion()))
                .orElseThrow(() -> new ResourceNotFoundException("error.file.notFound", id));

        if (!MaterialDtos.canOpen(caller, file.getVersion())) {
            throw new AccessDeniedException("Restricted file");
        }
        return stored(file);
    }

    /**
     * Where a file lies and how it is served, read in a transaction.
     */
    public StoredFile stored(final ArchiveFile file) {
        return new StoredFile(file.getId(), file.getUuid(), storage.file(file), storage.preview(file),
                downloadName(file.getTitle(), file.getExtension()), file.getContentType(), file.getVersion().isRestricted(),
                file.isHasPreview());
    }

    @Transactional(readOnly = true)
    public FileGuessDto guess(final UUID versionId, final String name) {
        callerService.current().requireUser();

        final MaterialVersion version = version(versionId);
        final FileGuesser.Guess guess = FileGuesser.guess(name, FileTypes.extensionOf(name), version.getKind(),
                roleRepository.findAllByOrderByPositionAscIdAsc(), instrumentRepository.findAllByOrderByPositionAscIdAsc());

        return new FileGuessDto(FileDtos.toDto(guess.role()), guess.instruments().stream().map(FileDtos::toDto).toList(), guess.desk(),
                guess.transposition(), guess.title());
    }

    @Transactional
    public FileDto add(final FileAddInput input) throws IOException {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final MaterialVersion version = version(input.versionId());
        final Path upload = uploadService.claim(input.uploadId(), caller);
        final String name = uploadService.fileName(input.uploadId());
        final FileTypes.Detected detected = types.detect(upload, name);
        final FileGuesser.Guess guess = FileGuesser.guess(name, detected.extension(), version.getKind(),
                roleRepository.findAllByOrderByPositionAscIdAsc(), instrumentRepository.findAllByOrderByPositionAscIdAsc());
        final ArchiveFile file = new ArchiveFile();

        file.setVersion(version);
        file.setTitle(title(input.title(), guess.title()));
        file.setComment(comment(input.comment()));
        final boolean guessed = input.roleId() == null;

        setPart(file, guessed ? guess.role() : role(input.roleId()),
                input.instrumentIds() != null ? instruments(input.instrumentIds()) : guessed ? guess.instruments() : List.of(),
                input.desk() != null || !guessed ? input.desk() : guess.desk(),
                input.transposition() != null || !guessed ? input.transposition() : guess.transposition());
        file.setOriginalName(name.isEmpty() ? file.getTitle() : title(name, name));
        setContent(file, upload, detected);
        file.setPosition(version.getFiles().stream().mapToInt(ArchiveFile::getPosition).max().orElse(0) + 1);
        repository.save(file);
        storage.store(upload, storage.file(version.getUuid(), file));
        uploadService.release(input.uploadId());
        auditService.record("CREATE", "FILE", file.getUuid(), "Laddade upp %s till %s".formatted(file.getTitle(), version.getLabel()));
        return FileDtos.toDto(file, true, true);
    }

    @Transactional
    public FileDto update(final UUID id, final FileInput input) {
        callerService.current().requireEditor();

        final ArchiveFile file = file(id);

        file.setTitle(title(input.title(), file.getTitle()));
        file.setComment(comment(input.comment()));
        setPart(file, role(input.roleId()), input.instrumentIds() == null ? List.of() : instruments(input.instrumentIds()), input.desk(),
                input.transposition());
        auditService.record("UPDATE", "FILE", file.getUuid(), "Ändrade filen " + file.getTitle());
        return FileDtos.toDto(file, true, true);
    }

    @Transactional
    public FileDto replace(final UUID id, final String uploadId) throws IOException {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final ArchiveFile file = file(id);
        final Path upload = uploadService.claim(uploadId, caller);
        final String name = uploadService.fileName(uploadId);
        final FileTypes.Detected detected = types.detect(upload, name);
        final Path old = storage.file(file);

        setContent(file, upload, detected);
        file.setOriginalName(name.isEmpty() ? file.getOriginalName() : title(name, name));
        pageRepository.deleteByFile(file);
        Files.deleteIfExists(storage.preview(file));
        Files.deleteIfExists(old);
        storage.store(upload, storage.file(file));
        uploadService.release(uploadId);
        auditService.record("UPDATE", "FILE", file.getUuid(), "Bytte innehållet i filen " + file.getTitle());
        return FileDtos.toDto(file, true, true);
    }

    @Transactional
    public List<FileDto> reorder(final UUID versionId, final List<UUID> ids) {
        callerService.current().requireEditor();

        final MaterialVersion version = version(versionId);
        final List<ArchiveFile> ordered = new ArrayList<>();

        for (final UUID id : ids) {
            version.getFiles().stream().filter(file -> file.getUuid().equals(id)).findFirst()
                    .filter(file -> !ordered.contains(file))
                    .ifPresentOrElse(ordered::add, () -> {
                        throw new BadRequestException("error.file.reorder");
                    });
        }
        version.getFiles().stream().filter(file -> !ordered.contains(file)).forEach(ordered::add);
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPosition(i + 1);
        }
        auditService.record("UPDATE", "VERSION", version.getUuid(), "Ändrade ordningen på filerna i " + version.getLabel());
        return ordered.stream().map(file -> FileDtos.toDto(file, true, true)).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireEditor();

        final ArchiveFile file = file(id);

        deleteAll(List.of(file));
        auditService.record("DELETE", "FILE", file.getUuid(), "Raderade filen " + file.getTitle());
    }

    /**
     * Deletes files and, once the transaction has committed, their content and previews.
     */
    @Transactional
    public void deleteAll(final List<ArchiveFile> files) {
        final List<Path> paths = new ArrayList<>();

        for (final ArchiveFile file : files) {
            paths.add(storage.file(file));
            paths.add(storage.preview(file));
            file.getVersion().getFiles().remove(file);
        }
        repository.deleteAll(files);
        afterCommit(() -> paths.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    /**
     * Runs an action once the current transaction has committed, or at once without one.
     */
    public static void afterCommit(final Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (final RuntimeException e) {
                        log.warn("Could not delete files after a commit: {}", e.getMessage());
                    }
                }
            });
        } else {
            action.run();
        }
    }

    private void setContent(final ArchiveFile file, final Path upload, final FileTypes.Detected detected) throws IOException {
        file.setExtension(detected.extension());
        file.setContentType(detected.contentType());
        file.setSize(Files.size(upload));
        file.setChecksum(sha256(upload));
        file.setPages(null);
        file.setHasText(false);
        file.setHasPreview(false);
        file.setProcessingStatus(ProcessingStatus.PENDING);
        file.setProcessingAttempts(0);
        file.setProcessingError(null);
    }

    private MaterialVersion version(final UUID id) {
        final Caller caller = callerService.current();

        return versionRepository.findByUuid(id)
                .filter(version -> MaterialDtos.sees(caller, version))
                .orElseThrow(() -> new ResourceNotFoundException("error.version.notFound", id));
    }

    private ArchiveFile file(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.file.notFound", id));
    }

    private FileRole role(final UUID id) {
        return roleRepository.findByUuid(id).orElseThrow(() -> new BadRequestException("error.fileRole.notFound", id));
    }

    private List<Instrument> instruments(final List<UUID> ids) {
        return ids.stream().distinct()
                .map(id -> instrumentRepository.findByUuid(id).orElseThrow(() -> new BadRequestException("error.instrument.notFound", id)))
                .toList();
    }

    /**
     * Sets a file's role, instruments, desk and transposition. A part needs an instrument; a file with instruments and no
     * transposition given gets its first instrument's default.
     */
    private static void setPart(final ArchiveFile file, final FileRole role, final List<Instrument> instruments,
                                final @Nullable String desk, final FileDtos.@Nullable Transposition transposition) {
        if (FileRole.PART.equals(role.getUuid()) && instruments.isEmpty()) {
            throw new BadRequestException("error.file.partInstrument");
        }
        if (desk != null && desk.strip().length() > MAX_DESK) {
            throw new BadRequestException("error.file.desk", MAX_DESK);
        }
        if (transposition != null && Math.abs(transposition.octave()) > 1) {
            throw new BadRequestException("error.file.octave");
        }
        file.setRole(role);
        file.getInstruments().clear();
        file.getInstruments().addAll(instruments);
        file.setDesk(StringUtils.hasText(desk) ? desk.strip() : null);
        if (transposition != null) {
            file.setTranspositionPitch(transposition.pitch());
            file.setTranspositionOctave(transposition.octave());
        } else if (!instruments.isEmpty()) {
            file.setTranspositionPitch(instruments.getFirst().getDefaultPitch());
            file.setTranspositionOctave(instruments.getFirst().getDefaultOctave());
        } else {
            file.setTranspositionPitch(null);
            file.setTranspositionOctave(0);
        }
    }

    /**
     * A file on disk as it is served.
     *
     * @param id           the internal id, for download logs
     * @param downloadName the title and extension
     * @param restricted   whether its version is restricted, so that downloads are logged with who
     */
    public record StoredFile(Long id, UUID uuid, Path path, Path preview, String downloadName, String contentType,
                             boolean restricted, boolean hasPreview) {
    }
}
