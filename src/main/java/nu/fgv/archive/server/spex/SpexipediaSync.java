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

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.archive.server.config.ArchiveProperties;
import nu.fgv.archive.server.file.InstrumentService;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.number.SpexNumber;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.util.StoredText;
import nu.fgv.archive.server.util.TextException;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the archive's copies of Spexipedia's base data (Spexarkivet's spec, 4.4 and 4.5): spex, numbers, characters,
 * and the tasks of instrument categories as instruments. Pulls Spexipedia's change feed every five minutes and when
 * Spexipedia says there is something new ({@code POST /api/sync/notify}), reads pages until it has caught up and applies
 * each page in one transaction with the cursor. A merge moves material, arrangements and instruments to the record
 * merged into.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class SpexipediaSync {

    private static final int MAX_ERROR = 1000;
    private static final int MAX_PAGES = 1000;
    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_SKIPPED = 50;
    /**
     * The tables and columns that refer to a spex by its id.
     */
    private static final List<String> SPEX_REFERENCES = List.of("manuscript.spex_id", "number.spex_id", "spex_character.spex_id",
            "spex.parent_id", "material_version.used_in_spex_id", "share_link.spex_id");

    private final SpexipediaClient client;
    private final ArchiveProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final SpexRepository spexRepository;
    private final SpexNumberRepository numberRepository;
    private final SpexCharacterRepository characterRepository;
    private final NumberService numberService;
    private final InstrumentService instrumentService;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    @SuppressWarnings("java:S107")
    public SpexipediaSync(final SpexipediaClient client, final ArchiveProperties properties, final JdbcClient jdbc,
                          final PlatformTransactionManager transactionManager, final SpexRepository spexRepository,
                          final SpexNumberRepository numberRepository, final SpexCharacterRepository characterRepository,
                          final NumberService numberService, final InstrumentService instrumentService, final Clock clock) {
        this.client = client;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.spexRepository = spexRepository;
        this.numberRepository = numberRepository;
        this.characterRepository = characterRepository;
        this.numberService = numberService;
        this.instrumentService = instrumentService;
        this.clock = clock;
    }

    @Scheduled(cron = "${archive.jobs.spexipedia-sync.cron-expression}")
    @SchedulerLock(name = "spexipedia-sync", lockAtMostFor = "PT10M")
    void scheduled() {
        if (properties.spexipedia().syncEnabled()) {
            pull(false);
        }
    }

    /**
     * Pulls in the background, when Spexipedia says there is something new.
     */
    public void pullSoon() {
        if (properties.spexipedia().syncEnabled()) {
            Executors.newVirtualThreadPerTaskExecutor().execute(() -> pull(false));
        }
    }

    /**
     * Pulls until caught up; a pull that finds another running returns at once.
     *
     * @param full from the start of the feed, applying everything again
     * @return how many changes were applied, or empty when another pull was running
     * @throws SpexipediaClient.SpexipediaCallException when Spexipedia cannot be reached (recorded as the last error)
     */
    public Optional<Integer> pull(final boolean full) {
        if (!running.tryLock()) {
            return Optional.empty();
        }
        try {
            return Optional.of(pullLocked(full));
        } finally {
            running.unlock();
        }
    }

    private int pullLocked(final boolean full) {
        String cursor = full ? null : jdbc.sql("SELECT cursor_value FROM spexipedia_sync WHERE id = 1").query(String.class).optional().orElse(null);
        int applied = 0;

        jdbc.sql("UPDATE spexipedia_sync SET last_pulled_at = :at WHERE id = 1").param("at", now()).update();
        if (full) {
            // Everything is applied again, so what was skipped gets a new chance
            jdbc.sql("DELETE FROM spexipedia_sync_skipped").update();
        }
        try {
            for (int pages = 0; pages < MAX_PAGES; pages++) {
                final FeedChange.Page page = client.changes(cursor);
                final String next = page.cursor() == null ? cursor : page.cursor();

                applyPage(page, next);
                applied += page.changes().size();
                cursor = next;
                if (!page.hasMore()) {
                    break;
                }
            }
        } catch (final RuntimeException e) {
            final StoredText error = TextException.text(e);

            log.warn("Sync from Spexipedia failed after {} changes: {}", applied, e.toString());
            jdbc.sql("UPDATE spexipedia_sync SET last_error = :error WHERE id = 1")
                    .param("error", error.encode(MAX_ERROR)).update();
            throw e;
        }
        jdbc.sql("UPDATE spexipedia_sync SET last_success = :at, last_error = NULL WHERE id = 1").param("at", now()).update();
        if (applied > 0) {
            log.info("Applied {} changes from Spexipedia", applied);
        }
        return applied;
    }

    /**
     * Applies a page in one transaction with the cursor. When that fails its changes are applied one at a time, so that
     * the ones before the failing one are kept. A change that keeps failing is tried {@value #MAX_ATTEMPTS} times, at
     * as many pulls; then it is recorded as skipped, for admins to see, and the feed goes on without it.
     */
    void applyPage(final FeedChange.Page page, final @Nullable String next) {
        try {
            transactionTemplate.executeWithoutResult(_ -> {
                page.changes().forEach(this::apply);
                moveCursor(next, page.changes().size());
            });
            return;
        } catch (final RuntimeException e) {
            log.warn("A page of {} changes from Spexipedia could not be applied, applying them one at a time: {}",
                    page.changes().size(), message(e));
        }
        for (final FeedChange change : page.changes()) {
            try {
                transactionTemplate.executeWithoutResult(_ -> {
                    apply(change);
                    moveCursor(change.sequence(), 1);
                });
            } catch (final RuntimeException e) {
                if (attempt(change) < MAX_ATTEMPTS) {
                    throw e;
                }
                skip(change, message(e));
            }
        }
        transactionTemplate.executeWithoutResult(_ -> moveCursor(next, 0));
    }

    /**
     * Moves the cursor; whatever was failing before it no longer is.
     */
    private void moveCursor(final @Nullable String cursor, final int applied) {
        jdbc.sql("""
                        UPDATE spexipedia_sync SET cursor_value = :cursor, applied = applied + :n, failing_sequence = NULL, failing_attempts = 0
                        WHERE id = 1""")
                .param("cursor", cursor).param("n", applied).update();
    }

    /**
     * Counts one more failed attempt at the change and answers how many there have been.
     */
    private int attempt(final FeedChange change) {
        jdbc.sql("""
                        UPDATE spexipedia_sync
                        SET failing_attempts = CASE WHEN failing_sequence = :sequence THEN failing_attempts + 1 ELSE 1 END,
                            failing_sequence = :sequence
                        WHERE id = 1""")
                .param("sequence", change.sequence()).update();
        return jdbc.sql("SELECT failing_attempts FROM spexipedia_sync WHERE id = 1").query(Integer.class).single();
    }

    private void skip(final FeedChange change, final StoredText error) {
        log.error("Skipped change {} from Spexipedia ({} {} {}) after {} attempts: {}", change.sequence(), change.change(),
                change.kind(), change.id(), MAX_ATTEMPTS, error);
        transactionTemplate.executeWithoutResult(_ -> {
            jdbc.sql("""
                            INSERT INTO spexipedia_sync_skipped (sequence, kind, record_id, change_type, error, skipped_at)
                            VALUES (:sequence, :kind, :id, :change, :error, :at)""")
                    .param("sequence", change.sequence()).param("kind", change.kind()).param("id", change.id().toString())
                    .param("change", change.change()).param("error", error.encode(MAX_ERROR))
                    .param("at", now()).update();
            moveCursor(change.sequence(), 0);
        });
    }

    private static StoredText message(final RuntimeException e) {
        return TextException.text(e);
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * Applies changes in one transaction without moving the cursor, for tests and the sample data.
     */
    public void applyAll(final List<FeedChange> changes) {
        transactionTemplate.executeWithoutResult(_ -> changes.forEach(this::apply));
    }

    void apply(final FeedChange change) {
        if ("MERGE".equals(change.change())) {
            merge(change.kind(), change.id(), Objects.requireNonNull(change.mergedIntoId()));
            return;
        }
        switch (change.kind()) {
            case "SPEX_CATEGORY" -> upsert(Objects.requireNonNull(change.spexCategory()));
            case "SPEX" -> upsert(Objects.requireNonNull(change.spex()));
            case "TASK_CATEGORY" -> upsert(Objects.requireNonNull(change.taskCategory()));
            case "TASK" -> upsert(Objects.requireNonNull(change.task()));
            case "CHARACTER" -> upsert(Objects.requireNonNull(change.character()));
            case "NUMBER" -> upsert(Objects.requireNonNull(change.number()));
            default -> {
                // Kinds the archive does not keep
            }
        }
    }

    private void upsert(final FeedChange.SpexCategory category) {
        jdbc.sql("INSERT INTO spexipedia_category (id, name) VALUES (:id, :name) ON DUPLICATE KEY UPDATE name = :name")
                .param("id", category.id().toString()).param("name", category.name()).update();
        spexRepository.flush();
        jdbc.sql("UPDATE spex SET category_name = :name WHERE category_id = :id")
                .param("name", category.name()).param("id", category.id().toString()).update();
    }

    private void upsert(final FeedChange.Spex record) {
        final Spex spex = spexRepository.findByUuid(record.id()).orElseGet(() -> {
            final Spex created = new Spex();

            created.setUuid(record.id());
            return created;
        });

        spex.setParentId(record.parentId());
        spex.setTitle(record.title());
        spex.setYear(year(record.year()));
        spex.setRevival(record.parentId() != null);
        spex.setCategoryId(record.categoryId());
        spex.setCategoryName(record.categoryId() == null ? null : jdbc.sql("SELECT name FROM spexipedia_category WHERE id = :id")
                .param("id", record.categoryId().toString()).query(String.class).optional().orElse(spex.getCategoryName()));
        spex.setRetired(record.retired());
        spex.setRefreshedAt(now());
        spexRepository.save(spex);
    }

    private static @Nullable Integer year(final String year) {
        try {
            return Integer.valueOf(year);
        } catch (final NumberFormatException _) {
            return null;
        }
    }

    private void upsert(final FeedChange.TaskCategory category) {
        jdbc.sql("""
                        INSERT INTO spexipedia_task_category (id, name, instruments, retired) VALUES (:id, :name, :instruments, :retired)
                        ON DUPLICATE KEY UPDATE name = :name, instruments = :instruments, retired = :retired""")
                .param("id", category.id().toString()).param("name", category.name())
                .param("instruments", category.instruments()).param("retired", category.retired()).update();
        // Its tasks become instruments, or stop being ones
        jdbc.sql("SELECT id, name, retired FROM spexipedia_task WHERE category_id = :id").param("id", category.id().toString())
                .query((rs, _) -> new FeedChange.Task(UUID.fromString(rs.getString("id")), rs.getString("name"), category.id(), rs.getBoolean("retired")))
                .list()
                .forEach(task -> instrumentService.syncTask(task.id(), task.name(), category.instruments() && !category.retired() && !task.retired()));
    }

    private void upsert(final FeedChange.Task task) {
        jdbc.sql("""
                        INSERT INTO spexipedia_task (id, name, category_id, retired) VALUES (:id, :name, :category, :retired)
                        ON DUPLICATE KEY UPDATE name = :name, category_id = :category, retired = :retired""")
                .param("id", task.id().toString()).param("name", task.name())
                .param("category", task.categoryId() == null ? null : task.categoryId().toString()).param("retired", task.retired()).update();

        final boolean instrument = !task.retired() && task.categoryId() != null && jdbc.sql(
                        "SELECT COUNT(*) FROM spexipedia_task_category WHERE id = :id AND instruments = TRUE AND retired = FALSE")
                .param("id", task.categoryId().toString()).query(Long.class).single() > 0;

        instrumentService.syncTask(task.id(), task.name(), instrument);
    }

    private void upsert(final FeedChange.Character record) {
        final SpexCharacter character = characterRepository.findByUuid(record.id()).orElseGet(() -> {
            final SpexCharacter created = new SpexCharacter();

            created.setUuid(record.id());
            return created;
        });

        character.setSpexId(record.spexId());
        character.setName(record.name());
        character.setPosition(record.position());
        character.setRetired(record.retired());
        characterRepository.save(character);
    }

    private void upsert(final FeedChange.Number record) {
        final SpexNumber number = numberRepository.findByUuid(record.id()).orElseGet(() -> {
            final SpexNumber created = new SpexNumber();

            created.setUuid(record.id());
            return created;
        });

        number.setSpexId(record.spexId());
        number.setAct(record.act());
        number.setPosition(record.position());
        number.setTitle(record.title());
        number.setOriginalSong(originalSong(record.originalTitle(), record.originalArtist()));
        number.setRetired(record.retired());
        numberRepository.save(number);
    }

    /**
     * The melody as the archive writes it: "Yesterday (The Beatles)".
     */
    static @Nullable String originalSong(final @Nullable String title, final @Nullable String artist) {
        if (StringUtils.hasText(title)) {
            return StringUtils.hasText(artist) ? "%s (%s)".formatted(title.strip(), artist.strip()) : title.strip();
        }
        return StringUtils.hasText(artist) ? artist.strip() : null;
    }

    private void merge(final String kind, final UUID from, final UUID into) {
        spexRepository.flush();
        switch (kind) {
            case "SPEX" -> {
                for (final String reference : SPEX_REFERENCES) {
                    final String[] parts = reference.split("\\.");

                    jdbc.sql("UPDATE %s SET %s = :into WHERE %s = :from".formatted(parts[0], parts[1], parts[1]))
                            .param("into", into.toString()).param("from", from.toString()).update();
                }
                jdbc.sql("DELETE FROM spex WHERE uuid = :from").param("from", from.toString()).update();
            }
            case "SPEX_CATEGORY" -> jdbc.sql("""
                            UPDATE spex SET category_id = :into,
                                category_name = COALESCE((SELECT name FROM spexipedia_category WHERE id = :into), category_name)
                            WHERE category_id = :from""")
                    .param("into", into.toString()).param("from", from.toString()).update();
            case "NUMBER" -> numberService.mergeFromSpexipedia(from, into);
            case "CHARACTER" ->
                    jdbc.sql("DELETE FROM spex_character WHERE uuid = :from").param("from", from.toString()).update();
            case "TASK" -> {
                instrumentService.mergeTasks(from, into);
                jdbc.sql("DELETE FROM spexipedia_task WHERE id = :from").param("from", from.toString()).update();
            }
            case "TASK_CATEGORY" -> {
                jdbc.sql("UPDATE spexipedia_task SET category_id = :into WHERE category_id = :from")
                        .param("into", into.toString()).param("from", from.toString()).update();
                jdbc.sql("DELETE FROM spexipedia_task_category WHERE id = :from").param("from", from.toString()).update();
            }
            default -> {
                // Kinds the archive does not keep
            }
        }
        log.info("Merged {} {} into {}", kind, from, into);
    }

    public Status status() {
        final List<Skipped> skipped = jdbc.sql("""
                        SELECT sequence, kind, record_id, change_type, error, skipped_at FROM spexipedia_sync_skipped
                        ORDER BY id DESC LIMIT :max""")
                .param("max", MAX_SKIPPED)
                .query((rs, _) -> new Skipped(rs.getString("sequence"), rs.getString("kind"), rs.getString("record_id"),
                        rs.getString("change_type"), rs.getString("error"), rs.getTimestamp("skipped_at").toInstant()))
                .list();

        return jdbc.sql("SELECT cursor_value, last_pulled_at, last_success, last_error, applied FROM spexipedia_sync WHERE id = 1")
                .query((rs, _) -> new Status(rs.getString("cursor_value"),
                        rs.getTimestamp("last_pulled_at") == null ? null : rs.getTimestamp("last_pulled_at").toInstant(),
                        rs.getTimestamp("last_success") == null ? null : rs.getTimestamp("last_success").toInstant(),
                        rs.getString("last_error"), rs.getLong("applied"), skipped))
                .single();
    }

    /**
     * Where the copy has got to in the feed, and the latest changes that were skipped.
     */
    public record Status(@Nullable String cursor, @Nullable Instant lastPulledAt, @Nullable Instant lastSuccess,
                         @Nullable String lastError, long applied, List<Skipped> skipped) {
    }

    /**
     * A change that could not be applied and was left out: the copy lacks it until it is made again in Spexipedia.
     */
    public record Skipped(String sequence, String kind, String id, String change, String error, Instant skippedAt) {
    }
}
