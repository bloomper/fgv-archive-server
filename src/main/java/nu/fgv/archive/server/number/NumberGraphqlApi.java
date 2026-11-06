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

import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import nu.fgv.archive.server.number.NumberService.NumberInput;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GraphQL for numbers. A thin adapter: {@link NumberService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class NumberGraphqlApi {

    private final NumberService numberService;
    private final MaterialService materialService;
    private final SpexService spexService;

    public NumberGraphqlApi(final NumberService numberService, final MaterialService materialService, final SpexService spexService) {
        this.numberService = numberService;
        this.materialService = materialService;
        this.spexService = spexService;
    }

    @QueryMapping
    public @Nullable NumberDto number(@Argument final UUID id) {
        return numberService.get(id);
    }

    @QueryMapping
    public List<NumberDto> missingArrangements(@Argument final @Nullable UUID categoryId) {
        final List<UUID> ids = materialService.numbersWithoutArrangement(categoryId);
        final Map<UUID, NumberDto> numbers = numberService.byIds(ids);

        return ids.stream().map(numbers::get).filter(Objects::nonNull).toList();
    }

    @BatchMapping(typeName = "Number", field = "spex")
    public Map<NumberDto, SpexDto> spex(final List<NumberDto> numbers) {
        final Map<UUID, SpexDto> spex = spexService.byIds(numbers.stream().map(NumberDto::spexId).distinct().toList());
        final Map<NumberDto, SpexDto> result = new LinkedHashMap<>();

        numbers.forEach(number -> result.put(number, spex.get(number.spexId())));
        return result;
    }

    @BatchMapping(typeName = "Number", field = "arrangement")
    public Map<NumberDto, @Nullable ArrangementDto> arrangement(final List<NumberDto> numbers) {
        final Map<UUID, ArrangementDto> arrangements = materialService.arrangementsOfNumbers(numbers.stream().map(NumberDto::id).distinct().toList());
        final Map<NumberDto, @Nullable ArrangementDto> result = new LinkedHashMap<>();

        numbers.forEach(number -> result.put(number, arrangements.get(number.id())));
        return result;
    }

    @MutationMapping
    public NumberDto numberCreate(@Argument final UUID spexId, @Argument final NumberInput input) {
        return numberService.create(spexId, input);
    }

    @MutationMapping
    public NumberDto numberUpdate(@Argument final UUID id, @Argument final NumberInput input) {
        return numberService.update(id, input);
    }

    @MutationMapping
    public @Nullable Void numberDelete(@Argument final UUID id) {
        numberService.delete(id);
        return null;
    }

    @MutationMapping
    public List<NumberDto> numbersPaste(@Argument final UUID spexId, @Argument final String text) {
        return numberService.paste(spexId, text);
    }

    @MutationMapping
    public List<NumberDto> numbersReorder(@Argument final UUID spexId, @Argument final List<UUID> ids) {
        return numberService.reorder(spexId, ids);
    }

    @MutationMapping
    public NumberDto numberMerge(@Argument final UUID id, @Argument final UUID intoId) {
        return numberService.merge(id, intoId);
    }
}
