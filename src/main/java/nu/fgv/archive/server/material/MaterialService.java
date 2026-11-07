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

package nu.fgv.archive.server.material;

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.Instrument;
import nu.fgv.archive.server.file.InstrumentRepository;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementInput;
import nu.fgv.archive.server.material.MaterialDtos.InstrumentationInput;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptInput;
import nu.fgv.archive.server.material.MaterialDtos.VersionDto;
import nu.fgv.archive.server.material.MaterialDtos.VersionInput;
import nu.fgv.archive.server.number.SpexNumber;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.settings.SettingsService;
import nu.fgv.archive.server.spex.Spex;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ConflictException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manuscripts, arrangements and their versions. Readers see published versions outside the trash, and a manuscript or
 * an arrangement once it has one; editors see and change everything. New versions start unpublished, restricted as the
 * settings say; at most one version of each is recommended.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class MaterialService {

    static final int MAX_TEXT = 255;
    static final int MAX_AUTHORS = 500;
    static final int MAX_INSTRUMENT_COUNT = 99;
    static final int MAX_MODULATIONS = 20;
    /**
     * Recommended first, then the newest by date, then by creation.
     */
    static final Comparator<MaterialVersion> VERSION_ORDER = Comparator
            .comparing((MaterialVersion version) -> !version.isRecommended())
            .thenComparing(MaterialVersion::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MaterialVersion::getId, Comparator.reverseOrder());

    private final ManuscriptRepository manuscriptRepository;
    private final ArrangementRepository arrangementRepository;
    private final MaterialVersionRepository versionRepository;
    private final SpexNumberRepository numberRepository;
    private final InstrumentRepository instrumentRepository;
    private final SpexService spexService;
    private final FileService fileService;
    private final SettingsService settingsService;
    private final CallerService callerService;
    private final AuditService auditService;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public MaterialService(final ManuscriptRepository manuscriptRepository, final ArrangementRepository arrangementRepository,
                           final MaterialVersionRepository versionRepository, final SpexNumberRepository numberRepository,
                           final InstrumentRepository instrumentRepository, final SpexService spexService,
                           final FileService fileService, final SettingsService settingsService,
                           final CallerService callerService, final AuditService auditService, final Clock clock) {
        this.manuscriptRepository = manuscriptRepository;
        this.arrangementRepository = arrangementRepository;
        this.versionRepository = versionRepository;
        this.numberRepository = numberRepository;
        this.instrumentRepository = instrumentRepository;
        this.spexService = spexService;
        this.fileService = fileService;
        this.settingsService = settingsService;
        this.callerService = callerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    private static @Nullable String text(final @Nullable String value, final int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.strip().length() > max) {
            throw new BadRequestException("error.text.tooLong", max);
        }
        return value.strip();
    }

    private static @Nullable String longText(final @Nullable String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static @Nullable LocalDate date(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.version.date");
        }
    }

    // ------------------------------------------------------------------------------------------------------- reading

    /**
     * Whether a version is in the key, whatever the spelling, and has parts the filter matches; null and empty filters
     * match everything.
     */
    public static boolean matches(final MaterialVersion version, final @Nullable MusicKey key, final PartFilter parts) {
        return (key == null || (version.getKey() != null && version.getKey().soundsLike(key)))
                && (parts.isEmpty() || version.getFiles().stream().anyMatch(parts::matchesPart));
    }

    private Caller user() {
        final Caller caller = callerService.current();

        caller.requireUser();
        return caller;
    }

    @Transactional(readOnly = true)
    public @Nullable ManuscriptDto manuscript(final UUID id) {
        final Caller caller = user();

        return manuscriptRepository.findByUuid(id).filter(manuscript -> MaterialDtos.sees(caller, manuscript))
                .map(MaterialDtos::toDto).orElse(null);
    }

    @Transactional(readOnly = true)
    public @Nullable ArrangementDto arrangement(final UUID id) {
        final Caller caller = user();

        return arrangementRepository.findByUuid(id).filter(arrangement -> MaterialDtos.sees(caller, arrangement))
                .map(MaterialDtos::toDto).orElse(null);
    }

    @Transactional(readOnly = true)
    public @Nullable VersionDto version(final UUID id) {
        final Caller caller = user();

        return versionRepository.findByUuid(id).filter(version -> MaterialDtos.sees(caller, version))
                .map(version -> MaterialDtos.toDto(version, caller)).orElse(null);
    }

    /**
     * Versions by id that the caller sees; others are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, VersionDto> versionsByIds(final Collection<UUID> ids) {
        final Caller caller = user();

        return ids.isEmpty() ? Map.of() : versionRepository.findByUuidIn(ids).stream()
                .filter(version -> MaterialDtos.sees(caller, version))
                .collect(Collectors.toMap(MaterialVersion::getUuid, version -> MaterialDtos.toDto(version, caller)));
    }

    @Transactional(readOnly = true)
    public Map<UUID, ManuscriptDto> manuscriptsByIds(final Collection<UUID> ids) {
        final Caller caller = user();

        return ids.isEmpty() ? Map.of() : manuscriptRepository.findByUuidIn(ids).stream()
                .filter(manuscript -> MaterialDtos.sees(caller, manuscript))
                .collect(Collectors.toMap(Manuscript::getUuid, MaterialDtos::toDto));
    }

    @Transactional(readOnly = true)
    public Map<UUID, ArrangementDto> arrangementsByIds(final Collection<UUID> ids) {
        final Caller caller = user();

        return ids.isEmpty() ? Map.of() : arrangementRepository.findByUuidIn(ids).stream()
                .filter(arrangement -> MaterialDtos.sees(caller, arrangement))
                .collect(Collectors.toMap(Arrangement::getUuid, MaterialDtos::toDto));
    }

    /**
     * The manuscripts of each spex' family outside the trash that the caller sees.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ManuscriptDto>> manuscriptsOf(final Collection<UUID> spexIds) {
        final Caller caller = user();
        final Map<UUID, UUID> families = new HashMap<>();

        final Map<UUID, SpexDto> known = spexService.byIds(spexIds);

        spexIds.forEach(id -> families.put(id, known.containsKey(id) ? known.get(id).familyId() : id));

        final Map<UUID, List<ManuscriptDto>> byFamily = manuscriptRepository.findBySpexIdInOrderByIdAsc(new HashSet<>(families.values())).stream()
                .filter(manuscript -> manuscript.getTrashedAt() == null && MaterialDtos.sees(caller, manuscript))
                .map(MaterialDtos::toDto)
                .collect(Collectors.groupingBy(ManuscriptDto::spexId));
        final Map<UUID, List<ManuscriptDto>> result = new LinkedHashMap<>();

        spexIds.forEach(id -> result.put(id, byFamily.getOrDefault(families.get(id), List.of())));
        return result;
    }

    /**
     * The arrangement of each number, when the caller sees it and it is outside the trash.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ArrangementDto> arrangementsOfNumbers(final Collection<UUID> numberIds) {
        final Caller caller = user();

        return numberIds.isEmpty() ? Map.of() : arrangementRepository.findByNumberIdIn(numberIds).stream()
                .filter(arrangement -> arrangement.getTrashedAt() == null && MaterialDtos.sees(caller, arrangement))
                .collect(Collectors.toMap(Arrangement::getNumberId, MaterialDtos::toDto));
    }

    /**
     * The arrangements of a spex' numbers (its own, or its original's for a revival without any) that the caller sees,
     * in the order of the show; with a key or a part filter, only those with a version that matches.
     */
    @Transactional(readOnly = true)
    public List<ArrangementDto> arrangementsOfSpex(final UUID spexId, final @Nullable MusicKey key, final PartFilter parts) {
        final Caller caller = user();
        List<SpexNumber> numbers = numberRepository.findBySpexIdOrderByPositionAscIdAsc(spexId);
        final SpexDto spex = spexService.byIds(List.of(spexId)).get(spexId);

        if (numbers.isEmpty() && spex != null && spex.parentId() != null) {
            numbers = numberRepository.findBySpexIdOrderByPositionAscIdAsc(spex.parentId());
        }

        final Map<UUID, Arrangement> arrangements = arrangementRepository.findByNumberIdIn(numbers.stream().map(SpexNumber::getUuid).toList())
                .stream().collect(Collectors.toMap(Arrangement::getNumberId, Function.identity()));

        return numbers.stream()
                .map(number -> arrangements.get(number.getUuid()))
                .filter(Objects::nonNull)
                .filter(arrangement -> arrangement.getTrashedAt() == null && MaterialDtos.sees(caller, arrangement))
                .filter(arrangement -> (key == null && parts.isEmpty()) || arrangement.getVersions().stream()
                        .anyMatch(version -> version.getTrashedAt() == null && MaterialDtos.sees(caller, version) && matches(version, key, parts)))
                .map(MaterialDtos::toDto)
                .toList();
    }

    /**
     * Arrangement versions in a key, whatever the spelling, that the caller sees outside the trash, for search.
     */
    @Transactional(readOnly = true)
    public List<VersionDto> versionsInKey(final MusicKey key, final PartFilter parts, final int limit) {
        final Caller caller = user();

        return versionRepository.findByKind(MaterialKind.ARRANGEMENT).stream()
                .filter(version -> !version.isInTrash() && MaterialDtos.sees(caller, version) && matches(version, key, parts))
                .limit(limit)
                .map(version -> MaterialDtos.toDto(version, caller))
                .toList();
    }

    /**
     * The versions of each manuscript or arrangement that the caller sees, recommended first; editors see trashed
     * versions only in the trash. With a key, only versions in it, whatever the spelling; with a part filter, only versions
     * with matching parts.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<VersionDto>> versionsOf(final Collection<UUID> parentIds, final @Nullable MusicKey key,
                                                  final PartFilter parts) {
        final Caller caller = user();
        final Map<UUID, List<VersionDto>> result = new LinkedHashMap<>();

        // One query for the manuscripts and one for the arrangements, not one per parent: a batch of parents
        final Map<UUID, List<MaterialVersion>> parents = new HashMap<>();

        parentIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (!parentIds.isEmpty()) {
            manuscriptRepository.findByUuidIn(parentIds).forEach(manuscript -> parents.put(manuscript.getUuid(), manuscript.getVersions()));
            arrangementRepository.findByUuidIn(parentIds).forEach(arrangement -> parents.putIfAbsent(arrangement.getUuid(), arrangement.getVersions()));
        }
        for (final UUID id : parentIds) {
            parents.getOrDefault(id, List.of()).stream()
                    .filter(version -> version.getTrashedAt() == null && MaterialDtos.sees(caller, version) && matches(version, key, parts))
                    .sorted(VERSION_ORDER)
                    .forEach(version -> result.get(id).add(MaterialDtos.toDto(version, caller)));
        }
        return result;
    }

    /**
     * The version of an arrangement whose parts the caller's zip of a spex' parts takes ({@link SpexParts#best}), or
     * null when the caller may open none.
     */
    @Transactional(readOnly = true)
    public @Nullable VersionDto partsVersion(final UUID arrangementId, final UUID spexId) {
        final Caller caller = user();

        return arrangementRepository.findByUuid(arrangementId)
                .flatMap(arrangement -> SpexParts.best(arrangement, spexId, SpexParts.openableBy(caller)))
                .map(version -> MaterialDtos.toDto(version, caller))
                .orElse(null);
    }

    /**
     * The most recently changed versions the caller sees.
     */
    @Transactional(readOnly = true)
    public List<VersionDto> recent(final int limit) {
        final Caller caller = user();
        final PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 100));

        return (caller.isEditor() ? versionRepository.findRecent(page) : versionRepository.findRecentListed(page)).stream()
                .map(version -> MaterialDtos.toDto(version, caller))
                .toList();
    }

    /**
     * How many manuscripts and arrangements of each family, by original, the caller sees outside the trash.
     */
    @Transactional(readOnly = true)
    public Map<UUID, MaterialCount> counts(final Collection<UUID> spexIds) {
        final Caller caller = user();
        final Map<UUID, UUID> familyOf = new HashMap<>();
        final Map<UUID, UUID> spexFamily = spexService.all().stream()
                .collect(Collectors.toMap(SpexDto::id, SpexDto::familyId));

        spexIds.forEach(id -> familyOf.put(id, spexFamily.getOrDefault(id, id)));

        final Map<UUID, Integer> manuscripts = new HashMap<>();
        final Map<UUID, Integer> arrangements = new HashMap<>();

        manuscriptRepository.findBySpexIdInOrderByIdAsc(new HashSet<>(familyOf.values())).stream()
                .filter(manuscript -> manuscript.getTrashedAt() == null && MaterialDtos.sees(caller, manuscript))
                .forEach(manuscript -> manuscripts.merge(manuscript.getSpexId(), 1, Integer::sum));

        final Set<UUID> familySpex = spexFamily.entrySet().stream()
                .filter(entry -> familyOf.containsValue(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        final Map<UUID, UUID> numberFamily = numberRepository.findBySpexIdInOrderByPositionAscIdAsc(familySpex).stream()
                .collect(Collectors.toMap(SpexNumber::getUuid, number -> spexFamily.getOrDefault(number.getSpexId(), number.getSpexId())));

        if (!numberFamily.isEmpty()) {
            arrangementRepository.findByNumberIdIn(numberFamily.keySet()).stream()
                    .filter(arrangement -> arrangement.getTrashedAt() == null && MaterialDtos.sees(caller, arrangement))
                    .forEach(arrangement -> arrangements.merge(numberFamily.get(arrangement.getNumberId()), 1, Integer::sum));
        }

        final Map<UUID, MaterialCount> result = new LinkedHashMap<>();

        spexIds.forEach(id -> result.put(id, new MaterialCount(manuscripts.getOrDefault(familyOf.get(id), 0),
                arrangements.getOrDefault(familyOf.get(id), 0))));
        return result;
    }

    /**
     * Original spex without a manuscript that has a published version, newest first.
     */
    @Transactional(readOnly = true)
    public List<UUID> spexWithoutManuscript(final @Nullable UUID categoryId) {
        callerService.current().requireEditor();

        final Set<UUID> covered = new HashSet<>(manuscriptRepository.findSpexWithPublished());

        return spexService.all().stream()
                .filter(spex -> spex.parentId() == null && !covered.contains(spex.id()))
                .filter(spex -> categoryId == null || categoryId.equals(spex.categoryId()))
                .sorted(Comparator.comparing((SpexDto spex) -> spex.year() == null ? 0 : spex.year()).reversed())
                .map(SpexDto::id)
                .toList();
    }

    /**
     * Numbers without an arrangement that has a published version, by spex, newest spex first.
     */
    @Transactional(readOnly = true)
    public List<UUID> numbersWithoutArrangement(final @Nullable UUID categoryId) {
        callerService.current().requireEditor();

        final Set<UUID> covered = new HashSet<>(arrangementRepository.findNumbersWithPublished());
        final Map<UUID, SpexDto> spex = spexService.all().stream()
                .collect(Collectors.toMap(SpexDto::id, Function.identity()));

        return numberRepository.findAll().stream()
                .filter(number -> !covered.contains(number.getUuid()))
                .filter(number -> categoryId == null || (spex.containsKey(number.getSpexId()) && categoryId.equals(spex.get(number.getSpexId()).categoryId())))
                .sorted(Comparator.comparing((SpexNumber number) -> {
                    final var dto = spex.get(number.getSpexId());

                    return dto == null || dto.year() == null ? 0 : dto.year();
                }).reversed().thenComparing(SpexNumber::getSpexId).thenComparing(SpexNumber::getPosition))
                .map(SpexNumber::getUuid)
                .toList();
    }

    // ----------------------------------------------------------------------------------------------------- changing

    @Transactional
    public ManuscriptDto createManuscript(final ManuscriptInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();
        if (input.spexId() == null) {
            throw new BadRequestException("error.manuscript.spexRequired");
        }

        final Spex spex = spexService.require(input.spexId());

        if (spex.getParentId() != null) {
            throw new BadRequestException("error.manuscript.revival");
        }

        final Manuscript manuscript = new Manuscript();

        manuscript.setSpexId(spex.getUuid());
        manuscript.setTitle(Objects.requireNonNullElse(text(input.title(), MAX_TEXT), Objects.requireNonNullElse(spex.getTitle(), "Manus")));
        manuscript.setAuthors(text(input.authors(), MAX_AUTHORS));
        manuscript.setDescription(longText(input.description()));
        manuscriptRepository.save(manuscript);

        final MaterialVersion version = newVersion(MaterialKind.MANUSCRIPT, "Original", spex.getUuid());

        version.setManuscript(manuscript);
        manuscript.getVersions().add(versionRepository.save(version));
        auditService.record("CREATE", "MANUSCRIPT", manuscript.getUuid(), "Skapade manuset " + manuscript.getTitle());
        return MaterialDtos.toDto(manuscript);
    }

    @Transactional
    public ManuscriptDto updateManuscript(final UUID id, final ManuscriptInput input) {
        callerService.current().requireEditor();

        final Manuscript manuscript = manuscriptEntity(id);

        manuscript.setTitle(Objects.requireNonNullElse(text(input.title(), MAX_TEXT), manuscript.getTitle()));
        manuscript.setAuthors(text(input.authors(), MAX_AUTHORS));
        manuscript.setDescription(longText(input.description()));
        auditService.record("UPDATE", "MANUSCRIPT", manuscript.getUuid(), "Ändrade manuset " + manuscript.getTitle());
        return MaterialDtos.toDto(manuscript);
    }

    @Transactional
    public ManuscriptDto trashManuscript(final UUID id, final boolean trashed) {
        callerService.current().requireEditor();

        final Manuscript manuscript = manuscriptEntity(id);

        manuscript.setTrashedAt(trashed ? clock.instant() : null);
        auditService.record(trashed ? "TRASH" : "RESTORE", "MANUSCRIPT", manuscript.getUuid(),
                (trashed ? "Lade manuset %s i papperskorgen" : "Återställde manuset %s").formatted(manuscript.getTitle()));
        return MaterialDtos.toDto(manuscript);
    }

    @Transactional
    public void deleteManuscript(final UUID id) {
        callerService.current().requireAdmin();

        final Manuscript manuscript = manuscriptEntity(id);

        if (manuscript.getTrashedAt() == null) {
            throw new BadRequestException("error.delete.notTrashed");
        }
        List.copyOf(manuscript.getVersions()).forEach(this::deleteVersionEntity);
        manuscriptRepository.delete(manuscript);
        auditService.record("DELETE", "MANUSCRIPT", manuscript.getUuid(), "Raderade manuset " + manuscript.getTitle());
    }

    @Transactional
    public ArrangementDto createArrangement(final ArrangementInput input) {
        callerService.current().requireEditor();
        if (input.numberId() == null) {
            throw new BadRequestException("error.arrangement.numberRequired");
        }

        final SpexNumber number = numberRepository.findByUuid(input.numberId())
                .orElseThrow(() -> new BadRequestException("error.number.notFound", input.numberId()));

        if (arrangementRepository.findByNumberId(number.getUuid()).isPresent()) {
            throw new ConflictException("error.arrangement.exists");
        }

        final Arrangement arrangement = new Arrangement();

        arrangement.setNumberId(number.getUuid());
        arrangement.setDescription(longText(input.description()));
        arrangementRepository.save(arrangement);

        final MaterialVersion version = newVersion(MaterialKind.ARRANGEMENT, "Original", number.getSpexId());

        version.setArrangement(arrangement);
        arrangement.getVersions().add(versionRepository.save(version));
        auditService.record("CREATE", "ARRANGEMENT", arrangement.getUuid(), "Skapade arrangemanget för " + number.getTitle());
        return MaterialDtos.toDto(arrangement);
    }

    @Transactional
    public ArrangementDto updateArrangement(final UUID id, final ArrangementInput input) {
        callerService.current().requireEditor();

        final Arrangement arrangement = arrangementEntity(id);

        arrangement.setDescription(longText(input.description()));
        auditService.record("UPDATE", "ARRANGEMENT", arrangement.getUuid(), "Ändrade arrangemanget för " + numberTitle(arrangement));
        return MaterialDtos.toDto(arrangement);
    }

    @Transactional
    public ArrangementDto trashArrangement(final UUID id, final boolean trashed) {
        callerService.current().requireEditor();

        final Arrangement arrangement = arrangementEntity(id);

        arrangement.setTrashedAt(trashed ? clock.instant() : null);
        auditService.record(trashed ? "TRASH" : "RESTORE", "ARRANGEMENT", arrangement.getUuid(),
                (trashed ? "Lade arrangemanget för %s i papperskorgen" : "Återställde arrangemanget för %s").formatted(numberTitle(arrangement)));
        return MaterialDtos.toDto(arrangement);
    }

    @Transactional
    public void deleteArrangement(final UUID id) {
        callerService.current().requireAdmin();

        final Arrangement arrangement = arrangementEntity(id);

        if (arrangement.getTrashedAt() == null) {
            throw new BadRequestException("error.delete.notTrashed");
        }
        List.copyOf(arrangement.getVersions()).forEach(this::deleteVersionEntity);
        arrangementRepository.delete(arrangement);
        auditService.record("DELETE", "ARRANGEMENT", arrangement.getUuid(), "Raderade arrangemanget för " + numberTitle(arrangement));
    }

    @Transactional
    public VersionDto createVersion(final UUID parentId, final VersionInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final Manuscript manuscript = manuscriptRepository.findByUuid(parentId).orElse(null);
        final Arrangement arrangement = manuscript != null ? null : arrangementRepository.findByUuid(parentId)
                .orElseThrow(() -> new ResourceNotFoundException("error.version.parentNotFound", parentId));
        final MaterialVersion version = newVersion(manuscript != null ? MaterialKind.MANUSCRIPT : MaterialKind.ARRANGEMENT, "", null);

        version.setManuscript(manuscript);
        version.setArrangement(arrangement);
        apply(version, input);
        versionRepository.save(version);
        (manuscript != null ? manuscript.getVersions() : Objects.requireNonNull(arrangement).getVersions()).add(version);
        auditService.record("CREATE", "VERSION", version.getUuid(), "Skapade versionen %s av %s".formatted(version.getLabel(), parentTitle(version)));
        return MaterialDtos.toDto(version, caller);
    }

    @Transactional
    public VersionDto updateVersion(final UUID id, final VersionInput input) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final MaterialVersion version = versionEntity(id);

        apply(version, input);
        auditService.record("UPDATE", "VERSION", version.getUuid(), "Ändrade versionen %s av %s".formatted(version.getLabel(), parentTitle(version)));
        return MaterialDtos.toDto(version, caller);
    }

    @Transactional
    public VersionDto copyVersion(final UUID id) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final MaterialVersion source = versionEntity(id);
        final MaterialVersion copy = newVersion(source.getKind(), "Kopia av " + source.getLabel(), source.getUsedInSpexId());

        copy.setManuscript(source.getManuscript());
        copy.setArrangement(source.getArrangement());
        copy.setLabel(copy.getLabel().length() > MAX_TEXT ? copy.getLabel().substring(0, MAX_TEXT) : copy.getLabel());
        copy.setDate(source.getDate());
        copy.setNotes(source.getNotes());
        copy.setArranger(source.getArranger());
        copy.setKey(source.getKey());
        copy.getModulations().addAll(source.getModulations());
        copy.setTempo(source.getTempo());
        copy.setDuration(source.getDuration());
        copy.setRestricted(source.isRestricted());
        source.getInstrumentation().forEach(entry ->
                copy.getInstrumentation().add(new InstrumentationEntry(entry.getInstrument(), entry.getCount(), entry.getPosition())));
        versionRepository.save(copy);
        auditService.record("CREATE", "VERSION", copy.getUuid(), "Kopierade versionen %s av %s".formatted(source.getLabel(), parentTitle(source)));
        return MaterialDtos.toDto(copy, caller);
    }

    @Transactional
    public VersionDto publishVersion(final UUID id, final boolean published) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final MaterialVersion version = versionEntity(id);

        if (version.isPublished() != published) {
            version.setPublished(published);
            version.setPublishedAt(published ? clock.instant() : null);
            auditService.record(published ? "PUBLISH" : "UNPUBLISH", "VERSION", version.getUuid(),
                    (published ? "Publicerade versionen %s av %s" : "Avpublicerade versionen %s av %s").formatted(version.getLabel(), parentTitle(version)));
        }
        return MaterialDtos.toDto(version, caller);
    }

    @Transactional
    public VersionDto recommendVersion(final UUID id, final boolean recommended) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final MaterialVersion version = versionEntity(id);
        final List<MaterialVersion> siblings = version.getManuscript() != null ? version.getManuscript().getVersions()
                : Objects.requireNonNull(version.getArrangement()).getVersions();

        if (recommended) {
            siblings.forEach(sibling -> sibling.setRecommended(sibling.getId().equals(version.getId())));
        } else {
            version.setRecommended(false);
        }
        auditService.record("UPDATE", "VERSION", version.getUuid(),
                (recommended ? "Rekommenderade versionen %s av %s" : "Slutade rekommendera versionen %s av %s").formatted(version.getLabel(), parentTitle(version)));
        return MaterialDtos.toDto(version, caller);
    }

    @Transactional
    public VersionDto trashVersion(final UUID id, final boolean trashed) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final MaterialVersion version = versionEntity(id);

        version.setTrashedAt(trashed ? clock.instant() : null);
        if (trashed) {
            version.setRecommended(false);
        }
        auditService.record(trashed ? "TRASH" : "RESTORE", "VERSION", version.getUuid(),
                (trashed ? "Lade versionen %s av %s i papperskorgen" : "Återställde versionen %s av %s").formatted(version.getLabel(), parentTitle(version)));
        return MaterialDtos.toDto(version, caller);
    }

    @Transactional
    public void deleteVersion(final UUID id) {
        callerService.current().requireAdmin();

        final MaterialVersion version = versionEntity(id);

        if (!version.isInTrash()) {
            throw new BadRequestException("error.delete.notTrashed");
        }
        deleteVersionEntity(version);
        auditService.record("DELETE", "VERSION", version.getUuid(), "Raderade versionen %s av %s".formatted(version.getLabel(), parentTitle(version)));
    }

    /**
     * Deletes everything in the trash with its files.
     *
     * @return how many manuscripts, arrangements and versions were deleted
     */
    @Transactional
    public int emptyTrash() {
        callerService.current().requireAdmin();

        int deleted = 0;

        for (final Manuscript manuscript : manuscriptRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()) {
            List.copyOf(manuscript.getVersions()).forEach(this::deleteVersionEntity);
            manuscriptRepository.delete(manuscript);
            deleted++;
        }
        for (final Arrangement arrangement : arrangementRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()) {
            List.copyOf(arrangement.getVersions()).forEach(this::deleteVersionEntity);
            arrangementRepository.delete(arrangement);
            deleted++;
        }
        for (final MaterialVersion version : versionRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc()) {
            deleteVersionEntity(version);
            deleted++;
        }
        auditService.record("DELETE", "TRASH", null, "Tömde papperskorgen (%d)".formatted(deleted));
        return deleted;
    }

    /**
     * What is in the trash: manuscripts and arrangements, and versions trashed on their own.
     */
    @Transactional(readOnly = true)
    public TrashDto trash() {
        final Caller caller = callerService.current();

        caller.requireEditor();
        return new TrashDto(
                manuscriptRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream().map(MaterialDtos::toDto).toList(),
                arrangementRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream().map(MaterialDtos::toDto).toList(),
                versionRepository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream()
                        .filter(version -> (version.getManuscript() == null || version.getManuscript().getTrashedAt() == null)
                                && (version.getArrangement() == null || version.getArrangement().getTrashedAt() == null))
                        .map(version -> MaterialDtos.toDto(version, caller))
                        .toList());
    }

    private void deleteVersionEntity(final MaterialVersion version) {
        fileService.deleteAll(List.copyOf(version.getFiles()));
        if (version.getManuscript() != null) {
            version.getManuscript().getVersions().remove(version);
        }
        if (version.getArrangement() != null) {
            version.getArrangement().getVersions().remove(version);
        }
        versionRepository.delete(version);
    }

    private MaterialVersion newVersion(final MaterialKind kind, final String label, final @Nullable UUID usedIn) {
        final MaterialVersion version = new MaterialVersion();
        final var settings = settingsService.current();

        version.setKind(kind);
        version.setLabel(label);
        version.setUsedInSpexId(usedIn);
        version.setRestricted(kind == MaterialKind.ARRANGEMENT ? settings.isArrangementRestricted() : settings.isManuscriptRestricted());
        return version;
    }

    private void apply(final MaterialVersion version, final VersionInput input) {
        final String label = text(input.label(), MAX_TEXT);

        if (label == null) {
            throw new BadRequestException("error.version.labelRequired");
        }
        version.setLabel(label);
        if (input.usedInSpexId() != null && !spexService.family(familySpex(version)).contains(input.usedInSpexId())) {
            throw new BadRequestException("error.version.usedIn");
        }
        version.setUsedInSpexId(input.usedInSpexId());
        version.setDate(date(input.date()));
        version.setNotes(longText(input.notes()));
        if (input.restricted() != null) {
            version.setRestricted(input.restricted());
        }
        if (version.getKind() == MaterialKind.ARRANGEMENT) {
            version.setArranger(text(input.arranger(), MAX_TEXT));
            version.setKey(input.key());
            if (input.modulations() != null) {
                if (input.modulations().size() > MAX_MODULATIONS) {
                    throw new BadRequestException("error.version.modulations", MAX_MODULATIONS);
                }
                version.getModulations().clear();
                version.getModulations().addAll(input.modulations());
            }
            version.setTempo(text(input.tempo(), 100));
            version.setDuration(text(input.duration(), 100));
            if (input.instrumentation() != null) {
                setInstrumentation(version, input.instrumentation());
            }
        }
    }

    private void setInstrumentation(final MaterialVersion version, final List<InstrumentationInput> entries) {
        final Map<UUID, Instrument> instruments = new HashMap<>();
        final Set<UUID> seen = new HashSet<>();

        version.getInstrumentation().clear();
        for (final InstrumentationInput entry : entries) {
            if (entry.count() < 1 || entry.count() > MAX_INSTRUMENT_COUNT || !seen.add(entry.instrumentId())) {
                throw new BadRequestException("error.version.instrumentation");
            }

            final Instrument instrument = instruments.computeIfAbsent(entry.instrumentId(), instrumentId -> instrumentRepository.findByUuid(instrumentId)
                    .orElseThrow(() -> new BadRequestException("error.instrument.notFound", instrumentId)));

            version.getInstrumentation().add(new InstrumentationEntry(instrument, entry.count(), version.getInstrumentation().size() + 1));
        }
    }

    /**
     * The spex whose family a version may be used in: the manuscript's spex or the arrangement's number's spex.
     */
    private UUID familySpex(final MaterialVersion version) {
        if (version.getManuscript() != null) {
            return version.getManuscript().getSpexId();
        }
        return numberRepository.findByUuid(Objects.requireNonNull(version.getArrangement()).getNumberId())
                .map(SpexNumber::getSpexId)
                .orElseThrow(() -> new BadRequestException("error.number.notFound", version.getArrangement().getNumberId()));
    }

    private String parentTitle(final MaterialVersion version) {
        return version.getManuscript() != null ? version.getManuscript().getTitle()
                : numberTitle(Objects.requireNonNull(version.getArrangement()));
    }

    private String numberTitle(final Arrangement arrangement) {
        return numberRepository.findByUuid(arrangement.getNumberId()).map(SpexNumber::getTitle).orElse("ett nummer");
    }

    private Manuscript manuscriptEntity(final UUID id) {
        return manuscriptRepository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.manuscript.notFound", id));
    }

    private Arrangement arrangementEntity(final UUID id) {
        return arrangementRepository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.arrangement.notFound", id));
    }

    private MaterialVersion versionEntity(final UUID id) {
        return versionRepository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.version.notFound", id));
    }

    /**
     * How many manuscripts and arrangements a spex' family has that the caller sees.
     */
    public record MaterialCount(int manuscripts, int arrangements) {
    }

    public record TrashDto(List<ManuscriptDto> manuscripts, List<ArrangementDto> arrangements, List<VersionDto> versions) {
    }
}
