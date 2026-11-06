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

package nu.fgv.archive.server.number;

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.material.Arrangement;
import nu.fgv.archive.server.material.ArrangementRepository;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.spex.Spex;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ConflictException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The numbers of the spex, kept by editors until Spexipedia takes them over (product spec 4.4). A revival without
 * numbers of its own shows its original's. A number with an arrangement is never deleted, only merged into another
 * number of the same spex, which takes its arrangement.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class NumberService {

    static final int MAX_TEXT = 255;
    static final int MAX_PASTE = 200;
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*\\d{1,3}\\s*[.):-]?\\s+");
    private static final Pattern MELODY = Pattern.compile("^(.*\\S)\\s*\\((.+)\\)\\s*$");

    private final SpexNumberRepository repository;
    private final ArrangementRepository arrangementRepository;
    private final SpexService spexService;
    private final CallerService callerService;
    private final AuditService auditService;

    public NumberService(final SpexNumberRepository repository, final ArrangementRepository arrangementRepository,
                         final SpexService spexService, final CallerService callerService, final AuditService auditService) {
        this.repository = repository;
        this.arrangementRepository = arrangementRepository;
        this.spexService = spexService;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    public static NumberDto toDto(final SpexNumber number) {
        return new NumberDto(number.getUuid(), number.getSpexId(), number.getPosition(), number.getTitle(),
                number.getOriginalSong(), number.getLyricist(), number.getComposer());
    }

    private static @Nullable String text(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.strip().length() > MAX_TEXT) {
            throw new BadRequestException("error.number.tooLong", MAX_TEXT);
        }
        return value.strip();
    }

    private static String title(final @Nullable String value) {
        final String title = text(value);

        if (title == null) {
            throw new BadRequestException("error.number.titleRequired");
        }
        return title;
    }

    /**
     * Parses one pasted line: tab-separated columns (title, original song, lyricist, composer), or a title with an
     * optional leading number and the original song in parentheses.
     */
    static NumberInput parseLine(final String line) {
        final String[] columns = line.split("\t", -1);

        if (columns.length > 1) {
            return new NumberInput(LEADING_NUMBER.matcher(columns[0]).replaceFirst("").strip(),
                    column(columns, 1), column(columns, 2), column(columns, 3));
        }

        final String withoutNumber = LEADING_NUMBER.matcher(line).replaceFirst("").strip();
        final Matcher melody = MELODY.matcher(withoutNumber);

        return melody.matches()
                ? new NumberInput(melody.group(1).strip(), melody.group(2).strip(), null, null)
                : new NumberInput(withoutNumber, null, null, null);
    }

    private static @Nullable String column(final String[] columns, final int index) {
        return index < columns.length && StringUtils.hasText(columns[index]) ? columns[index].strip() : null;
    }

    /**
     * The numbers of each spex in the order of the show: its own, or its original's for a revival without any.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SpexNumbers> numbersOf(final Collection<UUID> spexIds) {
        callerService.current().requireUser();

        final Map<UUID, SpexDto> known = spexService.byIds(spexIds);
        final Set<UUID> wanted = new HashSet<>(spexIds);

        known.values().forEach(dto -> wanted.add(dto.familyId()));

        final Map<UUID, List<NumberDto>> numbers = repository.findBySpexIdInOrderByPositionAscIdAsc(wanted).stream()
                .map(NumberService::toDto)
                .collect(Collectors.groupingBy(NumberDto::spexId, LinkedHashMap::new, Collectors.toList()));
        final Map<UUID, SpexNumbers> result = new LinkedHashMap<>();

        for (final UUID id : spexIds) {
            final List<NumberDto> own = numbers.getOrDefault(id, List.of());
            final SpexDto dto = known.get(id);

            if (own.isEmpty() && dto != null && dto.parentId() != null) {
                result.put(id, new SpexNumbers(numbers.getOrDefault(dto.parentId(), List.of()), true));
            } else {
                result.put(id, new SpexNumbers(own, false));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public @Nullable NumberDto get(final UUID id) {
        callerService.current().requireUser();
        return repository.findByUuid(id).map(NumberService::toDto).orElse(null);
    }

    /**
     * Numbers by id, without checking the caller, for other services.
     */
    @Transactional(readOnly = true)
    public Map<UUID, NumberDto> byIds(final Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : repository.findByUuidIn(ids).stream().map(NumberService::toDto)
                .collect(Collectors.toMap(NumberDto::id, Function.identity()));
    }

    /**
     * Every number, without checking the caller, for other services.
     */
    @Transactional(readOnly = true)
    public List<NumberDto> all() {
        return repository.findAll().stream().map(NumberService::toDto).toList();
    }

    @Transactional
    public NumberDto create(final UUID spexId, final NumberInput input) {
        callerService.current().requireEditor();

        final Spex spex = spexService.require(spexId);
        final SpexNumber number = new SpexNumber();

        number.setSpexId(spex.getUuid());
        apply(number, input);
        number.setPosition(repository.findBySpexIdOrderByPositionAscIdAsc(spexId).size() + 1);
        repository.save(number);
        auditService.record("CREATE", "NUMBER", number.getUuid(), "Lade till numret " + number.getTitle() + " i " + spex.getTitle());
        return toDto(number);
    }

    @Transactional
    public NumberDto update(final UUID id, final NumberInput input) {
        callerService.current().requireEditor();

        final SpexNumber number = number(id);

        apply(number, input);
        auditService.record("UPDATE", "NUMBER", number.getUuid(), "Ändrade numret " + number.getTitle());
        return toDto(number);
    }

    @Transactional
    public List<NumberDto> paste(final UUID spexId, final String text) {
        callerService.current().requireEditor();

        final Spex spex = spexService.require(spexId);
        final List<String> lines = text.lines().filter(StringUtils::hasText).toList();

        if (lines.isEmpty() || lines.size() > MAX_PASTE) {
            throw new BadRequestException("error.number.paste", MAX_PASTE);
        }

        int position = repository.findBySpexIdOrderByPositionAscIdAsc(spexId).size();
        final List<NumberDto> created = new ArrayList<>();

        for (final String line : lines) {
            final SpexNumber number = new SpexNumber();

            number.setSpexId(spexId);
            apply(number, parseLine(line));
            number.setPosition(++position);
            repository.save(number);
            created.add(toDto(number));
        }
        auditService.record("CREATE", "NUMBER", null, "Lade till %d nummer i %s".formatted(created.size(), spex.getTitle()));
        return created;
    }

    @Transactional
    public List<NumberDto> reorder(final UUID spexId, final List<UUID> ids) {
        callerService.current().requireEditor();

        final List<SpexNumber> numbers = repository.findBySpexIdOrderByPositionAscIdAsc(spexId);
        final Map<UUID, SpexNumber> byId = numbers.stream().collect(Collectors.toMap(SpexNumber::getUuid, Function.identity()));

        if (ids.size() != numbers.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.number.reorder");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "NUMBER", null, "Ändrade ordningen på numren i " + spexService.require(spexId).getTitle());
        return ids.stream().map(byId::get).map(NumberService::toDto).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireEditor();

        final SpexNumber number = number(id);

        if (arrangementRepository.findByNumberId(number.getUuid()).isPresent()) {
            throw new ConflictException("error.number.hasArrangement");
        }
        repository.delete(number);
        renumber(number.getSpexId());
        auditService.record("DELETE", "NUMBER", number.getUuid(), "Raderade numret " + number.getTitle());
    }

    /**
     * Merges a number into another of the same spex: its arrangement moves to the other number, or its versions into
     * the other number's arrangement, and the number is deleted. When both have arrangements, both must be in the trash
     * or neither: the versions then stay as they were, in a live arrangement or with the trashed one (restored with it).
     * An arrangement that moves alone keeps its trash state.
     */
    @Transactional
    public NumberDto merge(final UUID id, final UUID intoId) {
        callerService.current().requireEditor();

        final SpexNumber number = number(id);
        final SpexNumber into = number(intoId);

        if (number.getUuid().equals(into.getUuid()) || !number.getSpexId().equals(into.getSpexId())) {
            throw new BadRequestException("error.number.merge");
        }

        final Arrangement source = arrangementRepository.findByNumberId(number.getUuid()).orElse(null);

        if (source != null) {
            final Arrangement target = arrangementRepository.findByNumberId(into.getUuid()).orElse(null);

            // Versions moved between a trashed and a live arrangement would come back from the trash, or vanish into it
            if (target != null && (source.getTrashedAt() == null) != (target.getTrashedAt() == null)) {
                throw new ConflictException("error.number.mergeTrashed");
            }
            if (target == null) {
                source.setNumberId(into.getUuid());
            } else {
                for (final MaterialVersion version : List.copyOf(source.getVersions())) {
                    version.setArrangement(target);
                    // The target keeps its recommended version
                    version.setRecommended(version.isRecommended() && target.getVersions().stream().noneMatch(MaterialVersion::isRecommended));
                    target.getVersions().add(version);
                }
                source.getVersions().clear();
                arrangementRepository.delete(source);
            }
            arrangementRepository.flush();
        }
        repository.delete(number);
        renumber(number.getSpexId());
        auditService.record("UPDATE", "NUMBER", into.getUuid(), "Slog ihop numret %s med %s".formatted(number.getTitle(), into.getTitle()));
        return toDto(into);
    }

    private void renumber(final UUID spexId) {
        final List<SpexNumber> numbers = repository.findBySpexIdOrderByPositionAscIdAsc(spexId);

        for (int i = 0; i < numbers.size(); i++) {
            numbers.get(i).setPosition(i + 1);
        }
    }

    private void apply(final SpexNumber number, final NumberInput input) {
        number.setTitle(title(input.title()));
        number.setOriginalSong(text(input.originalSong()));
        number.setLyricist(text(input.lyricist()));
        number.setComposer(text(input.composer()));
    }

    private SpexNumber number(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.number.notFound", id));
    }

    public record NumberDto(UUID id, UUID spexId, int position, String title, @Nullable String originalSong,
                            @Nullable String lyricist, @Nullable String composer) {
    }

    public record NumberInput(@Nullable String title, @Nullable String originalSong, @Nullable String lyricist,
                              @Nullable String composer) {
    }

    /**
     * The numbers shown for a spex, and whether they are its original's.
     */
    public record SpexNumbers(List<NumberDto> numbers, boolean inherited) {
    }
}
