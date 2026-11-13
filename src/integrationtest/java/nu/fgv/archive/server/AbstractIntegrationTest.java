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

package nu.fgv.archive.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import nu.fgv.archive.server.file.FileStorage;
import nu.fgv.archive.server.processing.FileProcessor;
import nu.fgv.archive.server.spex.FeedChange;
import nu.fgv.archive.server.spex.SpexipediaSync;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JacksonJsonParser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL and Keycloak (with the committed copy of {@code fgv-keycloak}'s realm plus fictitious users) in Testcontainers,
 * and WireMock standing in for Spexipedia's GraphQL API. The worker is off; tests process files with
 * {@link FileProcessor#processPending()}. Every test starts from empty tables, the default roles, instruments and
 * settings, and an empty storage folder, with Spexipedia's spex copied from its feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
public abstract class AbstractIntegrationTest {

    protected static final String DOMAIN = "@archive.test";
    protected static final String VIEWER = "lasare" + DOMAIN;
    protected static final String EDITOR = "redaktor" + DOMAIN;
    protected static final String ADMIN = "admin" + DOMAIN;
    protected static final String NO_ACCESS = "utan" + DOMAIN;
    protected static final String PASSWORD = "s3cr3t-s3cr3t";
    protected static final String CLIENT_ID = "fgv-archive";
    /** Cleopatra 2024, an original. */
    protected static final String SPEX_ID = "00000000-0000-7000-8000-000000000001";
    /** Ali Baba 2020, another original. */
    protected static final String OTHER_SPEX_ID = "00000000-0000-7000-8000-000000000002";
    /** Cleopatra 2026, a revival of Cleopatra 2024. */
    protected static final String REVIVAL_ID = "00000000-0000-7000-8000-000000000003";
    protected static final String TRUMPET = "00000000-0000-7000-8000-00000000c00b";
    protected static final String TROMBONE = "00000000-0000-7000-8000-00000000c00d";
    protected static final String ALTO_SAX = "00000000-0000-7000-8000-00000000c008";
    protected static final WireMockServer SPEXIPEDIA = new WireMockServer(options().dynamicPort());
    protected static final Path STORAGE = Path.of("build/integrationtest-data/archive").toAbsolutePath();
    private static final String REALM = "fgv";
    private static final JacksonJsonParser JSON = new JacksonJsonParser();
    private static final Map<String, String> SECRETS = clientSecrets();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("mysqld", "--innodb-flush-log-at-trx-commit=0", "--innodb-doublewrite=0", "--skip-log-bin", "--performance-schema=OFF");
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8").withRealmImportFile("/realm/fgv.json");
    private static final List<String> TABLES = List.of("audit_entry", "download_log", "access_request_file", "access_request",
            "share_link_instrument", "share_link_file", "share_link_version", "share_link", "file_page", "file_instrument",
            "archive_file", "instrumentation_entry", "version_modulation", "material_version", "arrangement", "manuscript", "number",
            "spex", "spex_character", "spexipedia_task", "spexipedia_task_category", "spexipedia_category", "upload_session", "app_user",
            "file_role", "instrument", "settings");

    static {
        Startables.deepStart(MYSQL, KEYCLOAK).join();
        SPEXIPEDIA.start();
        prepareKeycloak();
    }

    @LocalServerPort
    protected int localPort;
    @Autowired
    protected JdbcClient jdbcClient;
    @Autowired
    protected FileStorage storage;
    @Autowired
    protected FileProcessor fileProcessor;
    @Autowired
    protected SpexipediaSync sync;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
        registry.add("spring.security.oauth2.client.registration.fgv-archive-service.client-secret", () -> SECRETS.get("fgv-archive-service"));
        registry.add("archive.keycloak.url", KEYCLOAK::getAuthServerUrl);
        registry.add("archive.keycloak.service-client-secret", () -> SECRETS.get("fgv-archive-service"));
        registry.add("archive.keycloak.admin-client-secret", () -> SECRETS.get("fgv-admin"));
        registry.add("archive.storage.root", STORAGE::toString);
        registry.add("archive.spexipedia.graphql-url", () -> SPEXIPEDIA.baseUrl() + "/api/graphql");
        // Tests apply the feed themselves
        registry.add("archive.spexipedia.sync-enabled", () -> "false");
    }

    /**
     * The client secrets in the committed realm copy, which are not real secrets.
     */
    private static Map<String, String> clientSecrets() {
        try (InputStream stream = AbstractIntegrationTest.class.getResourceAsStream("/realm/fgv.json")) {
            final Map<String, Object> realm = JSON.parseMap(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
            final Map<String, String> secrets = new ConcurrentHashMap<>();

            for (final Object client : (List<?>) realm.get("clients")) {
                if (client instanceof Map<?, ?> map && map.get("secret") instanceof String secret) {
                    secrets.put(String.valueOf(map.get("clientId")), secret);
                }
            }
            return secrets;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void prepareKeycloak() {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final ClientRepresentation web = realm.clients().findByClientId(CLIENT_ID).getFirst();

            web.setDirectAccessGrantsEnabled(true);
            realm.clients().get(web.getId()).update(web);

            createUser(realm, VIEWER, "Lisa", List.of("USER"));
            createUser(realm, EDITOR, "Erik", List.of("USER", "EDITOR"));
            createUser(realm, ADMIN, "Anna", List.of("USER", "EDITOR", "ADMIN"));
            createUser(realm, NO_ACCESS, "Nils", List.of());
        }
    }

    protected static String createUser(final RealmResource realm, final String email, final String firstName, final List<String> roles) {
        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName("Test");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            final String path = response.getLocation().getPath();
            final String userId = path.substring(path.lastIndexOf('/') + 1);

            grantClientRoles(realm, userId, CLIENT_ID, roles);
            return userId;
        }
    }

    protected static void grantClientRoles(final RealmResource realm, final String userId, final String clientId, final List<String> roles) {
        if (roles.isEmpty()) {
            return;
        }

        final ClientResource client = realm.clients().get(realm.clients().findByClientId(clientId).getFirst().getId());
        final List<RoleRepresentation> representations = roles.stream().map(role -> client.roles().get(role).toRepresentation()).toList();

        realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId()).add(representations);
    }

    protected static Keycloak keycloakAdmin() {
        return KEYCLOAK.getKeycloakAdminClient();
    }

    /**
     * A user's access token from {@code fgv-archive}, by password grant.
     */
    protected static String token(final String user) {
        return TOKENS.computeIfAbsent(user, _ -> {
            final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

            form.add("grant_type", "password");
            form.add("client_id", CLIENT_ID);
            form.add("client_secret", SECRETS.get(CLIENT_ID));
            form.add("username", user);
            form.add("password", PASSWORD);
            form.add("scope", "openid");

            final String response = RestClient.create()
                    .post()
                    .uri(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            return "Bearer " + JSON.parseMap(response).get("access_token");
        });
    }

    /**
     * Asserts that the response has an error with the given {@code extensions.classification}, e.g. {@code FORBIDDEN}.
     */
    protected static void expectError(final GraphQlTester.Response response, final String code) {
        response.errors().satisfy(errors -> assertThat(errors)
                .as("errors")
                .anySatisfy(error -> assertThat(String.valueOf(error.getErrorType())).isEqualTo(code)));
    }

    /**
     * Spexipedia's two originals and a revival, as its change feed brings them.
     */
    protected void spexipediaSpex() {
        final UUID category = UUID.fromString("00000000-0000-7000-8000-0000000000c1");

        sync.applyAll(List.of(
                new FeedChange("1", "SPEX_CATEGORY", category, "UPSERT", null, new FeedChange.SpexCategory(category, "Chalmersspexet"), null, null, null, null, null),
                spex(OTHER_SPEX_ID, "2020", "Ali Baba", null),
                spex(SPEX_ID, "2024", "Cleopatra", null),
                spex(REVIVAL_ID, "2026", "Cleopatra", SPEX_ID)));
    }

    private static FeedChange spex(final String id, final String year, final String title, final @Nullable String parentId) {
        final UUID uuid = UUID.fromString(id);

        return new FeedChange("1", "SPEX", uuid, "UPSERT", null, null, new FeedChange.Spex(uuid, year, title,
                UUID.fromString("00000000-0000-7000-8000-0000000000c1"), parentId == null ? null : UUID.fromString(parentId), false),
                null, null, null, null);
    }

    /**
     * A number of the spex, last in act 1, as Spexipedia's change feed brings it.
     *
     * @return its id
     */
    protected String number(final String spexId, final String title) {
        final UUID id = UUID.nameUUIDFromBytes(("number-" + spexId + "-" + title).getBytes(StandardCharsets.UTF_8));
        final int position = jdbcClient.sql("SELECT COUNT(*) FROM number WHERE spex_id = ?").param(spexId).query(Integer.class).single() + 1;

        sync.applyAll(List.of(new FeedChange("1", "NUMBER", id, "UPSERT", null, null, null, null, null, null,
                new FeedChange.Number(id, UUID.fromString(spexId), 1, position, title, null, null, false))));
        return id.toString();
    }

    /**
     * A service account's access token, by client credentials, e.g. {@code fgv-spexipedia-service}.
     */
    protected static String serviceToken(final String clientId) {
        return TOKENS.computeIfAbsent("client:" + clientId, _ -> {
            final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", SECRETS.get(clientId));
            return "Bearer " + JSON.parseMap(RestClient.create()
                    .post()
                    .uri(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class)).get("access_token");
        });
    }

    @BeforeEach
    void resetState() throws IOException {
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 0").update();
        for (final String table : TABLES) {
            jdbcClient.sql("TRUNCATE TABLE " + table).update();
        }
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 1").update();
        // Releases the scheduled jobs' locks; ShedLock keeps its rows, so they are not deleted
        jdbcClient.sql("UPDATE shedlock SET lock_until = CURRENT_TIMESTAMP(3)").update();
        // Back to the defaults of the migrations, which a test may have changed
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1_2__default_roles_and_instruments.sql")).execute(dataSource);
        jdbcClient.sql("""
                INSERT INTO settings (id, max_upload_mb, manuscript_restricted, arrangement_restricted, max_share_link_months)
                VALUES (1, 500, 0, 1, 24)""").update();
        storage.wipe();
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        spexipediaSpex();
    }

    protected HttpGraphQlTester graphQl(final @Nullable String user) {
        return graphQlWithToken(user == null ? null : token(user));
    }

    protected HttpGraphQlTester graphQlWithToken(final @Nullable String token) {
        return HttpGraphQlTester.create(WebTestClient.bindToServer()
                .baseUrl("http://localhost:%d/api/graphql".formatted(localPort))
                .responseTimeout(Duration.ofSeconds(60))
                .defaultHeaders(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
                })
                .build());
    }

    protected RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + localPort).build();
    }

    /**
     * Uploads a file with tus, in one chunk, as the given user, and returns the upload's id.
     */
    protected String upload(final String user, final Path file) throws IOException {
        final byte[] content = Files.readAllBytes(file);
        final String name = Base64.getEncoder().encodeToString(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        final String location = Objects.requireNonNull(http().post()
                .uri("/api/uploads")
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", String.valueOf(content.length))
                .header("Upload-Metadata", "filename " + name)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation()).toString();

        http().patch()
                .uri(location)
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .body(content)
                .retrieve()
                .toBodilessEntity();
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
