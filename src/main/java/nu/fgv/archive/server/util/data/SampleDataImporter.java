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

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.archive.server.access.AccessRequest;
import nu.fgv.archive.server.access.AccessRequestRepository;
import nu.fgv.archive.server.audit.AuditEntry;
import nu.fgv.archive.server.audit.AuditEntryRepository;
import nu.fgv.archive.server.config.ArchiveProperties;
import nu.fgv.archive.server.config.KeycloakConfig;
import nu.fgv.archive.server.download.DownloadLog;
import nu.fgv.archive.server.download.DownloadLogRepository;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.file.FileGuesser;
import nu.fgv.archive.server.file.FileRole;
import nu.fgv.archive.server.file.FileRoleRepository;
import nu.fgv.archive.server.file.FileStorage;
import nu.fgv.archive.server.file.FileTypes;
import nu.fgv.archive.server.file.Instrument;
import nu.fgv.archive.server.file.InstrumentRepository;
import nu.fgv.archive.server.file.Pitch;
import nu.fgv.archive.server.material.Arrangement;
import nu.fgv.archive.server.material.ArrangementRepository;
import nu.fgv.archive.server.material.InstrumentationEntry;
import nu.fgv.archive.server.material.Manuscript;
import nu.fgv.archive.server.material.ManuscriptRepository;
import nu.fgv.archive.server.material.MaterialKind;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MaterialVersionRepository;
import nu.fgv.archive.server.material.Mode;
import nu.fgv.archive.server.material.MusicKey;
import nu.fgv.archive.server.material.Tonic;
import nu.fgv.archive.server.number.SpexNumber;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.share.ShareLink;
import nu.fgv.archive.server.share.ShareLinkRepository;
import nu.fgv.archive.server.share.ShareLinkService;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.spex.SpexregisterClient;
import nu.fgv.archive.server.user.AppUser;
import nu.fgv.archive.server.user.AppUserRepository;
import nu.fgv.archive.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Replaces everything with sample data at every start while {@code archive.data.import-sample-data} is set (product
 * spec 5.9): the database, the storage folders and the sample users' roles in Keycloak. Built on Spexregister's sample
 * spex, so Spexregister must run with its sample data too. The files are queued for the worker, which reads their text
 * and renders their previews.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleDataImporter {

    /**
     * The fixed tokens of the sample share links, listed in the getting started guide.
     */
    public static final String VALID_TOKEN = "exempel-giltig-delningslank-0000000000000001";
    public static final String EXPIRED_TOKEN = "exempel-utgangen-delningslank-00000000000001";
    public static final String REVOKED_TOKEN = "exempel-aterkallad-delningslank-000000000001";
    // Spexregister's sample users, which its sample data import creates; they get roles here
    static final String ADMIN = "admin@fgv.nu";
    static final String EDITOR = "editor@fgv.nu";
    static final String USER = "user@fgv.nu";
    // Shared by Spexify's, Spexflix's and Spexarkivet's sample data: an account without access
    static final String NO_ACCESS = "no-access@fgv.nu";
    static final String TRUMPET = "00000000-0000-7000-8000-00000000c00b";
    static final String ALTO_SAX = "00000000-0000-7000-8000-00000000c008";
    static final String TENOR_SAX = "00000000-0000-7000-8000-00000000c009";
    static final String HORN = "00000000-0000-7000-8000-00000000c00c";
    static final String TROMBONE = "00000000-0000-7000-8000-00000000c00d";
    static final String PIANO = "00000000-0000-7000-8000-00000000c010";
    static final String BASS = "00000000-0000-7000-8000-00000000c013";
    static final String DRUMS = "00000000-0000-7000-8000-00000000c014";
    static final String VOCALS = "00000000-0000-7000-8000-00000000c01e";
    private static final int SPEXREGISTER_ATTEMPTS = 30;
    private static final Duration SPEXREGISTER_RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final List<String> TABLES = List.of("audit_entry", "download_log", "access_request_file", "access_request",
            "share_link_file", "share_link_version", "share_link", "file_page", "archive_file", "instrumentation_entry",
            "material_version", "arrangement", "manuscript", "number", "upload_session", "app_user");
    private static final List<String[]> NUMBERS = List.of(
            new String[]{"Ouvertyr", null}, new String[]{"Öppningskören", "Hello, Dolly!"},
            new String[]{"Kärleksduetten", "Something stupid (Frank & Nancy Sinatra)"}, new String[]{"Hovets klagan", "Yesterday (The Beatles)"},
            new String[]{"Rövarvisan", "Rövarvisan (Kardemummastaden)"}, new String[]{"Ödets nummer", "Dancing Queen (ABBA)"},
            new String[]{"Drottningens hämnd", "Der Hölle Rache (Mozart)"}, new String[]{"Finalen", "Final Countdown (Europe)"});
    private static final List<MusicKey> KEYS = List.of(new MusicKey(Tonic.C, Mode.MAJOR), new MusicKey(Tonic.G, Mode.MAJOR),
            new MusicKey(Tonic.F, Mode.MAJOR), new MusicKey(Tonic.D, Mode.MINOR), new MusicKey(Tonic.B_FLAT, Mode.MAJOR),
            new MusicKey(Tonic.F_SHARP, Mode.MINOR));
    private static final List<String> LYRICISTS = List.of("Åsa Åkesson", "Bertil Berg", "Cecilia Ceder", "David Dahl");
    /** The band: an instrument's default id and how many play it. */
    private static final List<Object[]> BAND = List.of(
            new Object[]{TRUMPET, 2}, new Object[]{ALTO_SAX, 1}, new Object[]{TENOR_SAX, 1}, new Object[]{HORN, 1},
            new Object[]{TROMBONE, 1}, new Object[]{PIANO, 1}, new Object[]{BASS, 1}, new Object[]{DRUMS, 1}, new Object[]{VOCALS, 1});
    /**
     * The band's parts: title, desk, the pitch when not the first instrument's default, and the instruments. A trumpet
     * part in both B flat and C, and a part for alto or tenor saxophone.
     */
    private static final List<Object[]> PARTS = List.of(
            new Object[]{"Trumpet 1–2", "1–2", null, List.of(TRUMPET)}, new Object[]{"Trumpet 1–2 i C", "1–2", Pitch.C, List.of(TRUMPET)},
            new Object[]{"Altsax / Tenorsax", null, null, List.of(ALTO_SAX, TENOR_SAX)}, new Object[]{"Valthorn i F", null, null, List.of(HORN)},
            new Object[]{"Trombon", null, null, List.of(TROMBONE)}, new Object[]{"Piano", null, null, List.of(PIANO)},
            new Object[]{"Elbas", null, null, List.of(BASS)}, new Object[]{"Trummor", null, null, List.of(DRUMS)},
            new Object[]{"Sång", null, null, List.of(VOCALS)});

    private final ArchiveProperties properties;
    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SpexregisterClient spexregisterClient;
    private final SpexService spexService;
    private final Keycloak keycloak;
    private final FileStorage storage;
    private final FileTypes types;
    private final AppUserRepository userRepository;
    private final SpexNumberRepository numberRepository;
    private final ManuscriptRepository manuscriptRepository;
    private final ArrangementRepository arrangementRepository;
    private final MaterialVersionRepository versionRepository;
    private final ArchiveFileRepository fileRepository;
    private final FileRoleRepository roleRepository;
    private final InstrumentRepository instrumentRepository;
    private final ShareLinkRepository shareLinkRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final DownloadLogRepository downloadLogRepository;
    private final AuditEntryRepository auditRepository;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public SampleDataImporter(final ArchiveProperties properties, final Environment environment, final JdbcTemplate jdbcTemplate,
                              final TransactionTemplate transactionTemplate, final SpexregisterClient spexregisterClient,
                              final SpexService spexService, @Qualifier(KeycloakConfig.ADMIN) final Keycloak keycloak,
                              final FileStorage storage, final FileTypes types, final AppUserRepository userRepository,
                              final SpexNumberRepository numberRepository, final ManuscriptRepository manuscriptRepository,
                              final ArrangementRepository arrangementRepository, final MaterialVersionRepository versionRepository,
                              final ArchiveFileRepository fileRepository, final FileRoleRepository roleRepository,
                              final InstrumentRepository instrumentRepository, final ShareLinkRepository shareLinkRepository,
                              final AccessRequestRepository accessRequestRepository, final DownloadLogRepository downloadLogRepository,
                              final AuditEntryRepository auditRepository, final Clock clock) {
        this.properties = properties;
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.spexregisterClient = spexregisterClient;
        this.spexService = spexService;
        this.keycloak = keycloak;
        this.storage = storage;
        this.types = types;
        this.userRepository = userRepository;
        this.numberRepository = numberRepository;
        this.manuscriptRepository = manuscriptRepository;
        this.arrangementRepository = arrangementRepository;
        this.versionRepository = versionRepository;
        this.fileRepository = fileRepository;
        this.roleRepository = roleRepository;
        this.instrumentRepository = instrumentRepository;
        this.shareLinkRepository = shareLinkRepository;
        this.accessRequestRepository = accessRequestRepository;
        this.downloadLogRepository = downloadLogRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    private static UUID id(final String name) {
        return UUID.nameUUIDFromBytes(("archive-sample-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static UserRepresentation spexregisterUser(final RealmResource realm, final String email) {
        return realm.users().searchByEmail(email, true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Spexregister's sample user " + email
                        + " is missing; start Spexregister with its sample data first"));
    }

    private static String sha256(final Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() throws IOException {
        if (!properties.data().importSampleData()) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("production")) {
            throw new IllegalStateException("Refusing to import sample data in the production profile");
        }
        importSampleData();
    }

    public void importSampleData() throws IOException {
        final long started = System.nanoTime();
        final boolean large = "large".equalsIgnoreCase(properties.data().sampleDataSize());

        log.info("Importing {} sample data", large ? "large" : "small");
        // Truncating resets the ids, so sample ids are the same on every run
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (final String table : TABLES) {
                    statement.execute("TRUNCATE TABLE " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        storage.wipe();
        waitForSpexregister();
        spexService.refresh();

        final RealmResource realm = keycloak.realm(properties.keycloak().realm());
        final Map<String, AppUser> users = NotificationSuspender.whileSuspended(realm, () -> createUsers(realm));
        final List<SpexDto> originals = spexService.all().stream()
                .filter(spex -> spex.parentId() == null)
                .sorted(Comparator.comparing((SpexDto spex) -> spex.year() == null ? 0 : spex.year()).reversed())
                .toList();
        // A few spex keep no material at all, for the lists of what is missing
        final int withMaterial = Math.max(1, Math.min(large ? 150 : 10, originals.size() - 2));
        final Path work = Files.createTempDirectory("archive-sample-");

        try {
            final Map<String, Path> shared = sharedFiles(work, large);

            for (int i = 0; i < withMaterial; i++) {
                final int index = i;
                final SpexDto spex = originals.get(i);

                transactionTemplate.executeWithoutResult(_ -> createMaterial(index, spex, large, shared, work));
            }
            transactionTemplate.executeWithoutResult(_ -> createShareLinksAndLogs(users));
            transactionTemplate.executeWithoutResult(_ -> createAudit(users.get(EDITOR)));
        } finally {
            FileSystemUtils.deleteRecursively(work);
        }
        log.info("Imported sample data in {} s", Duration.ofNanos(System.nanoTime() - started).toSeconds());
    }

    private void waitForSpexregister() {
        for (int attempt = 1; ; attempt++) {
            try {
                if (spexregisterClient.allSpex().isEmpty()) {
                    throw new IllegalStateException("Spexregister has no spex; import its sample data first");
                }
                return;
            } catch (final SpexregisterClient.SpexregisterCallException e) {
                if (attempt == SPEXREGISTER_ATTEMPTS) {
                    throw new IllegalStateException("Spexregister is unavailable, so sample data cannot be imported", e);
                }
                log.info("Waiting for Spexregister ({}/{})", attempt, SPEXREGISTER_ATTEMPTS);
                try {
                    Thread.sleep(SPEXREGISTER_RETRY_INTERVAL);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Spexregister", interrupted);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- users

    /**
     * Gives Spexregister's sample users {@code admin@fgv.nu}, {@code editor@fgv.nu} and {@code user@fgv.nu} the roles
     * of the same name on this client, and creates {@code no-access@fgv.nu}, without roles, when it is missing. Refuses
     * without Spexregister's sample users, which keeps the import away from real environments.
     */
    private Map<String, AppUser> createUsers(final RealmResource realm) {
        final ClientResource client = realm.clients().get(realm.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId());
        final String clientUuid = client.toRepresentation().getId();
        final Map<String, AppUser> users = new LinkedHashMap<>();

        users.put(ADMIN, grant(realm, client, clientUuid, spexregisterUser(realm, ADMIN), "USER", "EDITOR", "ADMIN"));
        users.put(EDITOR, grant(realm, client, clientUuid, spexregisterUser(realm, EDITOR), "USER", "EDITOR"));
        users.put(USER, grant(realm, client, clientUuid, spexregisterUser(realm, USER), "USER"));
        users.put(NO_ACCESS, recordUser(noAccessUser(realm)));
        return users;
    }

    private AppUser grant(final RealmResource realm, final ClientResource client, final String clientUuid,
                          final UserRepresentation user, final String... roles) {
        final List<RoleRepresentation> representations = Arrays.stream(roles)
                .map(role -> client.roles().get(role).toRepresentation())
                .toList();

        realm.users().get(user.getId()).roles().clientLevel(clientUuid).add(representations);
        return recordUser(user);
    }

    private AppUser recordUser(final UserRepresentation representation) {
        final Instant now = clock.instant();
        final AppUser user = new AppUser();

        user.setSubject(representation.getId());
        user.setUsername(representation.getUsername());
        user.setDisplayName(representation.getFirstName());
        user.setCreatedAt(now);
        user.setLastSeenAt(now);
        return Objects.requireNonNull(transactionTemplate.execute(_ -> userRepository.save(user)));
    }

    private UserRepresentation noAccessUser(final RealmResource realm) {
        final List<UserRepresentation> existing = realm.users().searchByEmail(NO_ACCESS, true);

        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(properties.data().sampleUserPassword());
        credential.setTemporary(false);
        user.setUsername(NO_ACCESS);
        user.setEmail(NO_ACCESS);
        user.setFirstName("No");
        user.setLastName("Access");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw new IllegalStateException("Could not create user " + NO_ACCESS + " in Keycloak: " + response.getStatus());
            }

            final String path = response.getLocation().getPath();

            return realm.users().get(path.substring(path.lastIndexOf('/') + 1)).toRepresentation();
        }
    }

    // ---------------------------------------------------------------------------------------------------- material

    /**
     * The files that are the same for many versions, generated once: MusicXML, DOCX, MIDI and a scanned page.
     */
    private Map<String, Path> sharedFiles(final Path work, final boolean large) throws IOException {
        final Map<String, Path> files = new LinkedHashMap<>();

        files.put("musicxml", work.resolve("Melodistämma.musicxml"));
        SampleFiles.musicXml(files.get("musicxml"), "Melodistämma");
        files.put("docx", work.resolve("Manus.docx"));
        SampleFiles.docx(files.get("docx"), "Manus");
        files.put("midi", work.resolve("Referens.mid"));
        SampleFiles.midi(files.get("midi"));
        files.put("scan", work.resolve("Skannat original.pdf"));
        SampleFiles.scan(files.get("scan"), "Handskrivet original");
        files.put("score", work.resolve("Partitur.pdf"));
        SampleFiles.music(files.get("score"), "Partitur", "Alla instrument", large ? 1 : 3, 8);
        return files;
    }

    private void createMaterial(final int index, final SpexDto spex, final boolean large, final Map<String, Path> shared,
                                final Path work) {
        try {
            final String title = Objects.requireNonNullElse(spex.title(), "Spexet");
            final List<SpexDto> revivals = spexService.revivals(List.of(spex.id())).get(spex.id());
            final SpexDto revival = revivals.isEmpty() ? null : revivals.getFirst();
            final List<SpexNumber> numbers = createNumbers(spex.id(), index, large ? 4 : 6);

            // A revival with numbers of its own every other time; else it shows its original's
            if (revival != null && index % 2 == 0) {
                createNumbers(revival.id(), index + 1, large ? 3 : 4);
            }
            if (index % 5 != 4) {
                createManuscript(index, spex, revival, title, large, shared, work);
            }
            for (int n = 0; n < numbers.size() - 1; n++) {
                createArrangement(index, n, numbers.get(n), spex, revival, large, shared, work);
            }
            if (index == 5) {
                final Manuscript tour = manuscript(spex, title + " – turnéversion", "manus-tour-" + index);

                tour.setTrashedAt(clock.instant().minus(Duration.ofDays(3)));
                version(MaterialKind.MANUSCRIPT, tour, null, "Turné", spex.id(), "tour-" + index, true, false);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<SpexNumber> createNumbers(final UUID spexId, final int index, final int count) {
        final List<SpexNumber> numbers = new ArrayList<>();

        for (int n = 0; n < count; n++) {
            final String[] sample = NUMBERS.get((index + n) % NUMBERS.size());
            final SpexNumber number = new SpexNumber();

            number.setUuid(id("number-" + spexId + "-" + n));
            number.setSpexId(spexId);
            number.setPosition(n + 1);
            number.setTitle(sample[0]);
            number.setOriginalSong(sample[1]);
            number.setLyricist(LYRICISTS.get((index + n) % LYRICISTS.size()));
            number.setComposer(sample[1] == null ? "Fredrik Falk" : null);
            numbers.add(numberRepository.save(number));
        }
        return numbers;
    }

    private Manuscript manuscript(final SpexDto spex, final String title, final String key) {
        final Manuscript manuscript = new Manuscript();

        manuscript.setUuid(id(key));
        manuscript.setSpexId(spex.id());
        manuscript.setTitle(title);
        manuscript.setAuthors(LYRICISTS.get(title.length() % LYRICISTS.size()) + " och " + LYRICISTS.get((title.length() + 1) % LYRICISTS.size()));
        manuscript.setDescription("Exempeldata: ett genererat manus för " + title + ".");
        return manuscriptRepository.save(manuscript);
    }

    private void createManuscript(final int index, final SpexDto spex, final @Nullable SpexDto revival, final String title,
                                  final boolean large, final Map<String, Path> shared, final Path work) throws IOException {
        final Manuscript manuscript = manuscript(spex, title, "manuscript-" + index);
        final MaterialVersion original = version(MaterialKind.MANUSCRIPT, manuscript, null, "Original " + Objects.requireNonNullElse(spex.year(), 0),
                spex.id(), "manuscript-original-" + index, true, index % 4 == 3);
        final Path pdf = work.resolve("Manus %s.pdf".formatted(title));

        original.setRecommended(true);
        original.setDate(spex.year() == null ? null : LocalDate.of(spex.year(), 2, 1));
        SampleFiles.manuscript(pdf, title, original.getLabel(), large ? 2 : 20 + (index * 13) % 61);
        addFile(original, pdf, "Manus – " + title, FileRole.MANUSCRIPT, List.of(), 1);
        addFile(original, shared.get("docx"), "Manus (Word)", FileRole.SOURCE, List.of(), 2);
        if (index == 1) {
            addFile(original, shared.get("scan"), "Skannat original", FileRole.SCAN, List.of(), 3);
        }
        if (revival != null) {
            final MaterialVersion rewrite = version(MaterialKind.MANUSCRIPT, manuscript, null,
                    "Nyuppsättning " + Objects.requireNonNullElse(revival.year(), 0), revival.id(), "manuscript-revival-" + index, true, false);
            final Path revised = work.resolve("Manus %s %s.pdf".formatted(title, revival.year()));

            rewrite.setNotes("Omskriven för nyuppsättningen: nya repliker i andra akten.");
            SampleFiles.manuscript(revised, title, rewrite.getLabel(), large ? 2 : 20 + (index * 7) % 30);
            addFile(rewrite, revised, "Manus – " + title + " " + revival.year(), FileRole.MANUSCRIPT, List.of(), 1);
        }
        if (index % 3 == 2) {
            final MaterialVersion draft = version(MaterialKind.MANUSCRIPT, manuscript, null, "Utkast", null, "manuscript-draft-" + index, false, false);
            final Path draftPdf = work.resolve("Utkast %s.pdf".formatted(title));

            SampleFiles.manuscript(draftPdf, title, "Utkast", 2);
            addFile(draft, draftPdf, "Utkast", FileRole.MANUSCRIPT, List.of(), 1);
        }
        if (index == 3) {
            final MaterialVersion trashed = version(MaterialKind.MANUSCRIPT, manuscript, null, "Felaktig uppladdning", null, "manuscript-trashed-" + index, true, false);

            trashed.setTrashedAt(clock.instant().minus(Duration.ofDays(1)));
        }
    }

    private void createArrangement(final int index, final int n, final SpexNumber number, final SpexDto spex,
                                   final @Nullable SpexDto revival, final boolean large, final Map<String, Path> shared,
                                   final Path work) throws IOException {
        final Arrangement arrangement = new Arrangement();

        arrangement.setUuid(id("arrangement-" + index + "-" + n));
        arrangement.setNumberId(number.getUuid());
        arrangement.setDescription(n == 0 ? "Exempeldata: arrangemang för spexets husband." : null);
        if (index == 2 && n == 1) {
            arrangement.setTrashedAt(clock.instant().minus(Duration.ofDays(2)));
        }
        arrangementRepository.save(arrangement);

        // Arrangement versions are restricted by default; every third is open
        final MaterialVersion original = version(MaterialKind.ARRANGEMENT, null, arrangement, "Original", spex.id(),
                "arrangement-original-" + index + "-" + n, index % 7 != 6 || n != 0, n % 3 != 0);

        original.setRecommended(true);
        original.setArranger(LYRICISTS.get((index + n + 2) % LYRICISTS.size()));
        original.setKey(KEYS.get((index + n) % KEYS.size()));
        if (n == 1) {
            original.getModulations().add(new MusicKey(Tonic.A_FLAT, Mode.MAJOR));
        }
        original.setTempo("♩ = " + (90 + (index * 7 + n * 11) % 60));
        original.setDuration("%d:%02d".formatted(2 + n % 3, (index * 13 + n * 7) % 60));
        addBand(original, work, number, large ? 1 : 2, shared);
        if (revival != null && n % 2 == 0) {
            final MaterialVersion band = version(MaterialKind.ARRANGEMENT, null, arrangement, "Band " + revival.year(), revival.id(),
                    "arrangement-revival-" + index + "-" + n, n != 2, true);

            band.setArranger("Gustav Gran");
            band.setKey(new MusicKey(Tonic.E_FLAT, Mode.MAJOR));
            band.setNotes("Transponerad en ters ner för nyuppsättningens sångare.");
            addBand(band, work, number, 1, shared);
        }
        if (n == 2) {
            final MaterialVersion transposed = version(MaterialKind.ARRANGEMENT, null, arrangement, "Transponerad till D", spex.id(),
                    "arrangement-transposed-" + index + "-" + n, true, false);

            transposed.setArranger(original.getArranger());
            transposed.setKey(new MusicKey(Tonic.D, Mode.MAJOR));
            transposed.setNotes("Samma arrangemang, en ton lägre för en mörkare röst.");
            addBand(transposed, work, number, 1, shared);
        }
    }

    private void addBand(final MaterialVersion version, final Path work, final SpexNumber number, final int pages,
                         final Map<String, Path> shared) throws IOException {
        int position = 1;

        addFile(version, shared.get("score"), "Partitur", FileRole.SCORE, List.of(), position++);
        for (final Object[] member : BAND) {
            version.getInstrumentation().add(new InstrumentationEntry(instrument((String) member[0]), (Integer) member[1],
                    version.getInstrumentation().size() + 1));
        }
        for (final Object[] part : PARTS) {
            final String name = (String) part[0];
            final List<Instrument> instruments = ((List<?>) part[3]).stream().map(id -> instrument((String) id)).toList();
            final Path pdf = work.resolve("%s %s %s.pdf".formatted(number.getTitle(), version.getUuid(), name.replace('/', '-')));
            final ArchiveFile file;

            SampleFiles.music(pdf, number.getTitle(), name, pages, 6);
            file = addFile(version, pdf, name, FileRole.PART, instruments, position++);
            file.setDesk((String) part[1]);
            if (part[2] != null) {
                file.setTranspositionPitch((Pitch) part[2]);
                file.setTranspositionOctave(0);
            }
        }
        if (number.getPosition() == 1) {
            addFile(version, shared.get("musicxml"), "Melodistämma (MusicXML)", FileRole.SOURCE, List.of(), position++);
            addFile(version, shared.get("midi"), "Referens (MIDI)", FileRole.REFERENCE_AUDIO, List.of(), position);
        }
    }

    private MaterialVersion version(final MaterialKind kind, final @Nullable Manuscript manuscript,
                                    final @Nullable Arrangement arrangement, final String label, final @Nullable UUID usedIn,
                                    final String key, final boolean published, final boolean restricted) {
        final MaterialVersion version = new MaterialVersion();

        version.setUuid(id("version-" + key));
        version.setKind(kind);
        version.setManuscript(manuscript);
        version.setArrangement(arrangement);
        version.setLabel(label);
        version.setUsedInSpexId(usedIn);
        version.setPublished(published);
        version.setPublishedAt(published ? clock.instant().minus(Duration.ofDays(30)) : null);
        version.setRestricted(restricted);
        return versionRepository.save(version);
    }

    private Instrument instrument(final String id) {
        return instrumentRepository.findByUuid(UUID.fromString(id)).orElseThrow();
    }

    private ArchiveFile addFile(final MaterialVersion version, final Path source, final String title, final UUID roleId,
                                final List<Instrument> instruments, final int position) throws IOException {
        final String name = source.getFileName().toString();
        final FileTypes.Detected detected = types.detect(source, name);
        final ArchiveFile file = new ArchiveFile();

        file.setUuid(id("file-" + version.getUuid() + "-" + position));
        file.setVersion(version);
        file.setRole(roleRepository.findByUuid(roleId).orElseThrow());
        file.getInstruments().addAll(instruments);
        if (!instruments.isEmpty()) {
            file.setTranspositionPitch(instruments.getFirst().getDefaultPitch());
            file.setTranspositionOctave(instruments.getFirst().getDefaultOctave());
        }
        file.setTitle(title.isBlank() ? FileGuesser.title(name) : title);
        file.setOriginalName(name);
        file.setExtension(detected.extension());
        file.setContentType(detected.contentType());
        file.setSize(Files.size(source));
        file.setChecksum(sha256(source));
        file.setPosition(position);
        fileRepository.save(file);
        version.getFiles().add(file);

        final Path target = storage.file(version.getUuid(), file);

        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        return file;
    }

    // ------------------------------------------------------------------------------------------- links and logs

    private void createShareLinksAndLogs(final Map<String, AppUser> users) {
        final AppUser editor = users.get(EDITOR);
        final List<MaterialVersion> listed = versionRepository.findAll().stream().filter(MaterialVersion::isListed).toList();
        final MaterialVersion manuscript = listed.stream().filter(version -> version.getKind() == MaterialKind.MANUSCRIPT).findFirst().orElseThrow();
        final List<MaterialVersion> arrangements = listed.stream()
                .filter(version -> version.getKind() == MaterialKind.ARRANGEMENT && version.isRestricted()).limit(3).toList();
        final ShareLink valid = shareLink("Ali Baba 2028, band", VALID_TOKEN, editor, clock.instant().plus(Duration.ofDays(300)), null);

        valid.getVersions().add(manuscript);
        valid.getVersions().addAll(arrangements);

        final ShareLink expired = shareLink("Cleopatra 2025, styrgruppen", EXPIRED_TOKEN, editor, clock.instant().minus(Duration.ofDays(10)), null);

        expired.getVersions().add(manuscript);

        final ShareLink revoked = shareLink("Orkestern 2026", REVOKED_TOKEN, editor, clock.instant().plus(Duration.ofDays(100)),
                clock.instant().minus(Duration.ofDays(5)));

        revoked.getFiles().add(arrangements.getFirst().getFiles().getFirst());

        final ArchiveFile restrictedFile = arrangements.getFirst().getFiles().get(1);

        for (int i = 0; i < 3; i++) {
            downloadLog(restrictedFile, null, valid);
        }
        downloadLog(manuscript.getFiles().getFirst(), null, valid);
        downloadLog(restrictedFile, editor, null);

        final AccessRequest request = new AccessRequest();

        request.setUuid(id("access-request-1"));
        request.setUserSubject(users.get(USER).getSubject());
        request.setUserName(users.get(USER).getDisplayName());
        request.setUserEmail(USER);
        request.setReason("Jag spelar trumpet i nyuppsättningen och behöver stämmorna till repetitionen.");
        request.setCreatedAt(clock.instant().minus(Duration.ofDays(2)));
        request.getFiles().addAll(arrangements.getFirst().getFiles().stream().filter(file -> !file.getInstruments().isEmpty()).limit(2).toList());
        accessRequestRepository.save(request);
    }

    private ShareLink shareLink(final String name, final String token, final AppUser creator, final Instant expiresAt,
                                final @Nullable Instant revokedAt) {
        final ShareLink link = new ShareLink();

        link.setUuid(id("share-link-" + token));
        link.setName(name);
        link.setTokenHash(ShareLinkService.hash(token));
        link.setCreatedBy(creator.getSubject());
        link.setCreatedByName(creator.getDisplayName());
        link.setCreatedAt(clock.instant().minus(Duration.ofDays(60)));
        link.setExpiresAt(expiresAt);
        link.setRevokedAt(revokedAt);
        link.setRevokedByName(revokedAt == null ? null : creator.getDisplayName());
        return shareLinkRepository.save(link);
    }

    private void downloadLog(final ArchiveFile file, final @Nullable AppUser user, final @Nullable ShareLink link) {
        final DownloadLog entry = new DownloadLog();

        entry.setFileId(file.getId());
        entry.setAt(clock.instant().minus(Duration.ofHours(downloadLogRepository.count() + 1)));
        entry.setUserSubject(user == null ? null : user.getSubject());
        entry.setUserName(user == null ? null : user.getDisplayName());
        entry.setShareLinkId(link == null ? null : link.getId());
        downloadLogRepository.save(entry);
        file.setDownloadCount(file.getDownloadCount() + 1);
    }

    private void createAudit(final AppUser editor) {
        final Instant now = clock.instant();
        final List<MaterialVersion> versions = versionRepository.findAll().stream().limit(5).toList();

        for (int i = 0; i < versions.size(); i++) {
            final MaterialVersion version = versions.get(i);
            final AuditEntry entry = new AuditEntry();

            entry.setAt(now.minus(Duration.ofDays(versions.size() - i)));
            entry.setActorSubject(editor.getSubject());
            entry.setActorName(editor.getDisplayName());
            entry.setAction(i % 2 == 0 ? "CREATE" : "PUBLISH");
            entry.setEntityType("VERSION");
            entry.setEntityId(version.getUuid().toString());
            entry.setSummary((i % 2 == 0 ? "Skapade versionen %s" : "Publicerade versionen %s").formatted(version.getLabel()));
            auditRepository.save(entry);
        }

        final AuditEntry share = new AuditEntry();

        share.setAt(now.minus(Duration.ofHours(3)));
        share.setActorSubject(editor.getSubject());
        share.setActorName(editor.getDisplayName());
        share.setAction("SHARE");
        share.setEntityType("SHARE_LINK");
        share.setEntityId(id("share-link-" + VALID_TOKEN).toString());
        share.setSummary("Skapade delningslänken Ali Baba 2028, band");
        auditRepository.save(share);
    }
}
