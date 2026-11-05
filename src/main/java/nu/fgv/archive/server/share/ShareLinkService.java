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

package nu.fgv.archive.server.share;

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.download.DownloadLogRepository;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.file.FileDtos;
import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.Instrument;
import nu.fgv.archive.server.file.InstrumentRepository;
import nu.fgv.archive.server.file.Pitch;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.material.MaterialDtos.InstrumentationDto;
import nu.fgv.archive.server.material.MaterialKind;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MaterialVersionRepository;
import nu.fgv.archive.server.material.MusicKey;
import nu.fgv.archive.server.material.SpexParts;
import nu.fgv.archive.server.number.SpexNumber;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.settings.SettingsService;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import nu.fgv.archive.server.util.error.ShareLinkGoneException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Share links (product spec 5.5): editors create them for published versions and files, extend and revoke their own,
 * and admins any. Anyone with a valid link's token sees and downloads exactly what it shares, without logging in;
 * expired and revoked links answer with their status and who made them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ShareLinkService {

    static final int DEFAULT_MONTHS = 12;
    static final int MAX_NAME = 255;
    static final int MAX_ITEMS = 500;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ShareLinkRepository repository;
    private final MaterialVersionRepository versionRepository;
    private final ArchiveFileRepository fileRepository;
    private final SpexNumberRepository numberRepository;
    private final DownloadLogRepository downloadLogRepository;
    private final InstrumentRepository instrumentRepository;
    private final SpexParts spexPartsFinder;
    private final SpexService spexService;
    private final SettingsService settingsService;
    private final CallerService callerService;
    private final AuditService auditService;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ShareLinkService(final ShareLinkRepository repository, final MaterialVersionRepository versionRepository,
                            final ArchiveFileRepository fileRepository, final SpexNumberRepository numberRepository,
                            final DownloadLogRepository downloadLogRepository, final InstrumentRepository instrumentRepository,
                            final SpexParts spexPartsFinder, final SpexService spexService,
                            final SettingsService settingsService, final CallerService callerService,
                            final AuditService auditService, final Clock clock) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.fileRepository = fileRepository;
        this.numberRepository = numberRepository;
        this.downloadLogRepository = downloadLogRepository;
        this.instrumentRepository = instrumentRepository;
        this.spexPartsFinder = spexPartsFinder;
        this.spexService = spexService;
        this.settingsService = settingsService;
        this.callerService = callerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * The SHA-256 of a token, hex: what the database keeps.
     */
    public static String hash(final String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A new token: 32 random bytes, base64url without padding.
     */
    static String newToken() {
        final byte[] bytes = new byte[32];

        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // --------------------------------------------------------------------------------------------- managing links

    @Transactional(readOnly = true)
    public List<ShareLinkDto> list(final boolean all) {
        final Caller caller = callerService.current();

        caller.requireEditor();
        return (all && caller.isAdmin() ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByCreatedByOrderByCreatedAtDesc(caller.subject())).stream().map(this::toDto).toList();
    }

    /**
     * Downloads through each link, per file, most downloaded first.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<FileDownloads>> downloads(final Collection<UUID> linkIds) {
        callerService.current().requireEditor();

        final Map<Long, UUID> links = new HashMap<>();

        linkIds.forEach(id -> repository.findByUuid(id).ifPresent(link -> links.put(link.getId(), link.getUuid())));

        final Map<UUID, List<FileDownloads>> result = new LinkedHashMap<>();

        linkIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (!links.isEmpty()) {
            final List<Object[]> rows = downloadLogRepository.countByShareLinks(links.keySet());
            final Map<Long, ArchiveFile> files = new HashMap<>();

            fileRepository.findByIdIn(rows.stream().map(row -> (Long) row[1]).toList()).forEach(file -> files.put(file.getId(), file));
            for (final Object[] row : rows) {
                final ArchiveFile file = files.get((Long) row[1]);

                if (file != null) {
                    result.get(links.get((Long) row[0])).add(new FileDownloads(FileDtos.toDto(file, true, true), ((Number) row[2]).intValue()));
                }
            }
        }
        return result;
    }

    @Transactional
    public CreatedShareLink create(final ShareLinkInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final String name = StringUtils.hasText(input.name()) ? input.name().strip() : "";

        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new BadRequestException("error.shareLink.name");
        }

        final List<UUID> versionIds = input.versionIds() == null ? List.of() : input.versionIds();
        final List<UUID> fileIds = input.fileIds() == null ? List.of() : input.fileIds();

        final List<UUID> instrumentIds = input.instrumentIds() == null ? List.of() : input.instrumentIds().stream().distinct().toList();
        final List<Pitch> transpositions = input.transpositions() == null ? List.of() : input.transpositions().stream().distinct().toList();

        if (versionIds.isEmpty() && fileIds.isEmpty() && input.spexId() == null || versionIds.size() + fileIds.size() > MAX_ITEMS) {
            throw new BadRequestException("error.shareLink.empty");
        }
        if (input.spexId() == null ? !instrumentIds.isEmpty() || !transpositions.isEmpty() : instrumentIds.isEmpty()) {
            throw new BadRequestException("error.shareLink.instruments");
        }

        final ShareLink link = new ShareLink();

        if (input.spexId() != null) {
            link.setSpexId(spexService.require(input.spexId()).getUuid());
            for (final UUID id : instrumentIds) {
                link.getInstruments().add(instrumentRepository.findByUuid(id)
                        .orElseThrow(() -> new BadRequestException("error.instrument.notFound", id)));
            }
            link.setTranspositions(transpositions.isEmpty() ? null : transpositions.stream().map(Enum::name).collect(Collectors.joining(",")));
        }
        final String token = newToken();
        final Instant now = clock.instant();

        for (final UUID id : versionIds) {
            link.getVersions().add(versionRepository.findByUuid(id).filter(MaterialVersion::isListed)
                    .orElseThrow(() -> new BadRequestException("error.shareLink.unpublished")));
        }
        for (final UUID id : fileIds) {
            link.getFiles().add(fileRepository.findByUuid(id).filter(file -> file.getVersion().isListed())
                    .orElseThrow(() -> new BadRequestException("error.shareLink.unpublished")));
        }
        link.setName(name);
        link.setTokenHash(hash(token));
        link.setCreatedBy(caller.subject());
        link.setCreatedByName(caller.name());
        link.setCreatedAt(now);
        link.setExpiresAt(expiry(input.months() == null ? DEFAULT_MONTHS : input.months()));
        repository.save(link);
        auditService.record("SHARE", "SHARE_LINK", link.getUuid(), "Skapade delningslänken " + name);
        return new CreatedShareLink(toDto(link), token);
    }

    @Transactional
    public ShareLinkDto extend(final UUID id, final int months) {
        final ShareLink link = owned(id);

        link.setExpiresAt(expiry(months));
        auditService.record("UPDATE", "SHARE_LINK", link.getUuid(), "Förlängde delningslänken " + link.getName());
        return toDto(link);
    }

    @Transactional
    public ShareLinkDto revoke(final UUID id) {
        final ShareLink link = owned(id);

        if (link.getRevokedAt() == null) {
            link.setRevokedAt(clock.instant());
            link.setRevokedByName(callerService.current().name());
            auditService.record("REVOKE", "SHARE_LINK", link.getUuid(), "Återkallade delningslänken " + link.getName());
        }
        return toDto(link);
    }

    private Instant expiry(final int months) {
        if (months < 1 || months > settingsService.current().getMaxShareLinkMonths()) {
            throw new BadRequestException("error.shareLink.months", settingsService.current().getMaxShareLinkMonths());
        }
        return clock.instant().atZone(ZoneId.of("UTC")).plusMonths(months).toInstant();
    }

    private ShareLink owned(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final ShareLink link = repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.shareLink.notFound", id));

        if (!caller.isAdmin() && !link.getCreatedBy().equals(caller.subject())) {
            throw new AccessDeniedException("Not the link's creator");
        }
        return link;
    }

    private ShareLinkDto toDto(final ShareLink link) {
        return new ShareLinkDto(link.getUuid(), link.getName(), link.getCreatedBy(), link.getCreatedByName(), link.getCreatedAt(),
                link.getExpiresAt(), link.getRevokedAt(), link.getRevokedByName(), link.status(clock.instant()),
                link.getVersions().stream().map(MaterialVersion::getUuid).toList(),
                link.getFiles().stream().map(ArchiveFile::getUuid).toList(), link.getSpexId(),
                link.getInstruments().stream().map(Instrument::getUuid).toList(), link.transpositionList());
    }

    // --------------------------------------------------------------------------------------------- using links

    /**
     * What a link shows, for anyone with its token; null when there is no such link.
     */
    @Transactional(readOnly = true)
    public @Nullable SharedViewDto view(final String token) {
        final ShareLink link = repository.findByTokenHash(hash(token)).orElse(null);

        if (link == null) {
            return null;
        }

        final ShareLinkStatus status = link.status(clock.instant());

        return new SharedViewDto(link.getName(), status, link.getCreatedByName(), link.getExpiresAt(),
                status == ShareLinkStatus.VALID ? sharedVersions(link) : List.of());
    }

    /**
     * A valid link, for downloads.
     *
     * @throws ResourceNotFoundException when there is no such link
     * @throws ShareLinkGoneException    when it has expired or been revoked
     */
    @Transactional(readOnly = true)
    public ShareLink valid(final String token) {
        final ShareLink link = repository.findByTokenHash(hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("error.shareLink.notFound", ""));

        final ShareLinkStatus status = link.status(clock.instant());

        if (status != ShareLinkStatus.VALID) {
            throw new ShareLinkGoneException(status == ShareLinkStatus.REVOKED, link.getCreatedByName());
        }
        return link;
    }

    /**
     * The files a valid link opens of one of its versions, in order: all of a shared version, or the ones shared on
     * their own.
     *
     * @throws ResourceNotFoundException when the link opens nothing of the version
     */
    public List<ArchiveFile> sharedFiles(final ShareLink link, final UUID versionId) {
        final List<ArchiveFile> files = sharedFiles(link).getOrDefault(versionId, List.of());

        if (files.isEmpty()) {
            throw new ResourceNotFoundException("error.version.notFound", versionId);
        }
        return files;
    }

    /**
     * The file, when a valid link opens it.
     */
    public ArchiveFile sharedFile(final ShareLink link, final UUID fileId) {
        return sharedFiles(link).values().stream().flatMap(List::stream)
                .filter(file -> file.getUuid().equals(fileId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("error.file.notFound", fileId));
    }

    /**
     * Every file a link opens, by version, of the published versions outside the trash.
     */
    private Map<UUID, List<ArchiveFile>> sharedFiles(final ShareLink link) {
        final Set<MaterialVersion> versions = new LinkedHashSet<>(link.getVersions());

        link.getFiles().forEach(file -> versions.add(file.getVersion()));

        final Map<UUID, List<ArchiveFile>> result = new LinkedHashMap<>();

        final Set<ArchiveFile> parts = new HashSet<>(spexParts(link));

        parts.forEach(file -> versions.add(file.getVersion()));
        for (final MaterialVersion version : versions) {
            if (version.isListed()) {
                final Predicate<ArchiveFile> shared = file -> link.getVersions().contains(version) || link.getFiles().contains(file)
                        || parts.contains(file);

                result.put(version.getUuid(), version.getFiles().stream().filter(shared).toList());
            }
        }
        return result;
    }

    /**
     * For a link made for instruments: the parts it opens across its spex' arrangements now, in the order of the show,
     * so parts added after the link was made are included.
     */
    public List<ArchiveFile> spexParts(final ShareLink link) {
        return link.getSpexId() == null ? List.of()
                : spexPartsFinder.find(link.getSpexId(), link.partFilter(), MaterialVersion::isListed).stream().map(SpexParts.Part::file).toList();
    }

    private List<SharedVersionDto> sharedVersions(final ShareLink link) {
        final Map<UUID, List<ArchiveFile>> files = sharedFiles(link);
        final List<MaterialVersion> versions = versionRepository.findByUuidIn(files.keySet());
        final Map<UUID, SpexNumber> numbers = new HashMap<>();

        versions.stream().filter(version -> version.getArrangement() != null)
                .forEach(version -> numberRepository.findByUuid(Objects.requireNonNull(version.getArrangement()).getNumberId())
                        .ifPresent(number -> numbers.put(version.getUuid(), number)));

        final Set<UUID> spexIds = new LinkedHashSet<>();

        for (final MaterialVersion version : versions) {
            if (version.getManuscript() != null) {
                spexIds.add(version.getManuscript().getSpexId());
            } else if (numbers.containsKey(version.getUuid())) {
                spexIds.add(numbers.get(version.getUuid()).getSpexId());
            }
            if (version.getUsedInSpexId() != null) {
                spexIds.add(version.getUsedInSpexId());
            }
        }

        final Map<UUID, SpexDto> spex = spexService.byIds(spexIds);
        final List<SharedVersionDto> result = new ArrayList<>();

        for (final MaterialVersion version : versions) {
            final SpexNumber number = numbers.get(version.getUuid());
            final UUID spexId = version.getManuscript() != null ? version.getManuscript().getSpexId()
                    : number == null ? null : number.getSpexId();
            final List<InstrumentationDto> instrumentation = MaterialDtos.instrumentation(version);
            final List<FileDto> shared = files.get(version.getUuid()).stream().map(file -> FileDtos.toDto(file, true, false)).toList();

            result.add(new SharedVersionDto(version.getUuid(), version.getKind(),
                    version.getKind() == MaterialKind.MANUSCRIPT ? Objects.requireNonNull(version.getManuscript()).getTitle()
                            : number == null ? "" : number.getTitle(),
                    version.getLabel(), spexId == null ? null : sharedSpex(spex.get(spexId)),
                    version.getUsedInSpexId() == null || !spex.containsKey(version.getUsedInSpexId()) ? null
                            : sharedSpex(spex.get(version.getUsedInSpexId())),
                    number == null ? null : number.getPosition(), number == null ? null : number.getOriginalSong(),
                    number == null ? null : number.getLyricist(), number == null ? null : number.getComposer(),
                    version.getManuscript() == null ? null : version.getManuscript().getAuthors(), version.getArranger(),
                    version.getKey(), List.copyOf(version.getModulations()), version.getTempo(), version.getDuration(),
                    version.getDate() == null ? null : version.getDate().toString(), version.getNotes(), instrumentation, shared));
        }
        return result;
    }

    private static @Nullable SharedSpexDto sharedSpex(final @Nullable SpexDto spex) {
        return spex == null ? null : new SharedSpexDto(spex.id(), spex.title(), spex.year(), spex.categoryName());
    }

    /**
     * A new link: versions and files, and/or a spex with instruments (and optionally transpositions) whose parts it opens.
     */
    public record ShareLinkInput(@Nullable String name, @Nullable List<UUID> versionIds, @Nullable List<UUID> fileIds,
                                 @Nullable UUID spexId, @Nullable List<UUID> instrumentIds, @Nullable List<Pitch> transpositions,
                                 @Nullable Integer months) {
    }

    public record ShareLinkDto(UUID id, String name, String createdById, @Nullable String createdByName, Instant createdAt,
                               Instant expiresAt, @Nullable Instant revokedAt, @Nullable String revokedByName,
                               ShareLinkStatus status, List<UUID> versionIds, List<UUID> fileIds, @Nullable UUID spexId,
                               List<UUID> instrumentIds, List<Pitch> transpositions) {
    }

    public record CreatedShareLink(ShareLinkDto shareLink, String token) {
    }

    public record FileDownloads(FileDto file, int count) {
    }

    public record SharedViewDto(String name, ShareLinkStatus status, @Nullable String createdByName, Instant expiresAt,
                                List<SharedVersionDto> versions) {
    }

    public record SharedVersionDto(UUID id, MaterialKind kind, String title, String label, @Nullable SharedSpexDto spex,
                                   @Nullable SharedSpexDto usedIn, @Nullable Integer numberPosition,
                                   @Nullable String originalSong, @Nullable String lyricist, @Nullable String composer,
                                   @Nullable String authors, @Nullable String arranger, @Nullable MusicKey key,
                                   List<MusicKey> modulations, @Nullable String tempo, @Nullable String duration, @Nullable String date,
                                   @Nullable String notes, List<InstrumentationDto> instrumentation,
                                   List<FileDto> files) {
    }

    public record SharedSpexDto(UUID id, @Nullable String title, @Nullable Integer year, @Nullable String categoryName) {
    }
}
