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

package nu.fgv.archive.server.mcp;

import nu.fgv.archive.server.AbstractIntegrationTest;
import nu.fgv.archive.server.util.data.SampleFiles;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP tools: only reading, the archive's search and material as the caller sees them, restricted text kept from
 * those who may not read it, and only {@code fgv-archive-mcp} tokens with the endpoint's audience let in.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class McpToolsIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    @TempDir
    private Path folder;

    private static Map<String, Object> args(final Object... pairs) {
        final Map<String, Object> map = new HashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    private ResponseEntity<String> post(final @Nullable String token, final String method, final Map<String, Object> params) {
        return http().post()
                .uri(MCP_PATH)
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", REQUEST_ID.incrementAndGet(), "method", method, "params", params))
                .retrieve()
                .onStatus(_ -> true, (_, _) -> {
                })
                .toEntity(String.class);
    }

    private JsonNode rpc(final String user, final String method, final Map<String, Object> params) {
        final ResponseEntity<String> response = post(mcpToken(user), method, params);

        assertThat(response.getStatusCode()).as("%s %s", method, response.getBody()).isEqualTo(HttpStatus.OK);
        return JSON.readTree(response.getBody());
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        return rpc(user, "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private JsonNode ok(final String user, final String tool, final Map<String, Object> arguments) {
        final JsonNode response = call(user, tool, arguments);
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        // No links to files, nor their contents other than extracted text
        assertThat(response.toString()).as(tool).doesNotContain("/api/files").doesNotContain("/api/share").doesNotContain("%PDF");
        return JSON.readTree(result.get("content").get(0).get("text").asString());
    }

    /**
     * A manuscript of Cleopatra 2024 with a published version holding a three-page PDF, its text extracted.
     *
     * @return the version's and the file's ids
     */
    private List<String> manuscriptWithText(final boolean restricted) throws IOException {
        final String versionId = graphQl(EDITOR)
                .document("mutation($spex: ID!) { manuscriptCreate(input: {spexId: $spex, title: \"Cleopatra\"}) { versions { id } } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("manuscriptCreate.versions[0].id").entity(String.class).get();
        final Path pdf = folder.resolve("Manus.pdf");

        SampleFiles.manuscript(pdf, "Cleopatra", "Original", 3);

        final String fileId = graphQl(EDITOR)
                .document("mutation($input: FileAddInput!) { fileAdd(input: $input) { id } }")
                .variable("input", Map.of("versionId", versionId, "uploadId", upload(EDITOR, pdf)))
                .execute()
                .path("fileAdd.id").entity(String.class).get();

        if (restricted) {
            jdbcClient.sql("UPDATE material_version SET restricted = 1 WHERE uuid = ?").param(versionId).update();
        }
        graphQl(EDITOR)
                .document("mutation($id: ID!) { versionPublish(id: $id, published: true) { published } }")
                .variable("id", versionId)
                .execute()
                .path("versionPublish.published").entity(Boolean.class).isEqualTo(true);
        assertThat(fileProcessor.processPending()).isEqualTo(1);
        return List.of(versionId, fileId);
    }

    @Test
    void theToolsOnlyRead() {
        final List<String> names = new ArrayList<>();

        for (final JsonNode tool : rpc(VIEWER, "tools/list", Map.of()).path("result").path("tools")) {
            names.add(tool.get("name").asString());
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(false)).as(tool.get("name").asString()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(tool.get("name").asString()).isFalse();
        }

        assertThat(names).containsExactlyInAnyOrder("search_archive", "list_spex", "list_spex_material", "get_material", "get_file_text");
    }

    @Test
    void aUserSearchesTheTextAndReadsThePage() throws IOException {
        final List<String> ids = manuscriptWithText(false);
        final JsonNode found = ok(VIEWER, "search_archive", args("query", SampleFiles.SEARCH_WORD));
        final JsonNode hit = found.get("textHits").get(0);

        assertThat(hit.get("page").asInt()).isEqualTo(2);
        assertThat(hit.get("excerpt").asString()).containsIgnoringCase(SampleFiles.SEARCH_WORD);
        assertThat(hit.get("file").get("id").asString()).isEqualTo(ids.get(1));
        assertThat(hit.get("file").get("pages").asInt()).isEqualTo(3);
        assertThat(hit.get("version").get("id").asString()).isEqualTo(ids.get(0));
        assertThat(hit.get("material").get("kind").asString()).isEqualTo("MANUSCRIPT");
        assertThat(hit.get("material").get("spex").get("title").asString()).isEqualTo("Cleopatra");

        final JsonNode spex = ok(VIEWER, "list_spex", args()).get("items");

        assertThat(spex.size()).isEqualTo(1);
        assertThat(spex.get(0).get("id").asString()).isEqualTo(SPEX_ID);
        assertThat(spex.get(0).get("manuscripts").asInt()).isEqualTo(1);
        assertThat(spex.get(0).get("revivals").get(0).get("id").asString()).isEqualTo(REVIVAL_ID);
        assertThat(ok(VIEWER, "list_spex", args("includeWithoutMaterial", true)).get("items").size()).isEqualTo(2);

        // A revival has its original's manuscripts
        final JsonNode material = ok(VIEWER, "list_spex_material", args("spexId", REVIVAL_ID));

        assertThat(material.get("original").get("id").asString()).isEqualTo(SPEX_ID);
        assertThat(material.get("manuscripts").get(0).get("versions").asInt()).isEqualTo(1);

        final JsonNode manuscript = ok(VIEWER, "get_material", args("materialId", material.get("manuscripts").get(0).get("id").asString()));
        final JsonNode file = manuscript.get("versions").get(0).get("files").get(0);

        assertThat(file.get("pages").asInt()).isEqualTo(3);
        assertThat(file.get("hasText").asBoolean()).isTrue();
        assertThat(file.get("canOpen").asBoolean()).isTrue();
        assertThat(file.has("downloadCount")).isFalse();

        final JsonNode text = ok(VIEWER, "get_file_text", args("fileId", ids.get(1), "fromPage", 2, "toPage", 2));

        assertThat(text.get("pages").size()).isEqualTo(1);
        assertThat(text.get("pages").get(0).get("page").asInt()).isEqualTo(2);
        assertThat(text.get("pages").get(0).get("text").asString()).containsIgnoringCase(SampleFiles.SEARCH_WORD);
        assertThat(text.get("nextPage").asInt()).isEqualTo(3);
        assertThat(text.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void restrictedTextIsOnlyForEditors() throws IOException {
        final String fileId = manuscriptWithText(true).get(1);

        assertThat(ok(VIEWER, "search_archive", args("query", SampleFiles.SEARCH_WORD)).get("textHits").size()).isZero();
        assertThat(ok(EDITOR, "search_archive", args("query", SampleFiles.SEARCH_WORD)).get("textHits").size()).isPositive();

        final JsonNode refusal = call(VIEWER, "get_file_text", args("fileId", fileId));

        assertThat(refused(refusal)).isTrue();
        assertThat(refusal.toString()).contains("FORBIDDEN").doesNotContainIgnoringCase(SampleFiles.SEARCH_WORD);
        assertThat(ok(EDITOR, "get_file_text", args("fileId", fileId)).get("pages").size()).isEqualTo(3);
    }

    @Test
    void anUnpublishedVersionAndAUserWithoutRolesSeeNothing() {
        final String manuscriptId = graphQl(EDITOR)
                .document("mutation($spex: ID!) { manuscriptCreate(input: {spexId: $spex}) { id } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("manuscriptCreate.id").entity(String.class).get();

        assertThat(call(VIEWER, "get_material", args("materialId", manuscriptId)).toString()).contains("NOT_FOUND");
        assertThat(ok(EDITOR, "get_material", args("materialId", manuscriptId)).get("versions").get(0).get("published").asBoolean()).isFalse();
        assertThat(call(NO_ACCESS, "list_spex", args()).toString()).contains("FORBIDDEN");
    }

    @Test
    void onlyMcpTokensWithTheEndpointsAudienceGetIn() {
        final ResponseEntity<String> none = post(null, "tools/list", Map.of());

        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(none.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("resource_metadata");
        // The web's tokens, and an MCP client's without the audience
        for (final String token : List.of(token(VIEWER), token(VIEWER, UNBOUND_MCP_CLIENT_ID))) {
            assertThat(post(token, "tools/list", Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // And MCP tokens are not accepted by the rest of the API
        final HttpStatusCode graphql = http().post()
                .uri("/api/graphql")
                .header(HttpHeaders.AUTHORIZATION, mcpToken(VIEWER))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "{ me { roles } }"))
                .retrieve()
                .onStatus(_ -> true, (_, _) -> {
                })
                .toBodilessEntity()
                .getStatusCode();

        assertThat(graphql).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http().get().uri("/.well-known/oauth-protected-resource/api/mcp").retrieve().body(String.class))
                .contains("/api/mcp").contains("authorization_servers");
    }
}
