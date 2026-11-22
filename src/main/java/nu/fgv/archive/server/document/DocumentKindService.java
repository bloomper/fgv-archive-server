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

package nu.fgv.archive.server.document;

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.util.StoredText;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ConflictException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Document kinds (spec 4.1): every user reads them, admins keep them. A kind in use is retired or merged, never
 * deleted. The import finds kinds by name or alias.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class DocumentKindService {

    static final int MAX_NAME = 100;

    private final DocumentKindRepository repository;
    private final JdbcClient jdbc;
    private final CallerService callerService;
    private final AuditService auditService;

    public DocumentKindService(final DocumentKindRepository repository, final JdbcClient jdbc, final CallerService callerService,
                               final AuditService auditService) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<DocumentKind> kinds(final boolean includeRetired) {
        return repository.findAllByOrderByPositionAscIdAsc().stream().filter(kind -> includeRetired || !kind.isRetired()).toList();
    }

    @Transactional(readOnly = true)
    public Map<Long, DocumentKind> byKeys(final java.util.Collection<Long> keys) {
        return repository.findAllById(keys).stream().collect(Collectors.toMap(DocumentKind::getId, Function.identity()));
    }

    /**
     * A kind for a document: the given one (a retired one only when the document already has it), or the first.
     */
    @Transactional(readOnly = true)
    public DocumentKind require(final @Nullable UUID id, final @Nullable DocumentKind current) {
        if (id == null) {
            return current != null ? current : kinds(false).stream().findFirst().orElseThrow();
        }

        final DocumentKind kind = repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.documentKind.notFound", id));

        if (kind.isRetired() && (current == null || !current.getId().equals(kind.getId()))) {
            throw new BadRequestException("error.documentKind.retired", kind.getNameSv());
        }
        return kind;
    }

    /**
     * The kind whose name or one of whose aliases is the text, ignoring case, accents and punctuation.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentKind> match(final @Nullable String text) {
        final String wanted = normalize(text);

        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return kinds(true).stream().filter(kind -> names(kind).contains(wanted))
                .min(java.util.Comparator.comparing(DocumentKind::isRetired));
    }

    private static Set<String> names(final DocumentKind kind) {
        final Set<String> names = new HashSet<>();

        names.add(normalize(kind.getNameSv()));
        names.add(normalize(kind.getNameEn()));
        kind.aliasList().forEach(alias -> names.add(normalize(alias)));
        return names;
    }

    public static String normalize(final @Nullable String text) {
        if (text == null) {
            return "";
        }

        final String lower = text.strip().toLowerCase(Locale.ROOT).replace("å", "\u0001").replace("ä", "\u0002").replace("ö", "\u0003");

        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}\\p{N}\u0001\u0002\u0003]+", " ").strip()
                .replace("\u0001", "å").replace("\u0002", "ä").replace("\u0003", "ö");
    }

    private static String name(final @Nullable String value) {
        if (!StringUtils.hasText(value) || value.strip().length() > MAX_NAME) {
            throw new BadRequestException("error.name.invalid", MAX_NAME);
        }
        return value.strip();
    }

    private static String aliases(final @Nullable List<String> aliases) {
        final String joined = aliases == null ? "" : aliases.stream().filter(StringUtils::hasText).map(String::strip).distinct()
                .collect(Collectors.joining("\n"));

        if (joined.length() > 2000) {
            throw new BadRequestException("error.name.invalid", 2000);
        }
        return joined;
    }

    @Transactional
    public DocumentKind save(final @Nullable UUID id, final DocumentKindInput input) {
        callerService.current().requireAdmin();

        final DocumentKind kind = id == null ? new DocumentKind()
                : repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.documentKind.notFound", id));

        kind.setNameSv(name(input.nameSv()));
        kind.setNameEn(name(input.nameEn()));
        kind.setAliases(aliases(input.aliases()));
        if (input.book() != null) {
            kind.setBook(input.book());
        }
        if (id == null) {
            kind.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(DocumentKind::getPosition).max().orElse(0) + 1);
        }
        repository.save(kind);
        auditService.record(id == null ? "CREATE" : "UPDATE", "DOCUMENT_KIND", kind.getUuid(),
                StoredText.of(id == null ? "audit.documentKind.create" : "audit.documentKind.update",
                        StoredText.of("audit.name", kind.getNameSv(), kind.getNameEn())));
        return kind;
    }

    /**
     * IMPORT. A new kind with the name in both languages, for an import that may create the kinds it meets.
     */
    @Transactional
    public DocumentKind createForImport(final String name) {
        callerService.current().requireImport();

        final DocumentKind kind = new DocumentKind();

        kind.setNameSv(name(name));
        kind.setNameEn(kind.getNameSv());
        kind.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(DocumentKind::getPosition).max().orElse(0) + 1);
        repository.save(kind);
        auditService.record("CREATE", "DOCUMENT_KIND", kind.getUuid(),
                StoredText.of("audit.documentKind.createForImport", StoredText.of("audit.name", kind.getNameSv(), kind.getNameEn())));
        return kind;
    }

    @Transactional
    public List<DocumentKind> reorder(final List<UUID> ids) {
        callerService.current().requireAdmin();

        final List<DocumentKind> kinds = repository.findAll();
        final Map<UUID, DocumentKind> byId = kinds.stream().collect(Collectors.toMap(DocumentKind::getUuid, Function.identity()));

        if (ids.size() != kinds.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.reorder.invalid");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "DOCUMENT_KIND", null, StoredText.of("audit.documentKind.reorder"));
        return ids.stream().map(byId::get).toList();
    }

    @Transactional
    public DocumentKind retire(final UUID id, final boolean retired) {
        callerService.current().requireAdmin();

        final DocumentKind kind = repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.documentKind.notFound", id));

        if (retired && kinds(false).stream().allMatch(other -> other.getId().equals(kind.getId()))) {
            throw new BadRequestException("error.documentKind.last");
        }
        kind.setRetired(retired);
        auditService.record("UPDATE", "DOCUMENT_KIND", id,
                StoredText.of(retired ? "audit.documentKind.retire" : "audit.documentKind.unretire",
                        StoredText.of("audit.name", kind.getNameSv(), kind.getNameEn())));
        return kind;
    }

    /**
     * Moves the documents of a kind to another, keeps the merged kind's names as aliases there, and deletes it.
     */
    @Transactional
    public DocumentKind merge(final UUID id, final UUID intoId) {
        callerService.current().requireAdmin();

        final DocumentKind from = repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.documentKind.notFound", id));
        final DocumentKind into = repository.findByUuid(intoId).orElseThrow(() -> new ResourceNotFoundException("error.documentKind.notFound", intoId));

        if (from.getId().equals(into.getId())) {
            throw new ConflictException("error.documentKind.merge");
        }
        jdbc.sql("UPDATE manuscript SET kind_id = :to WHERE kind_id = :from").param("to", into.getId()).param("from", from.getId()).update();

        final List<String> aliases = new ArrayList<>(into.aliasList());

        aliases.add(from.getNameSv());
        aliases.add(from.getNameEn());
        aliases.addAll(from.aliasList());
        into.setAliases(aliases(aliases.stream().filter(Objects::nonNull).toList()));
        repository.delete(from);
        auditService.record("MERGE", "DOCUMENT_KIND", intoId,
                StoredText.of("audit.documentKind.merge",
                        StoredText.of("audit.name", from.getNameSv(), from.getNameEn()),
                        StoredText.of("audit.name", into.getNameSv(), into.getNameEn())));
        return into;
    }

    /**
     * book: whether documents of the kind are books with a song list; left out, unchanged (a new kind is none).
     */
    public record DocumentKindInput(String nameSv, String nameEn, @Nullable List<String> aliases, @Nullable Boolean book) {

        public DocumentKindInput(final String nameSv, final String nameEn, final @Nullable List<String> aliases) {
            this(nameSv, nameEn, aliases, null);
        }
    }
}
