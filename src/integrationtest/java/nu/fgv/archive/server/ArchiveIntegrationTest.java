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

import nu.fgv.archive.server.file.FileRole;
import nu.fgv.archive.server.util.data.SampleFiles;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive end to end: restricted files, share links, byte ranges, search, numbers, instruments and transpositions.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ArchiveIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    private Path folder;

    private String manuscriptVersion(final boolean publish) {
        final String versionId = graphQl(EDITOR)
                .document("mutation($spex: ID!) { manuscriptCreate(input: {spexId: $spex}) { id versions { id restricted } } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("manuscriptCreate.versions[0].restricted").entity(Boolean.class).isEqualTo(false)
                .path("manuscriptCreate.versions[0].id").entity(String.class).get();

        if (publish) {
            publish(versionId);
        }
        return versionId;
    }

    private String arrangementVersion(final String spexId, final String title) {
        final String numberId = graphQl(EDITOR)
                .document("mutation($spex: ID!, $title: String!) { numberCreate(spexId: $spex, input: {title: $title}) { id } }")
                .variable("spex", spexId)
                .variable("title", title)
                .execute()
                .path("numberCreate.id").entity(String.class).get();

        return graphQl(EDITOR)
                .document("mutation($number: ID!) { arrangementCreate(input: {numberId: $number}) { versions { id restricted } } }")
                .variable("number", numberId)
                .execute()
                .path("arrangementCreate.versions[0].restricted").entity(Boolean.class).isEqualTo(true)
                .path("arrangementCreate.versions[0].id").entity(String.class).get();
    }

    private void publish(final String versionId) {
        graphQl(EDITOR)
                .document("mutation($id: ID!) { versionPublish(id: $id, published: true) { published } }")
                .variable("id", versionId)
                .execute()
                .path("versionPublish.published").entity(Boolean.class).isEqualTo(true);
    }

    private Path pdf(final String name, final int pages) throws IOException {
        final Path file = folder.resolve(name);

        if (name.startsWith("Manus")) {
            SampleFiles.manuscript(file, "Cleopatra", "Original", pages);
        } else {
            SampleFiles.music(file, "Ouvertyr", name, pages, 1);
        }
        return file;
    }

    /**
     * Adds a file, with its role and instruments guessed from its name unless given.
     */
    private String addFile(final String versionId, final Path file, final @Nullable String roleId,
                           final @Nullable List<String> instrumentIds, final @Nullable String pitch) throws IOException {
        final Map<String, Object> input = new HashMap<>();

        input.put("versionId", versionId);
        input.put("uploadId", upload(EDITOR, file));
        if (roleId != null) {
            input.put("roleId", roleId);
            input.put("instrumentIds", instrumentIds);
        }
        if (pitch != null) {
            input.put("transposition", Map.of("pitch", pitch, "octave", 0));
        }
        return graphQl(EDITOR)
                .document("mutation($input: FileAddInput!) { fileAdd(input: $input) { id } }")
                .variable("input", input)
                .execute()
                .path("fileAdd.id").entity(String.class).get();
    }

    private ResponseEntity<byte[]> get(final String path, final @Nullable String user, final @Nullable String range) {
        return http().get()
                .uri(path)
                .headers(headers -> {
                    if (user != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token(user));
                    }
                    if (range != null) {
                        headers.set(HttpHeaders.RANGE, range);
                    }
                })
                .retrieve()
                .onStatus(_ -> true, (_, _) -> {
                })
                .toEntity(byte[].class);
    }

    private String shareLink(final Map<String, Object> input) {
        return graphQl(EDITOR)
                .document("mutation($input: ShareLinkInput!) { shareLinkCreate(input: $input) { token shareLink { id status } } }")
                .variable("input", input)
                .execute()
                .path("shareLinkCreate.shareLink.status").entity(String.class).isEqualTo("VALID")
                .path("shareLinkCreate.token").entity(String.class).get();
    }

    @Test
    void aUserCannotOpenARestrictedFileButAnEditorCan() throws IOException {
        final String versionId = arrangementVersion(SPEX_ID, "Ouvertyr");
        final String fileId = addFile(versionId, pdf("Trumpet 1.pdf", 1), null, null, null);

        publish(versionId);

        graphQl(VIEWER)
                .document("query($id: ID!) { file(id: $id) { title canOpen role { id } instruments { id } desk transposition { pitch } } }")
                .variable("id", fileId)
                .execute()
                .path("file.title").entity(String.class).isEqualTo("Trumpet 1")
                .path("file.canOpen").entity(Boolean.class).isEqualTo(false)
                .path("file.role.id").entity(String.class).isEqualTo(FileRole.PART.toString())
                .path("file.instruments[0].id").entity(String.class).isEqualTo(TRUMPET)
                .path("file.desk").entity(String.class).isEqualTo("1")
                .path("file.transposition.pitch").entity(String.class).isEqualTo("B_FLAT");

        assertThat(get("/api/files/" + fileId, VIEWER, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/versions/" + versionId + "/parts/zip", VIEWER, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/files/" + fileId, NO_ACCESS, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/files/" + fileId, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        final ResponseEntity<byte[]> editor = get("/api/files/" + fileId + "?download=true", EDITOR, null);

        assertThat(editor.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(editor.getBody(), 0, 5)).isEqualTo("%PDF-");
        // Downloads of restricted files are logged with who
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM download_log WHERE user_subject IS NOT NULL").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anOpenFileIsServedWithByteRanges() throws IOException {
        final String versionId = manuscriptVersion(false);
        final String fileId = addFile(versionId, pdf("Manus.pdf", 3), null, null, null);

        publish(versionId);

        final ResponseEntity<byte[]> range = get("/api/files/" + fileId, VIEWER, "bytes=0-99");

        assertThat(range.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(range.getBody()).hasSize(100);
        assertThat(range.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).startsWith("bytes 0-99/");
    }

    @Test
    void anUnpublishedVersionIsHiddenFromUsers() throws IOException {
        final String versionId = manuscriptVersion(false);
        final String fileId = addFile(versionId, pdf("Manus.pdf", 2), null, null, null);

        graphQl(VIEWER).document("query($id: ID!) { version(id: $id) { id } }").variable("id", versionId).execute()
                .path("version").valueIsNull();
        assertThat(get("/api/files/" + fileId, VIEWER, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aShareLinkOpensExactlyItsFilesWithoutLoggingIn() throws IOException {
        final String versionId = arrangementVersion(SPEX_ID, "Ouvertyr");
        final String shared = addFile(versionId, pdf("Trumpet 1.pdf", 1), null, null, null);
        final String other = addFile(versionId, pdf("Trombon.pdf", 1), null, null, null);

        publish(versionId);

        final String token = shareLink(Map.of("name", "Ali Baba 2028, band", "fileIds", List.of(shared)));

        assertThat(get("/api/share/" + token + "/files/" + shared + "?download=true", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/share/" + token + "/files/" + other, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/share/not-a-token/files/" + shared, null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        graphQl(null)
                .document("query($token: String!) { sharedView(token: $token) { status name versions { id files { id canOpen } } } }")
                .variable("token", token)
                .execute()
                .path("sharedView.status").entity(String.class).isEqualTo("VALID")
                .path("sharedView.versions[*].id").entityList(String.class).containsExactly(versionId)
                .path("sharedView.versions[0].files[*].id").entityList(String.class).containsExactly(shared);
        // Downloads through a link are logged against the link, without a user
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM download_log WHERE share_link_id IS NOT NULL AND user_subject IS NULL")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void expiredAndRevokedLinksAreRefusedAndSayWhoMadeThem() throws IOException {
        final String versionId = manuscriptVersion(false);
        final String fileId = addFile(versionId, pdf("Manus.pdf", 2), null, null, null);

        publish(versionId);

        final String revoked = shareLink(Map.of("name", "Styrgruppen", "versionIds", List.of(versionId)));
        final String expired = shareLink(Map.of("name", "Orkestern", "versionIds", List.of(versionId)));
        final String linkId = graphQl(EDITOR).document("{ shareLinks { id name } }").execute()
                .path("shareLinks[?(@.name == 'Styrgruppen')].id").entityList(String.class).get().getFirst();

        graphQl(EDITOR).document("mutation($id: ID!) { shareLinkRevoke(id: $id) { status } }").variable("id", linkId).execute()
                .path("shareLinkRevoke.status").entity(String.class).isEqualTo("REVOKED");
        jdbcClient.sql("UPDATE share_link SET expires_at = '2020-01-01' WHERE name = 'Orkestern'").update();

        final ResponseEntity<byte[]> gone = get("/api/share/" + revoked + "/files/" + fileId, null, null);

        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(new String(gone.getBody())).contains("SHARE_LINK_REVOKED").contains("Erik");
        assertThat(new String(get("/api/share/" + expired + "/files/" + fileId, null, null).getBody())).contains("SHARE_LINK_EXPIRED");
        graphQl(null)
                .document("query($token: String!) { sharedView(token: $token) { status createdByName versions { id } } }")
                .variable("token", expired)
                .execute()
                .path("sharedView.status").entity(String.class).isEqualTo("EXPIRED")
                .path("sharedView.createdByName").entity(String.class).isEqualTo("Erik Test")
                .path("sharedView.versions").entityList(Object.class).hasSize(0);
    }

    @Test
    void searchFindsTextInAPdfWithItsPage() throws IOException {
        final String versionId = manuscriptVersion(false);

        addFile(versionId, pdf("Manus.pdf", 3), null, null, null);
        publish(versionId);
        assertThat(fileProcessor.processPending()).isEqualTo(1);

        graphQl(VIEWER)
                .document("query($q: String!) { search(q: $q) { textHits { page snippet file { pages hasText hasPreview } } } }")
                .variable("q", SampleFiles.SEARCH_WORD)
                .execute()
                .path("search.textHits[0].page").entity(Integer.class).isEqualTo(2)
                .path("search.textHits[0].snippet").entity(String.class).satisfies(snippet -> assertThat(snippet).containsIgnoringCase(SampleFiles.SEARCH_WORD))
                .path("search.textHits[0].file.pages").entity(Integer.class).isEqualTo(3)
                .path("search.textHits[0].file.hasPreview").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void aFileOfARefusedTypeIsRejected() throws IOException {
        final String versionId = manuscriptVersion(false);
        final Path program = Files.write(folder.resolve("Partitur.pdf"), SampleFiles.disallowed());

        expectError(graphQl(EDITOR)
                .document("mutation($input: FileAddInput!) { fileAdd(input: $input) { id } }")
                .variable("input", Map.of("versionId", versionId, "uploadId", upload(EDITOR, program)))
                .execute(), "BAD_REQUEST");
    }

    @Test
    void aNumberWithAnArrangementIsMergedNotDeleted() {
        arrangementVersion(SPEX_ID, "Ouvertyr");

        final List<String> numbers = graphQl(EDITOR)
                .document("mutation($spex: ID!) { numbersPaste(spexId: $spex, text: \"Finalen (Final Countdown)\\nExtranumret\") { id originalSong } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("numbersPaste[0].originalSong").entity(String.class).isEqualTo("Final Countdown")
                .path("numbersPaste[*].id").entityList(String.class).get();
        final String withArrangement = graphQl(EDITOR)
                .document("query($spex: ID!) { spex(id: $spex) { numbers { id title arrangement { id } } } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("spex.numbers[0].title").entity(String.class).isEqualTo("Ouvertyr")
                .path("spex.numbers[0].id").entity(String.class).get();
        final String otherSpexNumber = graphQl(EDITOR)
                .document("mutation($spex: ID!) { numberCreate(spexId: $spex, input: {title: \"Ouvertyr\"}) { id } }")
                .variable("spex", OTHER_SPEX_ID)
                .execute()
                .path("numberCreate.id").entity(String.class).get();

        expectError(graphQl(EDITOR).document("mutation($id: ID!) { numberDelete(id: $id) }").variable("id", withArrangement).execute(), "CONFLICT");
        expectError(graphQl(EDITOR).document("mutation($id: ID!, $into: ID!) { numberMerge(id: $id, intoId: $into) { id } }")
                .variable("id", withArrangement).variable("into", otherSpexNumber).execute(), "BAD_REQUEST");
        graphQl(EDITOR).document("mutation($id: ID!, $into: ID!) { numberMerge(id: $id, intoId: $into) { id arrangement { id } } }")
                .variable("id", withArrangement).variable("into", numbers.getFirst())
                .execute()
                .path("numberMerge.arrangement.id").entity(String.class).satisfies(id -> assertThat(id).isNotBlank());
        graphQl(EDITOR)
                .document("query($spex: ID!) { spex(id: $spex) { numbers { title position } } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("spex.numbers[*].title").entityList(String.class).containsExactly("Finalen", "Extranumret")
                .path("spex.numbers[*].position").entityList(Integer.class).containsExactly(1, 2);
        // A number without an arrangement is deleted
        graphQl(EDITOR).document("mutation($id: ID!) { numberDelete(id: $id) }").variable("id", numbers.get(1)).execute().path("numberDelete").valueIsNull();
        // A revival without numbers of its own shows its original's
        graphQl(VIEWER)
                .document("query($spex: ID!) { spex(id: $spex) { numbersInherited numbers { title } } }")
                .variable("spex", REVIVAL_ID)
                .execute()
                .path("spex.numbersInherited").entity(Boolean.class).isEqualTo(true)
                .path("spex.numbers[*].title").entityList(String.class).containsExactly("Finalen");
    }

    @Test
    void aPartWithTwoInstrumentsMatchesAFilterOnEither() throws IOException {
        final String versionId = arrangementVersion(SPEX_ID, "Ouvertyr");
        final String shared = addFile(versionId, pdf("Trumpet & Trombone.pdf", 1), null, null, null);

        addFile(versionId, pdf("Altsax.pdf", 1), null, null, null);
        publish(versionId);

        for (final String instrument : List.of(TRUMPET, TROMBONE)) {
            graphQl(EDITOR)
                    .document("query($id: ID!, $instrument: ID!) { version(id: $id) { files(parts: {instrumentIds: [$instrument]}) { id instruments { id } } } }")
                    .variable("id", versionId)
                    .variable("instrument", instrument)
                    .execute()
                    .path("version.files[*].id").entityList(String.class).containsExactly(shared)
                    .path("version.files[0].instruments[*].id").entityList(String.class).containsExactly(TRUMPET, TROMBONE);
        }
        graphQl(EDITOR)
                .document("query($spex: ID!) { spex(id: $spex) { arrangements(parts: {instrumentIds: [\"" + ALTO_SAX + "\"]}) { id } } }")
                .variable("spex", SPEX_ID)
                .execute()
                .path("spex.arrangements").entityList(Object.class).hasSize(1);
    }

    @Test
    void partsAreFilteredByTransposition() throws IOException {
        final String versionId = arrangementVersion(SPEX_ID, "Ouvertyr");
        final String flat = addFile(versionId, pdf("Trumpet in Bb.pdf", 1), null, null, null);
        final String concert = addFile(versionId, pdf("Trumpet i C.pdf", 1), FileRole.PART.toString(), List.of(TRUMPET), "C");

        publish(versionId);

        graphQl(EDITOR)
                .document("query($id: ID!) { version(id: $id) { bb: files(parts: {transpositions: [B_FLAT]}) { id } c: files(parts: {transpositions: [C], instrumentIds: [\"" + TRUMPET + "\"]}) { id } } }")
                .variable("id", versionId)
                .execute()
                .path("version.bb[*].id").entityList(String.class).containsExactly(flat)
                .path("version.c[*].id").entityList(String.class).containsExactly(concert);

        final ResponseEntity<byte[]> zip = get("/api/spex/" + SPEX_ID + "/parts/zip?transpositions=B_FLAT", EDITOR, null);

        assertThat(zip.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(zip.getHeaders().getContentType()).hasToString("application/zip");
    }

    @Test
    void anInstrumentShareLinkOpensPartsAddedAfterItWasMade() throws IOException {
        final String versionId = arrangementVersion(SPEX_ID, "Ouvertyr");

        addFile(versionId, pdf("Trumpet 1.pdf", 1), null, null, null);
        addFile(versionId, pdf("Trombon.pdf", 1), null, null, null);
        publish(versionId);

        final String token = shareLink(Map.of("name", "Ali Baba 2028, brass", "spexId", SPEX_ID, "instrumentIds", List.of(TRUMPET)));
        final String later = addFile(versionId, pdf("Trumpet 2.pdf", 1), null, null, null);

        graphQl(null)
                .document("query($token: String!) { sharedView(token: $token) { versions { files { id title } } } }")
                .variable("token", token)
                .execute()
                .path("sharedView.versions[0].files[*].title").entityList(String.class).containsExactly("Trumpet 1", "Trumpet 2");
        assertThat(get("/api/share/" + token + "/files/" + later, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/share/" + token + "/parts/zip", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void versionsAreFilteredByConcertKeyWhateverTheSpelling() {
        final String versionId = arrangementVersion(SPEX_ID, "Ouvertyr");

        graphQl(EDITOR)
                .document("mutation($id: ID!) { versionUpdate(id: $id, input: {label: \"Original\", key: {tonic: C_SHARP, mode: MAJOR}, modulations: [{tonic: D, mode: MAJOR}]}) { key { tonic mode } modulations { tonic } } }")
                .variable("id", versionId)
                .execute()
                .path("versionUpdate.key.tonic").entity(String.class).isEqualTo("C_SHARP")
                .path("versionUpdate.modulations[0].tonic").entity(String.class).isEqualTo("D");
        publish(versionId);
        graphQl(VIEWER)
                .document("{ search(q: \"\", key: {tonic: D_FLAT, mode: MAJOR}) { versions { id } } }")
                .execute()
                .path("search.versions[*].id").entityList(String.class).containsExactly(versionId);
        graphQl(VIEWER)
                .document("{ search(q: \"\", key: {tonic: D_FLAT, mode: MINOR}) { versions { id } } }")
                .execute()
                .path("search.versions").entityList(Object.class).hasSize(0);
    }

    @Test
    void usersCannotEdit() {
        expectError(graphQl(VIEWER)
                .document("mutation($spex: ID!) { manuscriptCreate(input: {spexId: $spex}) { id } }")
                .variable("spex", SPEX_ID)
                .execute(), "FORBIDDEN");
        expectError(graphQl(NO_ACCESS).document("{ spexList { id } }").execute(), "FORBIDDEN");
        expectError(graphQl(EDITOR)
                .document("mutation($spex: ID!) { manuscriptCreate(input: {spexId: $spex}) { id } }")
                .variable("spex", REVIVAL_ID)
                .execute(), "BAD_REQUEST");
    }
}
