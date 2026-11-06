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

package nu.fgv.archive.server.download;

import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.FileService.StoredFile;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MaterialVersionRepository;
import nu.fgv.archive.server.material.SpexParts;
import nu.fgv.archive.server.number.SpexNumber;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.share.ShareLink;
import nu.fgv.archive.server.share.ShareLinkService;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Downloads: single files and zips, for logged-in users and through share links, and their logging (product spec 5.4).
 * Every download is counted per file; downloads of restricted files and through share links are also logged with who.
 * Reading a file in the page is not a download.
 *
 * <p>Zips of parts take a {@link PartFilter}: the parts of chosen instruments or transpositions, or every part when it
 * is empty.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class DownloadService {

    /** A file fetched again within this time by the same user or link is not counted or logged again. */
    static final Duration DEDUPLICATE = Duration.ofMinutes(5);

    private final DownloadLogRepository logRepository;
    private final MaterialVersionRepository versionRepository;
    private final SpexNumberRepository numberRepository;
    private final SpexParts spexParts;
    private final SpexService spexService;
    private final FileService fileService;
    private final ShareLinkService shareLinkService;
    private final CallerService callerService;
    private final Clock clock;
    /** When each user or link last downloaded each file, for {@link #DEDUPLICATE}; per API instance. */
    private final Map<String, Instant> recent = new ConcurrentHashMap<>();

    @SuppressWarnings("java:S107")
    public DownloadService(final DownloadLogRepository logRepository, final MaterialVersionRepository versionRepository,
                           final SpexNumberRepository numberRepository, final SpexParts spexParts, final SpexService spexService,
                           final FileService fileService, final ShareLinkService shareLinkService,
                           final CallerService callerService, final Clock clock) {
        this.logRepository = logRepository;
        this.versionRepository = versionRepository;
        this.numberRepository = numberRepository;
        this.spexParts = spexParts;
        this.spexService = spexService;
        this.fileService = fileService;
        this.shareLinkService = shareLinkService;
        this.callerService = callerService;
        this.clock = clock;
    }

    /**
     * Which files of a version go into a zip: all without a filter, else the parts it matches, every part when empty.
     */
    private static Predicate<ArchiveFile> wanted(final @Nullable PartFilter parts) {
        if (parts == null) {
            return _ -> true;
        }
        return parts::matchesPart;
    }

    static String safe(final String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
    }

    /**
     * Whether a GET of a file is a download to count: the whole file, or a range from its first byte, which is how a
     * reader starts. The rest of the ranges a reader asks for are not; neither is {@code download=}, which the client
     * chooses and which only sets the disposition.
     */
    public static boolean countable(final @Nullable String range) {
        return range == null || range.isBlank() || range.strip().replace(" ", "").startsWith("bytes=0-");
    }

    /**
     * Counts the downloads, and logs them with who when the file is restricted or comes through a share link. The same
     * file fetched again by the same user or link within {@link #DEDUPLICATE} counts once.
     */
    @Transactional
    public void record(final List<StoredFile> files, final @Nullable Long shareLinkId) {
        final Caller caller = callerService.currentIfAny().orElse(null);
        final String who = shareLinkId != null ? "link:" + shareLinkId : caller != null ? "user:" + caller.subject() : "anonymous";
        final Instant now = clock.instant();

        recent.values().removeIf(at -> at.isBefore(now.minus(DEDUPLICATE)));
        for (final StoredFile file : files) {
            final Instant previous = recent.put(who + ":" + file.uuid(), now);

            if (previous != null && previous.isAfter(now.minus(DEDUPLICATE))) {
                continue;
            }
            logRepository.countDownload(file.id());
            if (shareLinkId != null || file.restricted()) {
                final DownloadLog entry = new DownloadLog();

                entry.setFileId(file.id());
                entry.setAt(clock.instant());
                entry.setShareLinkId(shareLinkId);
                entry.setUserSubject(shareLinkId == null && caller != null ? caller.subject() : null);
                entry.setUserName(shareLinkId == null && caller != null ? caller.name() : null);
                logRepository.save(entry);
            }
        }
    }

    /**
     * A version's files, or with a filter its parts, as a zip, for a user who may open them.
     *
     * @param parts null for every file, else the parts to include
     */
    @Transactional(readOnly = true)
    public ZipPlan versionZip(final UUID versionId, final @Nullable PartFilter parts) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final MaterialVersion version = versionRepository.findByUuid(versionId)
                .filter(found -> MaterialDtos.sees(caller, found))
                .orElseThrow(() -> new ResourceNotFoundException("error.version.notFound", versionId));

        if (!MaterialDtos.canOpen(caller, version)) {
            throw new AccessDeniedException("Restricted version");
        }
        return zip(version, version.getFiles(), parts, null);
    }

    /**
     * The parts of chosen instruments or transpositions across a spex' arrangements, in the order of the show, e.g.
     * every trumpet and trombone part of a show, from the versions the user may open.
     */
    @Transactional(readOnly = true)
    public ZipPlan spexZip(final UUID spexId, final PartFilter parts) {
        final Caller caller = callerService.current();

        caller.requireUser();
        return spexZip(spexId, parts, SpexParts.openableBy(caller), null);
    }

    /**
     * A file a valid share link opens.
     */
    @Transactional(readOnly = true)
    public SharedFile sharedFile(final String token, final UUID fileId) {
        final ShareLink link = shareLinkService.valid(token);

        return new SharedFile(fileService.stored(shareLinkService.sharedFile(link, fileId)), link.getId());
    }

    /**
     * What a valid share link opens of a version, or of its parts, as a zip.
     */
    @Transactional(readOnly = true)
    public ZipPlan sharedVersionZip(final String token, final UUID versionId, final @Nullable PartFilter parts) {
        final ShareLink link = shareLinkService.valid(token);
        final List<ArchiveFile> files = shareLinkService.sharedFiles(link, versionId);

        return zip(files.getFirst().getVersion(), files, parts, link.getId());
    }

    /**
     * Every part a valid share link opens across its spex' arrangements, in the order of the show, narrowed further when
     * asked.
     */
    @Transactional(readOnly = true)
    public ZipPlan sharedSpexZip(final String token, final PartFilter parts) {
        final ShareLink link = shareLinkService.valid(token);

        if (link.getSpexId() == null) {
            throw new ResourceNotFoundException("error.zip.empty");
        }

        final Set<ArchiveFile> shared = new HashSet<>(shareLinkService.spexParts(link));

        return spexZip(link.getSpexId(), parts, MaterialVersion::isListed, link.getId(), shared::contains);
    }

    private ZipPlan spexZip(final UUID spexId, final PartFilter parts, final Predicate<MaterialVersion> usable,
                            final @Nullable Long shareLinkId) {
        return spexZip(spexId, parts, usable, shareLinkId, _ -> true);
    }

    private ZipPlan spexZip(final UUID spexId, final PartFilter parts, final Predicate<MaterialVersion> usable,
                            final @Nullable Long shareLinkId, final Predicate<ArchiveFile> allowed) {
        final List<ZipItem> items = spexParts.find(spexId, parts, usable).stream()
                .filter(part -> allowed.test(part.file()))
                .map(part -> new ZipItem("%02d %s - %s".formatted(part.number().getPosition(), safe(part.number().getTitle()),
                        FileService.downloadName(part.file().getTitle(), part.file().getExtension())), fileService.stored(part.file())))
                .toList();

        if (items.isEmpty()) {
            throw new ResourceNotFoundException("error.zip.empty");
        }

        final String spexTitle = Optional.ofNullable(spexService.byIds(List.of(spexId)).get(spexId))
                .map(spex -> spex.title() + (spex.year() == null ? "" : " " + spex.year())).orElse("Spex");

        return new ZipPlan(safe(spexTitle + " - stämmor") + ".zip", unique(items), shareLinkId);
    }

    private ZipPlan zip(final MaterialVersion version, final List<ArchiveFile> files, final @Nullable PartFilter parts,
                        final @Nullable Long shareLinkId) {
        final List<ZipItem> items = files.stream()
                .filter(wanted(parts))
                .map(file -> new ZipItem(FileService.downloadName(file.getTitle(), file.getExtension()), fileService.stored(file)))
                .toList();

        if (items.isEmpty()) {
            throw new ResourceNotFoundException("error.zip.empty");
        }

        final String title = version.getManuscript() != null ? version.getManuscript().getTitle()
                : numberRepository.findByUuid(Objects.requireNonNull(version.getArrangement()).getNumberId())
                .map(SpexNumber::getTitle).orElse("Arrangemang");

        return new ZipPlan(safe(title + " - " + version.getLabel() + (parts != null ? " - stämmor" : "")) + ".zip", unique(items), shareLinkId);
    }

    /**
     * The items with a number added to repeated names, since a zip cannot hold two entries with the same name.
     */
    static List<ZipItem> unique(final List<ZipItem> items) {
        final Set<String> used = new HashSet<>();

        return items.stream().map(item -> {
            final String name = item.name();
            final int dot = name.lastIndexOf('.');
            String candidate = name;

            for (int i = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); i++) {
                candidate = dot < 0 ? name + " (" + i + ")" : name.substring(0, dot) + " (" + i + ")" + name.substring(dot);
            }
            return new ZipItem(candidate, item.file());
        }).toList();
    }

    /**
     * A zip to stream: its name, its entries, and the share link it comes through, if any.
     */
    public record ZipPlan(String name, List<ZipItem> items, @Nullable Long shareLinkId) {
    }

    public record ZipItem(String name, StoredFile file) {
    }

    public record SharedFile(StoredFile file, Long shareLinkId) {
    }
}
