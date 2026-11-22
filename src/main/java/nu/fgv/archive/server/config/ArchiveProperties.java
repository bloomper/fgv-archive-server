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

package nu.fgv.archive.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * Spexarkiv's settings under {@code archive.*}.
 *
 * @param timeZone the zone the jobs' schedules and "today" are in
 * @param webUrl   the web's address, for the links in emails
 * @param baseUrl  the API's public address, e.g. {@code https://api.archive.fgv.local.nu:8443}, for the MCP endpoint's
 *                 address, which MCP tokens must have as audience
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties(prefix = "archive")
public record ArchiveProperties(String timeZone,
                                Imports imports,
                                String webUrl,
                                String baseUrl,
                                Keycloak keycloak,
                                Security security,
                                Mcp mcp,
                                Storage storage,
                                Worker worker,
                                Spexipedia spexipedia,
                                Mail mail,
                                Data data,
                                Media media) {

    public record Keycloak(String url, String realm, String serviceClientId, String serviceClientSecret) {
    }

    /**
     * Signed links to first-page previews, which Spexipedia's spex pages show without a token.
     *
     * @param signingKey   the key the links are signed with; links are not offered without one
     * @param linkLifetime how long a link works
     */
    public record Media(String signingKey, java.time.Duration linkLifetime) {
    }

    public record Security(List<String> authorizedParties) {
    }

    /**
     * @param authorizedParties the Keycloak clients whose tokens the MCP endpoint accepts, e.g. {@code fgv-archive-mcp}
     */
    public record Mcp(List<String> authorizedParties) {
    }

    /**
     * @param root the folder holding {@code files/}, {@code previews/} and {@code uploads/}
     */
    public record Storage(Path root) {
    }

    /**
     * @param enabled whether this instance extracts text and renders previews; only the worker container does
     */
    public record Worker(boolean enabled) {
    }

    /**
     * @param graphqlUrl  Spexipedia's GraphQL API, e.g. {@code http://spexipedia-api:8080/api/graphql}
     * @param webUrl      Spexipedia's web, for links to its pages; empty for none
     * @param syncEnabled whether to follow its change feed
     */
    public record Spexipedia(String graphqlUrl, String webUrl, boolean syncEnabled) {
    }

    public record Mail(String from) {
    }

    public record Data(boolean importSampleData, String sampleDataSize) {
    }

    /**
     * @param root      files received through the import API, {@code files/<sha256>}
     * @param keepFiles how long a received file is kept when no batch uses it
     */
    public record Imports(java.nio.file.Path root, java.time.Duration keepFiles) {
    }
}
