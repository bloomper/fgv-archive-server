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

import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MusicKey;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * GraphQL for spex: browsing, a spex page with its manuscripts and numbers, and the spex without a manuscript. A thin
 * adapter: the services decide access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexGraphqlApi {

    private final SpexService spexService;
    private final MaterialService materialService;
    private final NumberService numberService;

    public SpexGraphqlApi(final SpexService spexService, final MaterialService materialService, final NumberService numberService) {
        this.spexService = spexService;
        this.materialService = materialService;
        this.numberService = numberService;
    }

    private static <T> Map<SpexDto, T> bySpex(final List<SpexDto> spex, final Function<List<UUID>, Map<UUID, T>> lookup) {
        final Map<UUID, T> values = lookup.apply(spex.stream().map(SpexDto::id).distinct().toList());
        final Map<SpexDto, T> result = new LinkedHashMap<>();

        spex.forEach(dto -> result.put(dto, values.get(dto.id())));
        return result;
    }

    @QueryMapping
    public List<SpexService.CategoryDto> categories() {
        return spexService.categories();
    }

    @QueryMapping
    public List<Integer> years() {
        return spexService.years();
    }

    @QueryMapping
    public List<SpexDto> spexList(@Argument final SpexService.@Nullable SpexFilter filter) {
        if (filter != null && Boolean.TRUE.equals(filter.withMaterial())) {
            final List<SpexDto> all = spexService.list(filter, null);
            final Map<UUID, MaterialService.MaterialCount> counts = materialService.counts(all.stream().map(SpexDto::id).toList());

            return all.stream().filter(spex -> {
                final MaterialService.MaterialCount count = counts.get(spex.id());

                return count != null && count.manuscripts() + count.arrangements() > 0;
            }).toList();
        }
        return spexService.list(filter, null);
    }

    @QueryMapping
    public @Nullable SpexDto spex(@Argument final UUID id) {
        return spexService.get(id);
    }

    @QueryMapping
    public List<SpexDto> missingManuscripts(@Argument final @Nullable UUID categoryId) {
        final List<UUID> ids = materialService.spexWithoutManuscript(categoryId);
        final Map<UUID, SpexDto> spex = spexService.byIds(ids);

        return ids.stream().map(spex::get).filter(Objects::nonNull).toList();
    }

    @BatchMapping(typeName = "Spex", field = "original")
    public Map<SpexDto, @Nullable SpexDto> original(final List<SpexDto> spex) {
        final Map<UUID, SpexDto> parents = spexService.byIds(spex.stream().map(SpexDto::parentId).filter(Objects::nonNull).distinct().toList());
        final Map<SpexDto, @Nullable SpexDto> result = new LinkedHashMap<>();

        spex.forEach(dto -> result.put(dto, dto.parentId() == null ? null : parents.get(dto.parentId())));
        return result;
    }

    @BatchMapping(typeName = "Spex", field = "revivals")
    public Map<SpexDto, List<SpexDto>> revivals(final List<SpexDto> spex) {
        return bySpex(spex, spexService::revivals);
    }

    @BatchMapping(typeName = "Spex", field = "manuscripts")
    public Map<SpexDto, List<ManuscriptDto>> manuscripts(final List<SpexDto> spex) {
        return bySpex(spex, materialService::manuscriptsOf);
    }

    /**
     * The arrangements of the numbers shown for the spex, in the order of the show, narrowed when asked.
     */
    @SchemaMapping(typeName = "Spex", field = "arrangements")
    public List<ArrangementDto> arrangements(final SpexDto spex, @Argument final @Nullable MusicKey key,
                                             @Argument final @Nullable PartFilter parts) {
        return materialService.arrangementsOfSpex(spex.id(), key, PartFilter.orNone(parts));
    }

    @BatchMapping(typeName = "Spex", field = "numbers")
    public Map<SpexDto, List<NumberDto>> numbers(final List<SpexDto> spex) {
        final Map<SpexDto, NumberService.SpexNumbers> numbers = bySpex(spex, numberService::numbersOf);
        final Map<SpexDto, List<NumberDto>> result = new LinkedHashMap<>();

        numbers.forEach((dto, value) -> result.put(dto, value.numbers()));
        return result;
    }

    @BatchMapping(typeName = "Spex", field = "numbersInherited")
    public Map<SpexDto, Boolean> numbersInherited(final List<SpexDto> spex) {
        final Map<SpexDto, NumberService.SpexNumbers> numbers = bySpex(spex, numberService::numbersOf);
        final Map<SpexDto, Boolean> result = new LinkedHashMap<>();

        numbers.forEach((dto, value) -> result.put(dto, value.inherited()));
        return result;
    }

    @BatchMapping(typeName = "Spex", field = "manuscriptCount")
    public Map<SpexDto, Integer> manuscriptCount(final List<SpexDto> spex) {
        final Map<SpexDto, MaterialService.MaterialCount> counts = bySpex(spex, materialService::counts);
        final Map<SpexDto, Integer> result = new LinkedHashMap<>();

        counts.forEach((dto, count) -> result.put(dto, count.manuscripts()));
        return result;
    }

    @BatchMapping(typeName = "Spex", field = "arrangementCount")
    public Map<SpexDto, Integer> arrangementCount(final List<SpexDto> spex) {
        final Map<SpexDto, MaterialService.MaterialCount> counts = bySpex(spex, materialService::counts);
        final Map<SpexDto, Integer> result = new LinkedHashMap<>();

        counts.forEach((dto, count) -> result.put(dto, count.arrangements()));
        return result;
    }
}
