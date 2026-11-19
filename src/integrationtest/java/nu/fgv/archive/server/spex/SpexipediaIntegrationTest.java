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

import nu.fgv.archive.server.AbstractIntegrationTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The archive and Spexipedia: numbers, characters and instruments from its change feed, merges, what its service
 * account reads with {@code LINKS}, and its notifications.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexipediaIntegrationTest extends AbstractIntegrationTest {

    private static final String SPEXIPEDIA_SERVICE = "fgv-spexipedia-service";
    // The default instrument Trumpet, which a task of that name takes over
    private static final String TRUMPET = "00000000-0000-7000-8000-00000000c00b";

    private String arrangement(final String numberId, final boolean publish) {
        final String versionId = graphQl(EDITOR)
                .document("mutation($number: ID!) { arrangementCreate(input: {numberId: $number}) { versions { id } } }")
                .variable("number", numberId).execute()
                .path("arrangementCreate.versions[0].id").entity(String.class).get();

        if (publish) {
            graphQl(EDITOR).document("mutation($id: ID!) { versionPublish(id: $id, published: true) { id } }").variable("id", versionId).execute();
        }
        return graphQl(EDITOR).document("query($id: ID!) { version(id: $id) { parentId } }").variable("id", versionId).execute()
                .path("version.parentId").entity(String.class).get();
    }

    private static FeedChange change(final String kind, final UUID id, final @Nullable UUID into) {
        return new FeedChange("1", kind, id, into == null ? "UPSERT" : "MERGE", into, null, null, null, null, null, null);
    }

    @Test
    void aRevivalShowsItsOriginalsNumbersAndCharactersAndItsOwn() {
        number(SPEX_ID, "Ouvertyr");
        number(SPEX_ID, "Finalen");
        number(REVIVAL_ID, "Extranumret");

        final UUID caesar = UUID.randomUUID();
        final UUID added = UUID.randomUUID();

        sync.applyAll(List.of(
                new FeedChange("1", "CHARACTER", caesar, "UPSERT", null, null, null, null, null,
                        new FeedChange.Character(caesar, UUID.fromString(SPEX_ID), "Caesar", 1, false), null),
                new FeedChange("1", "CHARACTER", added, "UPSERT", null, null, null, null, null,
                        new FeedChange.Character(added, UUID.fromString(REVIVAL_ID), "Turisten", 2, false), null)));

        graphQl(VIEWER).document("query($id: ID!) { spex(id: $id) { numbersInherited numbers { title act position } characters { name } } }")
                .variable("id", REVIVAL_ID).execute()
                .path("spex.numbersInherited").entity(Boolean.class).isEqualTo(true)
                .path("spex.numbers[*].title").entityList(String.class).containsExactly("Ouvertyr", "Extranumret", "Finalen")
                .path("spex.characters[*].name").entityList(String.class).containsExactly("Caesar", "Turisten");
    }

    @Test
    void aRetiredNumberIsShownOnlyWithItsArrangement() {
        final String kept = number(SPEX_ID, "Ouvertyr");
        final String gone = number(SPEX_ID, "Finalen");

        arrangement(kept, false);
        sync.applyAll(List.of(
                new FeedChange("1", "NUMBER", UUID.fromString(kept), "UPSERT", null, null, null, null, null, null,
                        new FeedChange.Number(UUID.fromString(kept), UUID.fromString(SPEX_ID), 1, 1, "Ouvertyr", "Yesterday", "The Beatles", true)),
                new FeedChange("1", "NUMBER", UUID.fromString(gone), "UPSERT", null, null, null, null, null, null,
                        new FeedChange.Number(UUID.fromString(gone), UUID.fromString(SPEX_ID), 1, 2, "Finalen", null, null, true))));

        graphQl(EDITOR).document("query($id: ID!) { spex(id: $id) { numbers { title retired originalSong } } }")
                .variable("id", SPEX_ID).execute()
                .path("spex.numbers[*].title").entityList(String.class).containsExactly("Ouvertyr")
                .path("spex.numbers[0].retired").entity(Boolean.class).isEqualTo(true)
                .path("spex.numbers[0].originalSong").entity(String.class).isEqualTo("Yesterday (The Beatles)");
    }

    @Test
    void aNumberMergedInSpexipediaTakesItsArrangementAlong() {
        final String from = number(SPEX_ID, "Ouvertyr");
        final String into = number(SPEX_ID, "Uvertyr");
        final String arrangementId = arrangement(from, true);

        sync.applyAll(List.of(change("NUMBER", UUID.fromString(from), UUID.fromString(into))));

        graphQl(EDITOR).document("query($id: ID!) { spex(id: $id) { numbers { id arrangement { id } } } }")
                .variable("id", SPEX_ID).execute()
                .path("spex.numbers[*].id").entityList(String.class).containsExactly(into)
                .path("spex.numbers[0].arrangement.id").entity(String.class).isEqualTo(arrangementId);
    }

    @Test
    void tasksInInstrumentCategoriesAreInstrumentsKeepingTheArchivesIdsByName() {
        final UUID orchestra = UUID.randomUUID();
        final UUID trumpet = UUID.randomUUID();
        final UUID kazoo = UUID.randomUUID();
        final UUID director = UUID.randomUUID();
        final UUID committee = UUID.randomUUID();

        sync.applyAll(List.of(
                new FeedChange("1", "TASK_CATEGORY", orchestra, "UPSERT", null, null, null,
                        new FeedChange.TaskCategory(orchestra, "Orkester", true, false), null, null, null),
                new FeedChange("1", "TASK_CATEGORY", committee, "UPSERT", null, null, null,
                        new FeedChange.TaskCategory(committee, "Kommitté", false, false), null, null, null),
                new FeedChange("1", "TASK", trumpet, "UPSERT", null, null, null, null, new FeedChange.Task(trumpet, "Trumpet", orchestra, false), null, null),
                new FeedChange("1", "TASK", kazoo, "UPSERT", null, null, null, null, new FeedChange.Task(kazoo, "Kazoo", orchestra, false), null, null),
                new FeedChange("1", "TASK", director, "UPSERT", null, null, null, null, new FeedChange.Task(director, "Direktör", committee, false), null, null)));

        final String query = "{ instruments { id nameSv taskId retired } }";

        graphQl(ADMIN).document(query).execute()
                .path("instruments[?(@.nameSv == 'Trumpet')].id").entityList(String.class).containsExactly(TRUMPET)
                .path("instruments[?(@.nameSv == 'Trumpet')].taskId").entityList(String.class).containsExactly(trumpet.toString())
                .path("instruments[?(@.nameSv == 'Kazoo')].taskId").entityList(String.class).containsExactly(kazoo.toString())
                .path("instruments[?(@.nameSv == 'Direktör')]").entityList(Object.class).hasSize(0);

        // An instrument from Spexipedia is renamed and removed there, not here
        expectError(graphQl(ADMIN).document("mutation($id: ID!) { instrumentDelete(id: $id) }").variable("id", TRUMPET).execute(), "CONFLICT");

        // The task leaves the instrument categories: the instrument is retired, not deleted
        sync.applyAll(List.of(new FeedChange("1", "TASK", kazoo, "UPSERT", null, null, null, null,
                new FeedChange.Task(kazoo, "Kazoo", committee, false), null, null)));
        graphQl(ADMIN).document(query).execute()
                .path("instruments[?(@.nameSv == 'Kazoo')].retired").entityList(Boolean.class).containsExactly(true);

        // The archive's own instruments stay as they are
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM instrument WHERE task_id IS NULL AND name_sv = 'Flöjt'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void spexipediaReadsPublishedManuscriptsAndArrangementsOnly() {
        final String published = number(SPEX_ID, "Ouvertyr");
        final String unpublished = number(SPEX_ID, "Finalen");
        final String arrangementId = arrangement(published, true);

        arrangement(unpublished, false);

        final String links = """
                query($spex: [ID!]!, $numbers: [ID!]!) { spexipediaLinks(spexIds: $spex, numberIds: $numbers) {
                    manuscripts { id } arrangements { id title numberId } } }""";
        final Map<String, Object> variables = Map.of("spex", List.of(SPEX_ID), "numbers", List.of(published, unpublished));

        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(links).variables(variables).execute()
                .path("spexipediaLinks.manuscripts").entityList(Object.class).hasSize(0)
                .path("spexipediaLinks.arrangements[*].id").entityList(String.class).containsExactly(arrangementId)
                .path("spexipediaLinks.arrangements[0].title").entity(String.class).isEqualTo("Ouvertyr");
        expectError(graphQl(EDITOR).document(links).variables(variables).execute(), "FORBIDDEN");
    }

    @Test
    void aBookListsNumbersOfAnySpexAndSpexipediaReadsThePublishedOnes() {
        final String overture = number(SPEX_ID, "Ouvertyr");
        final String other = number(OTHER_SPEX_ID, "Sultanens visa");
        // Spex book, a book kind
        final String bookId = graphQl(EDITOR).document("""
                        mutation { manuscriptCreate(input: {title: "Chalmersspex I", kindId: "00000000-0000-7000-8000-00000000e010",
                            volume: 1, publishedYear: "1975"}) { id book volume publishedYear versions { id } } }""")
                .execute()
                .path("manuscriptCreate.book").entity(Boolean.class).isEqualTo(true)
                .path("manuscriptCreate.volume").entity(Integer.class).isEqualTo(1)
                .path("manuscriptCreate.id").entity(String.class).get();

        graphQl(EDITOR).document("""
                        mutation($id: ID!, $a: ID!, $b: ID!) { bookSongsSet(id: $id, songs: [{numberId: $a}, {title: "Gaudeamus"},
                            {numberId: $b, textOnly: true}]) { songs { position number { title } title textOnly } } }""")
                .variables(Map.of("id", bookId, "a", overture, "b", other))
                .execute()
                .path("bookSongsSet.songs[0].number.title").entity(String.class).isEqualTo("Ouvertyr")
                .path("bookSongsSet.songs[1].title").entity(String.class).isEqualTo("Gaudeamus")
                .path("bookSongsSet.songs[2].textOnly").entity(Boolean.class).isEqualTo(true);
        // A manuscript has no songs
        final String manuscript = graphQl(EDITOR).document("mutation($spex: ID!) { manuscriptCreate(input: {spexId: $spex}) { id } }")
                .variable("spex", SPEX_ID).execute().path("manuscriptCreate.id").entity(String.class).get();

        expectError(graphQl(EDITOR).document("mutation($id: ID!) { bookSongsSet(id: $id, songs: [{title: \"x\"}]) { id } }")
                .variable("id", manuscript).execute(), "BAD_REQUEST");

        final String links = """
                query($numbers: [ID!]!) { spexipediaLinks(spexIds: [], numberIds: $numbers) {
                    books { id title volume kindNameSv songs { numberId textOnly } } } }""";

        // Unpublished, the book is not linked to
        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(links).variable("numbers", List.of(other)).execute()
                .path("spexipediaLinks.books").entityList(Object.class).hasSize(0);

        final String versionId = graphQl(EDITOR).document("query($id: ID!) { manuscript(id: $id) { versions { id } } }")
                .variable("id", bookId).execute().path("manuscript.versions[0].id").entity(String.class).get();

        graphQl(EDITOR).document("mutation($id: ID!) { versionPublish(id: $id, published: true) { id } }").variable("id", versionId).execute();
        graphQlWithToken(serviceToken(SPEXIPEDIA_SERVICE)).document(links).variable("numbers", List.of(other)).execute()
                .path("spexipediaLinks.books[0].id").entity(String.class).isEqualTo(bookId)
                .path("spexipediaLinks.books[0].kindNameSv").entity(String.class).isEqualTo("Spexbok")
                .path("spexipediaLinks.books[0].songs[*].numberId").entityList(String.class).containsExactly(other)
                .path("spexipediaLinks.books[0].songs[0].textOnly").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void spexipediaNotifiesWithItsServiceAccountOnly() {
        notify(serviceToken(SPEXIPEDIA_SERVICE)).expectStatus().isAccepted();
        notify(token(ADMIN)).expectStatus().isForbidden();
        notify(null).expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec notify(final @Nullable String token) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + localPort).build()
                .post().uri("/api/sync/notify")
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .exchange();
    }
}
