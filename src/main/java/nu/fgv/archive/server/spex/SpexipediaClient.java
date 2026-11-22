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

import nu.fgv.archive.server.util.StoredText;
import nu.fgv.archive.server.util.TextException;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Spexipedia's change feed, read with {@code fgv-archive-service}'s token, which holds {@code SYNC} on
 * {@code fgv-spexipedia}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpexipediaClient {

    static final int PAGE_SIZE = 500;
    private static final String CHANGES = """
            query Changes($after: String, $first: Int) {
                baseDataChanges(after: $after, first: $first) {
                    cursor hasMore
                    changes {
                        sequence kind id change mergedIntoId
                        spexCategory { id name }
                        spex { id year title categoryId parentId retired }
                        taskCategory { id name instruments retired }
                        task { id name categoryId retired }
                        character { id spexId name position retired }
                        number { id spexId act position title originalTitle originalArtist retired }
                    }
                }
            }""";

    private final HttpSyncGraphQlClient client;

    public SpexipediaClient(final HttpSyncGraphQlClient spexipediaGraphQlClient) {
        this.client = spexipediaGraphQlClient;
    }

    /**
     * The changes after the cursor (null for the start).
     *
     * @throws SpexipediaCallException when Spexipedia cannot be reached or answers with errors
     */
    public FeedChange.Page changes(final @Nullable String after) {
        final ClientGraphQlResponse response;

        try {
            response = client.document(CHANGES).variable("after", after).variable("first", PAGE_SIZE).executeSync();
        } catch (final RuntimeException e) {
            throw new SpexipediaCallException(StoredText.of("spexipedia.unreachable", String.valueOf(e.getMessage())), e);
        }
        if (!response.isValid() || !response.getErrors().isEmpty()) {
            throw new SpexipediaCallException(StoredText.of("spexipedia.answeredWithErrors", response.getErrors()), null);
        }
        return Objects.requireNonNull(response.field("baseDataChanges").toEntity(FeedChange.Page.class));
    }

    /**
     * Spexipedia could not be reached or answered with errors.
     */
    public static class SpexipediaCallException extends TextException {

        public SpexipediaCallException(final StoredText text, final @Nullable Throwable cause) {
            super(text, cause);
        }

        public SpexipediaCallException(final String message, final @Nullable Throwable cause) {
            this(StoredText.of("text.unexpected", message), cause);
        }
    }
}
