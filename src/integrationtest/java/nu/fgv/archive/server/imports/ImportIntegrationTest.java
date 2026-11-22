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

package nu.fgv.archive.server.imports;

import nu.fgv.archive.server.AbstractIntegrationTest;
import nu.fgv.archive.server.util.data.SampleFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import API (spec 5.6): documents of a kind by name, arrangements of numbers, versions with files whose role and
 * instruments come from their names, repeatable by source key, and only for IMPORT.
 */
class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "fgv-content-migration";
    private static final String BATCH = """
            mutation($input: ImportBatchInput!, $dryRun: Boolean!) { importBatch(input: $input, dryRun: $dryRun) {
                created updated unchanged kept failed items { kind sourceKey id outcome message warnings }
            } }""";

    @TempDir
    private Path folder;

    private Map<String, String> upload(final Path file) throws Exception {
        final byte[] bytes = Files.readAllBytes(file);
        final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        http().put().uri("/api/import/files/" + sha).header("Authorization", serviceToken(MIGRATION)).body(bytes)
                .retrieve().toBodilessEntity();
        return Map.of("sha256", sha, "name", file.getFileName().toString());
    }

    private GraphQlTesterResponse batch(final Map<String, Object> input, final boolean dryRun) {
        return new GraphQlTesterResponse(graphQlWithToken(serviceToken(MIGRATION)).document(BATCH).variable("input", input).variable("dryRun", dryRun).execute());
    }

    private record GraphQlTesterResponse(org.springframework.graphql.test.tester.GraphQlTester.Response response) {
    }

    @Test
    void onlyImportReachesTheImportApi() {
        expectError(graphQl(ADMIN).document(BATCH).variable("input", Map.of()).variable("dryRun", false).execute(), "FORBIDDEN");
    }

    @Test
    void documentsAndArrangementsArriveWithTheirFilesAndAgainUnchanged() throws Exception {
        final String number = number(SPEX_ID, "Ouvertyr");
        final Path programme = folder.resolve("Program 2024.pdf");
        final Path part = folder.resolve("Trumpet 1.pdf");

        SampleFiles.manuscript(programme, "Cleopatra", "Program", 2);
        SampleFiles.music(part, "Ouvertyr", "Trumpet 1", 1, 1);

        final Map<String, Object> input = Map.of(
                "documents", List.of(Map.of("sourceKey", "test:doc", "spexId", SPEX_ID, "kind", "programblad")),
                "documentVersions", List.of(Map.of("sourceKey", "test:doc/v1", "parentSourceKey", "test:doc", "label", "Original 2024",
                        "files", List.of(Map.of("file", upload(programme))))),
                "arrangements", List.of(Map.of("sourceKey", "test:arr", "numberId", number)),
                "arrangementVersions", List.of(Map.of("sourceKey", "test:arr/v1", "parentSourceKey", "test:arr", "label", "Original",
                        "files", List.of(Map.of("file", upload(part))))));

        // A dry run keeps nothing
        batch(input, true).response().path("importBatch.created").entity(Integer.class).isEqualTo(4);
        graphQl(EDITOR).document("query($id: ID!) { spex(id: $id) { manuscripts { id } } }").variable("id", SPEX_ID).execute()
                .path("spex.manuscripts").entityList(Object.class).hasSize(0);

        final String doc = batch(input, false).response()
                .path("importBatch.created").entity(Integer.class).isEqualTo(4)
                .path("importBatch.items[0].id").entity(String.class).get();

        graphQl(EDITOR).document("""
                        query($id: ID!) { manuscript(id: $id) { kind { nameSv } versions { label published files { role { nameSv } } } } }""")
                .variable("id", doc).execute()
                .path("manuscript.kind.nameSv").entity(String.class).isEqualTo("Program")
                // The new document's empty Original was taken over, not kept beside the imported version
                .path("manuscript.versions[*].label").entityList(String.class).containsExactly("Original 2024")
                .path("manuscript.versions[0].published").entity(Boolean.class).isEqualTo(false);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM archive_file f JOIN file_instrument i ON i.file_id = f.id").query(Integer.class).single())
                .isEqualTo(1);

        batch(input, false).response().path("importBatch.unchanged").entity(Integer.class).isEqualTo(4);
        graphQlWithToken(serviceToken(MIGRATION)).document("mutation { importForget(sourcePrefix: \"test:\") }").execute()
                .path("importForget").entity(Integer.class).isEqualTo(4);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM manuscript").query(Integer.class).single()).isZero();
    }

    @Test
    void aDocumentMayBelongToNoSpex() {
        final String doc = batch(Map.of("documents", List.of(Map.of("sourceKey", "test:songbook", "kind", "Sånghäfte", "title", "Sånghäfte 2001"))), false)
                .response()
                .path("importBatch.created").entity(Integer.class).isEqualTo(1)
                .path("importBatch.items[0].id").entity(String.class).get();

        graphQl(EDITOR).document("query($id: ID!) { manuscript(id: $id) { spex { id } kind { nameSv } title } }").variable("id", doc).execute()
                .path("manuscript.spex").valueIsNull()
                .path("manuscript.kind.nameSv").entity(String.class).isEqualTo("Sångbok")
                .path("manuscript.title").entity(String.class).isEqualTo("Sånghäfte 2001");
    }

    @Test
    void anUnknownDocumentKindFailsWithTheKnownOnes() {
        batch(Map.of("documents", List.of(Map.of("sourceKey", "test:doc", "spexId", SPEX_ID, "kind", "Affisch"))), false).response()
                .path("importBatch.failed").entity(Integer.class).isEqualTo(1)
                .path("importBatch.items[0].message").entity(String.class).satisfies(message -> assertThat(message).contains("Affisch").contains("Manus"));
    }

    @Test
    void aRecordsMessageIsInTheLanguageAskedFor() {
        graphQlWithToken(serviceToken(MIGRATION)).document("""
                        mutation($input: ImportBatchInput!) { importBatch(input: $input, dryRun: true) {
                            items { outcome sv: message(locale: "sv") en: message(locale: "en") }
                        } }""")
                .variable("input", Map.of("documents", List.of(Map.of("sourceKey", " ", "spexId", SPEX_ID, "kind", "programblad")))).execute()
                .path("importBatch.items[0].outcome").entity(String.class).isEqualTo("FAILED")
                .path("importBatch.items[0].sv").entity(String.class).isEqualTo("Fältet sourceKey är inte giltigt.")
                .path("importBatch.items[0].en").entity(String.class).isEqualTo("The field sourceKey is not valid.");
    }
}
