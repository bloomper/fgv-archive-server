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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample data: built on Spexipedia's sample spex and fgv-infra-deploy's sample users, with share links in every status.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SampleDataImporter importer;

    /**
     * Spexipedia's feed with two originals, the newer with numbers.
     */
    private static void spexipediaFeed() {
        SPEXIPEDIA.resetAll();
        SPEXIPEDIA.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("baseDataChanges"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"baseDataChanges": {"cursor": "9", "hasMore": false, "changes": [
                          {"sequence": "1", "kind": "SPEX", "id": "00000000-0000-7000-8000-000000000002", "change": "UPSERT", "mergedIntoId": null,
                           "spex": {"id": "00000000-0000-7000-8000-000000000002", "year": "2020", "title": "Ali Baba", "categoryId": null, "parentId": null, "retired": false}},
                          {"sequence": "2", "kind": "SPEX", "id": "00000000-0000-7000-8000-000000000001", "change": "UPSERT", "mergedIntoId": null,
                           "spex": {"id": "00000000-0000-7000-8000-000000000001", "year": "2024", "title": "Cleopatra", "categoryId": null, "parentId": null, "retired": false}},
                  {"sequence": "3", "kind": "NUMBER", "id": "00000000-0000-7000-8000-0000000000e0", "change": "UPSERT", "mergedIntoId": null,
                   "number": {"id": "00000000-0000-7000-8000-0000000000e0", "spexId": "00000000-0000-7000-8000-000000000001", "act": 1, "position": 1,
                              "title": "Nummer 1", "originalTitle": null, "originalArtist": null, "retired": false}},
                  {"sequence": "4", "kind": "NUMBER", "id": "00000000-0000-7000-8000-0000000000e1", "change": "UPSERT", "mergedIntoId": null,
                   "number": {"id": "00000000-0000-7000-8000-0000000000e1", "spexId": "00000000-0000-7000-8000-000000000001", "act": 1, "position": 2,
                              "title": "Nummer 2", "originalTitle": null, "originalArtist": null, "retired": false}},
                  {"sequence": "5", "kind": "NUMBER", "id": "00000000-0000-7000-8000-0000000000e2", "change": "UPSERT", "mergedIntoId": null,
                   "number": {"id": "00000000-0000-7000-8000-0000000000e2", "spexId": "00000000-0000-7000-8000-000000000001", "act": 1, "position": 3,
                              "title": "Nummer 3", "originalTitle": null, "originalArtist": null, "retired": false}},
                  {"sequence": "6", "kind": "NUMBER", "id": "00000000-0000-7000-8000-0000000000e3", "change": "UPSERT", "mergedIntoId": null,
                   "number": {"id": "00000000-0000-7000-8000-0000000000e3", "spexId": "00000000-0000-7000-8000-000000000001", "act": 1, "position": 4,
                              "title": "Nummer 4", "originalTitle": null, "originalArtist": null, "retired": false}},
                  {"sequence": "7", "kind": "NUMBER", "id": "00000000-0000-7000-8000-0000000000e4", "change": "UPSERT", "mergedIntoId": null,
                   "number": {"id": "00000000-0000-7000-8000-0000000000e4", "spexId": "00000000-0000-7000-8000-000000000001", "act": 1, "position": 5,
                              "title": "Nummer 5", "originalTitle": null, "originalArtist": null, "retired": false}},
                  {"sequence": "8", "kind": "NUMBER", "id": "00000000-0000-7000-8000-0000000000e5", "change": "UPSERT", "mergedIntoId": null,
                   "number": {"id": "00000000-0000-7000-8000-0000000000e5", "spexId": "00000000-0000-7000-8000-000000000001", "act": 1, "position": 6,
                              "title": "Nummer 6", "originalTitle": null, "originalArtist": null, "retired": false}}
                        ]}}}""")));
    }

    @Test
    void importsMaterialUsersAndShareLinks() throws IOException {
        spexipediaFeed();
        importer.importSampleData();

        // fgv-infra-deploy's sample users, recorded by their fixed ids without asking Keycloak
        assertThat(jdbcClient.sql("SELECT subject FROM app_user ORDER BY subject").query(String.class).list()).containsExactly(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000005");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM manuscript").query(Long.class).single()).isPositive();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM arrangement").query(Long.class).single()).isPositive();
        // Every document kind has documents, and some are FGV's own, without a spex
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM document_kind k WHERE NOT EXISTS (SELECT 1 FROM manuscript m WHERE m.kind_id = k.id)")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM manuscript WHERE spex_id IS NULL").query(Long.class).single()).isPositive();
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
