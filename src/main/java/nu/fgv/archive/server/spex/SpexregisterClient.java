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

import nu.fgv.archive.server.config.CacheConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spexregister's GraphQL API, as its {@code MEDIA} role allows: spex, with their category.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpexregisterClient {

    static final int MAX_SPEX = 1000;
    private static final String ALL_SPEX = """
            query AllSpex($first: Int!, $filter: String!) {
                spexPaged(first: $first, filter: $filter, sort: ["year"], direction: ASC) {
                    edges { node { id year title revival parent { id } category { id name } } }
                }
            }""";

    private final HttpSyncGraphQlClient graphQlClient;

    public SpexregisterClient(final HttpSyncGraphQlClient spexregisterGraphQlClient) {
        this.graphQlClient = spexregisterGraphQlClient;
    }

    /**
     * Every spex, originals and revivals, by year.
     *
     * @throws SpexregisterCallException when Spexregister cannot be reached or answers with errors
     */
    @Cacheable(cacheNames = CacheConfig.ALL_SPEX)
    public List<RemoteSpex> allSpex() {
        try {
            final List<RemoteSpex> spex = new ArrayList<>();

            for (final String filter : List.of("parent:NULL", "parent!NULL")) {
                final ClientGraphQlResponse response = graphQlClient.document(ALL_SPEX)
                        .variable("first", MAX_SPEX)
                        .variable("filter", filter)
                        .executeSync();

                if (!response.getErrors().isEmpty() || !response.isValid()) {
                    throw new SpexregisterCallException("Response has errors: " + response.getErrors(), null);
                }

                final SpexConnection page = response.field("spexPaged").toEntity(SpexConnection.class);

                if (page != null && page.edges() != null) {
                    page.edges().stream().filter(Objects::nonNull).map(SpexEdge::node).filter(Objects::nonNull)
                            .map(SpexNode::toRemote).forEach(spex::add);
                }
            }
            return spex;
        } catch (final SpexregisterCallException e) {
            throw e;
        } catch (final RuntimeException e) {
            if (e instanceof RestClientException || e.getClass().getName().startsWith("org.springframework.graphql")) {
                throw new SpexregisterCallException(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), e);
            }
            throw e;
        }
    }

    record SpexConnection(@Nullable List<@Nullable SpexEdge> edges) {
    }

    record SpexEdge(@Nullable SpexNode node) {
    }

    record IdRef(String id) {
    }

    record CategoryNode(String id, @Nullable String name) {
    }

    record SpexNode(String id, @Nullable String year, @Nullable String title, @Nullable Boolean revival,
                    @Nullable IdRef parent, @Nullable CategoryNode category) {

        RemoteSpex toRemote() {
            Integer parsedYear = null;

            try {
                parsedYear = year == null ? null : Integer.valueOf(year);
            } catch (final NumberFormatException _) {
                // left out
            }
            return new RemoteSpex(UUID.fromString(id), title, parsedYear, Boolean.TRUE.equals(revival),
                    parent == null ? null : UUID.fromString(parent.id()),
                    category == null ? null : UUID.fromString(category.id()),
                    category == null ? null : category.name());
        }
    }

    /**
     * A spex as Spexregister knows it.
     *
     * @param id           its id in Spexregister
     * @param title        its title, shared with its original and revivals
     * @param year         the year it was played
     * @param revival      whether it is a revival of an older spex
     * @param parentId     the original, for a revival
     * @param categoryId   its category, e.g. Chalmersspexet
     * @param categoryName the category's name
     */
    public record RemoteSpex(UUID id, @Nullable String title, @Nullable Integer year, boolean revival,
                             @Nullable UUID parentId, @Nullable UUID categoryId, @Nullable String categoryName) {
    }

    /**
     * Spexregister could not be reached or answered with errors.
     */
    public static class SpexregisterCallException extends RuntimeException {

        public SpexregisterCallException(final String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
