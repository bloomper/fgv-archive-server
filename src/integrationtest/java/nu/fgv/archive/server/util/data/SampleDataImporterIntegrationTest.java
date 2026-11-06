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

package nu.fgv.archive.server.util.data;

import nu.fgv.archive.server.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample data: built on Spexregister's sample spex and users, with share links in every status.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SampleDataImporter importer;

    @BeforeAll
    static void spexregisterUsers() {
        try (Keycloak admin = keycloakAdmin()) {
            final RealmResource realm = admin.realm("fgv");

            for (final String email : List.of(SampleDataImporter.ADMIN, SampleDataImporter.EDITOR, SampleDataImporter.USER)) {
                if (realm.users().searchByEmail(email, true).isEmpty()) {
                    grantClientRoles(realm, createUser(realm, email, "Exempel", List.of()), "spexregister", List.of("USER"));
                }
            }
        }
    }

    private static List<String> archiveRoles(final String email) {
        try (Keycloak admin = keycloakAdmin()) {
            final RealmResource realm = admin.realm("fgv");
            final String clientUuid = realm.clients().findByClientId(CLIENT_ID).getFirst().getId();

            return realm.users().get(realm.users().searchByEmail(email, true).getFirst().getId()).roles().clientLevel(clientUuid)
                    .listAll().stream().map(RoleRepresentation::getName).sorted().toList();
        }
    }

    @Test
    void importsMaterialUsersAndShareLinks() throws IOException {
        importer.importSampleData();

        assertThat(archiveRoles(SampleDataImporter.ADMIN)).containsExactly("ADMIN", "EDITOR", "USER");
        assertThat(archiveRoles(SampleDataImporter.EDITOR)).containsExactly("EDITOR", "USER");
        assertThat(archiveRoles(SampleDataImporter.USER)).containsExactly("USER");
        assertThat(archiveRoles(SampleDataImporter.NO_ACCESS)).isEmpty();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM manuscript").query(Long.class).single()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM arrangement").query(Long.class).single()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(DISTINCT transposition_pitch) FROM archive_file WHERE transposition_pitch IS NOT NULL")
                .query(Long.class).single()).isGreaterThanOrEqualTo(3);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM file_instrument GROUP BY file_id ORDER BY COUNT(*) DESC LIMIT 1")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM access_request").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM access_request WHERE handled_at IS NOT NULL AND handled_by_name IS NOT NULL")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM download_log").query(Long.class).single()).isPositive();

        for (final String[] link : List.of(new String[]{SampleDataImporter.VALID_TOKEN, "VALID"},
                new String[]{SampleDataImporter.EXPIRED_TOKEN, "EXPIRED"}, new String[]{SampleDataImporter.REVOKED_TOKEN, "REVOKED"},
                new String[]{SampleDataImporter.INSTRUMENT_TOKEN, "VALID"})) {
            graphQl(null)
                    .document("query($token: String!) { sharedView(token: $token) { status } }")
                    .variable("token", link[0])
                    .execute()
                    .path("sharedView.status").entity(String.class).isEqualTo(link[1]);
        }
        // The instrument link says what it was made for, and opens brass parts in B♭ and C only
        graphQl(null)
                .document("query($token: String!) { sharedView(token: $token) { spex { id } instruments { nameEn } transpositions versions { files { transposition { pitch } instruments { nameEn } } } } }")
                .variable("token", SampleDataImporter.INSTRUMENT_TOKEN)
                .execute()
                .path("sharedView.spex.id").hasValue()
                .path("sharedView.instruments[*].nameEn").entityList(String.class).containsExactly("Trumpet", "Trombone")
                .path("sharedView.transpositions").entityList(String.class).containsExactly("B_FLAT", "C")
                .path("sharedView.versions[*].files[*].transposition.pitch").entityList(String.class)
                .satisfies(pitches -> assertThat(pitches).isNotEmpty().allMatch(pitch -> pitch.equals("B_FLAT") || pitch.equals("C")));

        // The worker reads every file; the scan has no text
        assertThat(fileProcessor.processPending()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM archive_file WHERE processing_status <> 'DONE'").query(Long.class).single()).isZero();
        graphQl(ADMIN)
                .document("query($q: String!) { search(q: $q) { textHits { page } } }")
                .variable("q", SampleFiles.SEARCH_WORD)
                .execute()
                .path("search.textHits").entityList(Object.class).satisfies(hits -> assertThat(hits).isNotEmpty());
    }
}
