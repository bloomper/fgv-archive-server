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

package nu.fgv.archive.server.spex;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The archive's copy of Spexregister's spex: refreshed every night, read for browsing, and the families of originals and
 * their revivals that material is grouped by.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class SpexService {

    static final Comparator<SpexDto> NEWEST_FIRST = Comparator
            .comparing((SpexDto spex) -> spex.year() == null ? 0 : spex.year()).reversed()
            .thenComparing(spex -> spex.title() == null ? "" : spex.title());

    private final SpexRepository repository;
    private final SpexregisterClient client;
    private final CallerService callerService;
    private final Clock clock;

    public SpexService(final SpexRepository repository, final SpexregisterClient client, final CallerService callerService,
                       final Clock clock) {
        this.repository = repository;
        this.client = client;
        this.callerService = callerService;
        this.clock = clock;
    }

    private static boolean matches(final SpexDto spex, final SpexFilter filter) {
        if (filter.categoryId() != null && !filter.categoryId().equals(spex.categoryId())) {
            return false;
        }
        if (filter.year() != null && !filter.year().equals(spex.year())) {
            return false;
        }
        if (StringUtils.hasText(filter.q())) {
            final String q = filter.q().strip().toLowerCase(Locale.ROOT);

            return (spex.title() != null && spex.title().toLowerCase(Locale.ROOT).contains(q))
                    || (spex.year() != null && spex.year().toString().equals(q));
        }
        return true;
    }

    /**
     * Copies every spex from Spexregister, adding new ones and updating the others. Spex that have left Spexregister are
     * kept, since material may refer to them.
     *
     * @throws SpexregisterClient.SpexregisterCallException when Spexregister cannot be reached
     */
    @SchedulerLock(name = "spex-refresh", lockAtMostFor = "PT30M")
    @Transactional
    public void refresh() {
        final Map<UUID, Spex> existing = repository.findAll().stream().collect(Collectors.toMap(Spex::getUuid, Function.identity()));
        final Instant now = clock.instant();
        int added = 0;

        for (final SpexregisterClient.RemoteSpex remote : client.allSpex()) {
            Spex spex = existing.get(remote.id());

            if (spex == null) {
                spex = new Spex();
                spex.setUuid(remote.id());
                added++;
            }
            spex.setParentId(remote.parentId());
            spex.setTitle(remote.title());
            spex.setYear(remote.year());
            spex.setRevival(remote.revival());
            spex.setCategoryId(remote.categoryId());
            spex.setCategoryName(remote.categoryName());
            spex.setRefreshedAt(now);
            repository.save(spex);
        }
        log.info("Refreshed the spex from Spexregister, {} new", added);
    }

    /**
     * Originals, newest first, where the original or one of its revivals matches the filter.
     *
     * @param families when given, only families it accepts, by original
     */
    @Transactional(readOnly = true)
    public List<SpexDto> list(final @Nullable SpexFilter filter, final @Nullable Predicate<UUID> families) {
        callerService.current().requireUser();

        final SpexFilter effective = filter == null ? new SpexFilter(null, null, null, null) : filter;
        final List<SpexDto> all = repository.findAll().stream().map(SpexDto::of).toList();
        final Map<UUID, SpexDto> byId = all.stream().collect(Collectors.toMap(SpexDto::id, Function.identity()));
        final Set<UUID> matching = new HashSet<>();

        for (final SpexDto spex : all) {
            if (matches(spex, effective)) {
                matching.add(byId.containsKey(spex.familyId()) ? spex.familyId() : spex.id());
            }
        }
        return matching.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(spex -> families == null || families.test(spex.id()))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Transactional(readOnly = true)
    public @Nullable SpexDto get(final UUID id) {
        callerService.current().requireUser();
        return repository.findByUuid(id).map(SpexDto::of).orElse(null);
    }

    /**
     * Spex by id, without checking the caller, for other services; ids not in the copy are left out.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SpexDto> byIds(final Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : repository.findByUuidIn(ids).stream().map(SpexDto::of)
                .collect(Collectors.toMap(SpexDto::id, Function.identity()));
    }

    /**
     * The revivals of each original, by year.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<SpexDto>> revivals(final Collection<UUID> originalIds) {
        final Map<UUID, List<SpexDto>> result = new LinkedHashMap<>();

        originalIds.forEach(id -> result.put(id, new ArrayList<>()));
        if (!originalIds.isEmpty()) {
            repository.findByParentIdIn(originalIds).stream()
                    .map(SpexDto::of)
                    .sorted(Comparator.comparing((SpexDto spex) -> spex.year() == null ? 0 : spex.year()))
                    .forEach(spex -> result.computeIfAbsent(Objects.requireNonNull(spex.parentId()), _ -> new ArrayList<>()).add(spex));
        }
        return result;
    }

    /**
     * A spex in the copy, or a bad request.
     */
    @Transactional(readOnly = true)
    public Spex require(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new BadRequestException("error.spex.notFound", id));
    }

    /**
     * The original and its revivals: the family of the given spex.
     */
    @Transactional(readOnly = true)
    public Set<UUID> family(final UUID spexId) {
        final UUID original = repository.findByUuid(spexId).map(Spex::familyId).orElse(spexId);
        final Set<UUID> family = new HashSet<>();

        family.add(original);
        repository.findByParentIdOrderByYearAsc(original).forEach(revival -> family.add(revival.getUuid()));
        return family;
    }

    /**
     * Every spex, without checking the caller, for other services.
     */
    @Transactional(readOnly = true)
    public List<SpexDto> all() {
        return repository.findAll().stream().map(SpexDto::of).toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        callerService.current().requireUser();

        final Map<UUID, CategoryDto> categories = new LinkedHashMap<>();

        for (final Spex spex : repository.findAll()) {
            if (spex.getCategoryId() != null) {
                final CategoryDto current = categories.get(spex.getCategoryId());

                categories.put(spex.getCategoryId(), new CategoryDto(spex.getCategoryId(),
                        Objects.requireNonNullElse(spex.getCategoryName(), ""), current == null ? 1 : current.spexCount() + 1));
            }
        }
        return categories.values().stream().sorted(Comparator.comparing(CategoryDto::name)).toList();
    }

    @Transactional(readOnly = true)
    public List<Integer> years() {
        callerService.current().requireUser();
        return repository.findAll().stream().map(Spex::getYear).filter(Objects::nonNull).distinct()
                .sorted(Comparator.reverseOrder()).toList();
    }

    public record SpexFilter(@Nullable UUID categoryId, @Nullable Integer year, @Nullable String q,
                             @Nullable Boolean withMaterial) {
    }

    public record CategoryDto(UUID id, String name, int spexCount) {
    }
}
