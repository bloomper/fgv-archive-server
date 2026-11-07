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

import nu.fgv.archive.server.material.MaterialGraphqlApi.Keyed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fields with arguments are batched once per distinct arguments.
 */
class MaterialGraphqlApiTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Test
    void looksUpOncePerDistinctArgumentsAndAnswersEveryKey() {
        final Set<Keyed<String>> keys = new LinkedHashSet<>(List.of(new Keyed<>(FIRST, "a"), new Keyed<>(SECOND, "a"), new Keyed<>(FIRST, "b")));
        final List<String> calls = new ArrayList<>();

        final Map<Keyed<String>, String> result = MaterialGraphqlApi.byArguments(keys, (ids, arguments) -> {
            calls.add(arguments + ids.size());
            return ids.stream().collect(Collectors.toMap(id -> id, id -> arguments + id.toString().charAt(35)));
        });

        assertThat(calls).containsExactly("a2", "b1");
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                new Keyed<>(FIRST, "a"), "a1", new Keyed<>(SECOND, "a"), "a2", new Keyed<>(FIRST, "b"), "b1"));
    }
}
