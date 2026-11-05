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
 * Spexarkivet's settings under {@code archive.*}.
 *
 * @param timeZone the zone the jobs' schedules and "today" are in
 * @param webUrl   the web's address, for the links in emails
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties(prefix = "archive")
public record ArchiveProperties(String timeZone,
                                String webUrl,
                                Keycloak keycloak,
                                Security security,
                                Storage storage,
                                Worker worker,
                                Spexregister spexregister,
                                Mail mail,
                                Data data) {

    public record Keycloak(String url, String realm, String serviceClientId, String serviceClientSecret,
                           String adminClientId, String adminClientSecret) {
    }

    public record Security(List<String> authorizedParties) {
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

    public record Spexregister(String graphqlUrl) {
    }

    public record Mail(String from) {
    }

    public record Data(boolean importSampleData, String sampleDataSize, String sampleUserPassword) {
    }
}
