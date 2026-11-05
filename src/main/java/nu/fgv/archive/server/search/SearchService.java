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

package nu.fgv.archive.server.search;

import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.file.FileDtos;
import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.FilePageRepository;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.material.ArrangementRepository;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MaterialDtos.VersionDto;
import nu.fgv.archive.server.material.ManuscriptRepository;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MusicKey;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Search across the archive (product spec 5.1): spex by title and year, manuscripts by title and authors, numbers by
 * title, original song and credits, arrangements by arranger and description, and the text of files with MySQL's
 * full-text index, page by page. Text hits are only in files the caller may open, so restricted text is never shown to
 * those who may not read it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SearchService {

    static final int MAX_RESULTS = 50;
    static final int MAX_TEXT_HITS = 50;
    static final int SNIPPET = 90;
    /**
     * InnoDB's full-text index leaves out shorter words.
     */
    static final int MIN_WORD = 3;

    private final SpexService spexService;
    private final ManuscriptRepository manuscriptRepository;
    private final ArrangementRepository arrangementRepository;
    private final SpexNumberRepository numberRepository;
    private final FilePageRepository pageRepository;
    private final ArchiveFileRepository fileRepository;
    private final MaterialService materialService;
    private final CallerService callerService;

    public SearchService(final SpexService spexService, final ManuscriptRepository manuscriptRepository,
                         final ArrangementRepository arrangementRepository, final SpexNumberRepository numberRepository,
                         final FilePageRepository pageRepository, final ArchiveFileRepository fileRepository,
                         final MaterialService materialService, final CallerService callerService) {
        this.spexService = spexService;
        this.manuscriptRepository = manuscriptRepository;
        this.arrangementRepository = arrangementRepository;
        this.numberRepository = numberRepository;
        this.pageRepository = pageRepository;
        this.fileRepository = fileRepository;
        this.materialService = materialService;
        this.callerService = callerService;
    }

    /**
     * The words of a query as a boolean mode full-text query, each required and matched as a prefix, or null when no
     * word is long enough.
     */
    static @Nullable String fullTextQuery(final String q) {
        final List<String> words = words(q);

        return words.isEmpty() ? null : words.stream().map(word -> "+" + word + "*").collect(Collectors.joining(" "));
    }

    static List<String> words(final String q) {
        return Arrays.stream(q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= MIN_WORD)
                .distinct()
                .toList();
    }

    /**
     * The text around the first word found, on one line.
     */
    static String snippet(final String text, final List<String> words) {
        final String lower = text.toLowerCase(Locale.ROOT);
        int at = -1;

        for (final String word : words) {
            final int index = lower.indexOf(word);

            if (index >= 0 && (at < 0 || index < at)) {
                at = index;
            }
        }

        final int start = Math.max(0, at < 0 ? 0 : at - SNIPPET);
        final int end = Math.min(text.length(), (at < 0 ? 0 : at) + SNIPPET);

        return ((start > 0 ? "…" : "") + text.substring(start, end).replaceAll("\\s+", " ").strip() + (end < text.length() ? "…" : ""));
    }

    @Transactional(readOnly = true)
    public SearchResultDto search(final String query, final PartFilter parts, final @Nullable MusicKey key) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final String q = query.strip();
        final List<VersionDto> versions = key == null ? List.of() : materialService.versionsInKey(key, parts, MAX_RESULTS * 4).stream()
                .filter(version -> q.isEmpty() || contains(version.label(), q) || contains(version.arranger(), q))
                .limit(MAX_RESULTS).toList();

        if (q.length() < 2) {
            return new SearchResultDto(List.of(), List.of(), List.of(), List.of(), versions, List.of());
        }

        final String pattern = "%" + q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        final List<SpexDto> spex = spexService.list(new SpexService.SpexFilter(null, null, q, null), null).stream()
                .limit(MAX_RESULTS).toList();
        final List<ManuscriptDto> manuscripts = manuscriptRepository.search(pattern).stream()
                .filter(manuscript -> manuscript.getTrashedAt() == null && MaterialDtos.sees(caller, manuscript))
                .limit(MAX_RESULTS).map(MaterialDtos::toDto).toList();
        final List<NumberDto> numbers = numberRepository.search(pattern).stream()
                .limit(MAX_RESULTS).map(NumberService::toDto).toList();
        final List<ArrangementDto> arrangements = arrangementRepository.search(pattern).stream()
                .filter(arrangement -> arrangement.getTrashedAt() == null && MaterialDtos.sees(caller, arrangement))
                .filter(arrangement -> (key == null && parts.isEmpty()) || arrangement.getVersions().stream()
                        .anyMatch(version -> !version.isInTrash() && MaterialDtos.sees(caller, version) && MaterialService.matches(version, key, parts)))
                .limit(MAX_RESULTS).map(MaterialDtos::toDto).toList();

        return new SearchResultDto(spex, manuscripts, numbers, arrangements, versions, textHits(q, caller, parts));
    }

    private static boolean contains(final @Nullable String text, final String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
    }

    private List<TextHitDto> textHits(final String q, final Caller caller, final PartFilter parts) {
        final String fullText = fullTextQuery(q);

        if (fullText == null) {
            return List.of();
        }

        final List<Object[]> rows = pageRepository.search(fullText, MAX_TEXT_HITS * 4);
        final Map<Long, ArchiveFile> files = fileRepository.findByIdIn(rows.stream().map(row -> ((Number) row[0]).longValue()).toList())
                .stream().collect(Collectors.toMap(ArchiveFile::getId, Function.identity()));
        final List<String> words = words(q);
        final List<TextHitDto> hits = new ArrayList<>();

        for (final Object[] row : rows) {
            final ArchiveFile file = files.get(((Number) row[0]).longValue());

            if (file == null || hits.size() >= MAX_TEXT_HITS) {
                continue;
            }

            final MaterialVersion version = file.getVersion();

            if (!version.isInTrash() && MaterialDtos.sees(caller, version) && MaterialDtos.canOpen(caller, version) && parts.matches(file)) {
                final int page = ((Number) row[1]).intValue();
                final FileDto fileDto = FileDtos.toDto(file, true, caller.isEditor());

                hits.add(new TextHitDto(fileDto, MaterialDtos.toDto(version, caller), page == 0 ? null : page,
                        snippet((String) row[2], words)));
            }
        }
        return hits;
    }

    public record SearchResultDto(List<SpexDto> spex, List<ManuscriptDto> manuscripts, List<NumberDto> numbers,
                                  List<ArrangementDto> arrangements, List<VersionDto> versions, List<TextHitDto> textHits) {
    }

    public record TextHitDto(FileDto file, VersionDto version, @Nullable Integer page, String snippet) {
    }
}
