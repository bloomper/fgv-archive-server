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

import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.material.MusicKey;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * GraphQL for search. A thin adapter: {@link SearchService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SearchGraphqlApi {

    private final SearchService service;

    public SearchGraphqlApi(final SearchService service) {
        this.service = service;
    }

    @QueryMapping
    public SearchService.SearchResultDto search(@Argument final String q, @Argument final @Nullable PartFilter parts,
                                                @Argument final @Nullable MusicKey key) {
        return service.search(q, PartFilter.orNone(parts), key);
    }
}
