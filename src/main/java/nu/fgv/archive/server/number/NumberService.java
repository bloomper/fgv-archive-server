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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.archive.server.material.Arrangement;
import nu.fgv.archive.server.material.ArrangementRepository;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The numbers of the spex, copied from Spexipedia, where they are kept (Spexarkivet's spec, 4.4): read for spex and
 * arrangements, and merged when Spexipedia merges them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class NumberService {

    static final Comparator<NumberDto> SHOW_ORDER = Comparator.comparingInt(NumberDto::act).thenComparingInt(NumberDto::position);

    private final SpexNumberRepository repository;
    private final ArrangementRepository arrangementRepository;
    private final SpexService spexService;
    private final CallerService callerService;

    public NumberService(final SpexNumberRepository repository, final ArrangementRepository arrangementRepository,
                         final SpexService spexService, final CallerService callerService) {
        this.repository = repository;
        this.arrangementRepository = arrangementRepository;
        this.spexService = spexService;
        this.callerService = callerService;
    }

    public static NumberDto toDto(final SpexNumber number) {
        return new NumberDto(number.getUuid(), number.getSpexId(), number.getAct(), number.getPosition(), number.getTitle(),
                number.getOriginalSong(), number.getLyricist(), number.getComposer(), number.isRetired());
    }

    /**
     * The numbers of each spex in the order of the show. A revival has its original's, then those of its own (Spexipedia
     * keeps what a revival cuts or moves on its page; the archive shows them all). Numbers Spexipedia retired are left
     * out unless they have an arrangement.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SpexNumbers> numbersOf(final Collection<UUID> spexIds) {
        callerService.current().requireUser();

        final Map<UUID, SpexDto> known = spexService.byIds(spexIds);
        final Set<UUID> wanted = new HashSet<>(spexIds);

        known.values().forEach(dto -> wanted.add(dto.familyId()));

        final List<SpexNumber> found = repository.findBySpexIdInOrderByActAscPositionAscIdAsc(wanted);
        final Set<UUID> arranged = found.stream().anyMatch(SpexNumber::isRetired)
                ? arrangementRepository.findByNumberIdIn(found.stream().filter(SpexNumber::isRetired).map(SpexNumber::getUuid).toList())
                .stream().map(Arrangement::getNumberId).collect(Collectors.toSet())
                : Set.of();
        final Map<UUID, List<NumberDto>> numbers = found.stream()
                .filter(number -> !number.isRetired() || arranged.contains(number.getUuid()))
                .map(NumberService::toDto)
                .collect(Collectors.groupingBy(NumberDto::spexId, LinkedHashMap::new, Collectors.toList()));
        final Map<UUID, SpexNumbers> result = new LinkedHashMap<>();

        for (final UUID id : spexIds) {
            final List<NumberDto> own = numbers.getOrDefault(id, List.of());
            final SpexDto dto = known.get(id);
            final List<NumberDto> original = dto != null && dto.parentId() != null ? numbers.getOrDefault(dto.parentId(), List.of()) : List.of();

            if (original.isEmpty()) {
                result.put(id, new SpexNumbers(own, false));
            } else {
                final List<NumberDto> all = new ArrayList<>(original);

                all.addAll(own);
                all.sort(SHOW_ORDER);
                result.put(id, new SpexNumbers(all, true));
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

    /**
     * Spexipedia merged a number into another: its arrangement moves to the other number, or its versions into the
     * other number's arrangement, and the number is deleted. The target keeps its recommended version. A number already
     * gone is skipped; when the target is not here yet, the arrangement waits on the merged number, which stays.
     */
    @Transactional
    public void mergeFromSpexipedia(final UUID id, final UUID intoId) {
        final SpexNumber number = repository.findByUuid(id).orElse(null);

        if (number == null || id.equals(intoId)) {
            return;
        }
        if (repository.findByUuid(intoId).isEmpty()) {
            log.warn("Number {} was merged into {}, which is not here yet; kept", id, intoId);
            return;
        }

        final Arrangement source = arrangementRepository.findByNumberId(id).orElse(null);

        if (source != null) {
            final Arrangement target = arrangementRepository.findByNumberId(intoId).orElse(null);

            if (target == null) {
                source.setNumberId(intoId);
            } else {
                for (final MaterialVersion version : List.copyOf(source.getVersions())) {
                    version.setArrangement(target);
                    version.setRecommended(version.isRecommended() && target.getVersions().stream().noneMatch(MaterialVersion::isRecommended));
                    target.getVersions().add(version);
                }
                source.getVersions().clear();
                arrangementRepository.delete(source);
            }
            arrangementRepository.flush();
        }
        repository.delete(number);
        log.info("Merged number {} into {}", id, intoId);
    }

    /**
     * @param act      the act, from 1
     * @param position the position in the act, from 1
     * @param retired  whether Spexipedia no longer has it on the spex
     */
    public record NumberDto(UUID id, UUID spexId, int act, int position, String title, @Nullable String originalSong,
                            @Nullable String lyricist, @Nullable String composer, boolean retired) {
    }

    /**
     * The numbers shown for a spex, and whether some are its original's.
     */
    public record SpexNumbers(List<NumberDto> numbers, boolean inherited) {
    }
}
