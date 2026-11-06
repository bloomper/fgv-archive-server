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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queries become boolean full-text queries; hits show the text around them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SearchServiceTest {

    @Test
    void everyLongEnoughWordIsRequiredAsAPrefix() {
        assertThat(SearchService.fullTextQuery("Kungens kålsoppa, i år!")).isEqualTo("+kungens* +kålsoppa*");
        assertThat(SearchService.fullTextQuery("+a -b \"c\"")).isNull();
    }

    @Test
    void theSnippetSurroundsTheFirstHit() {
        final String text = "x".repeat(200) + " Vem har stulit min kungamantel? " + "y".repeat(200);
        final String snippet = SearchService.snippet(text, List.of("kungamantel"));

        assertThat(snippet).contains("kungamantel").startsWith("…").endsWith("…");
        assertThat(SearchService.snippet("kort text", List.of("saknas"))).isEqualTo("kort text");
    }
}
