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

import nu.fgv.archive.server.document.DocumentKind;
import nu.fgv.archive.server.document.DocumentKindService;
import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.Instrument;
import nu.fgv.archive.server.file.InstrumentRepository;
import nu.fgv.archive.server.imports.ImportEngine.Batch;
import nu.fgv.archive.server.imports.ImportEngine.Handler;
import nu.fgv.archive.server.imports.ImportEngine.ItemResult;
import nu.fgv.archive.server.imports.ImportRecords.ImportArrangement;
import nu.fgv.archive.server.imports.ImportRecords.ImportDocument;
import nu.fgv.archive.server.imports.ImportRecords.ImportFile;
import nu.fgv.archive.server.imports.ImportRecords.ImportVersion;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The archive's import API (spec 5.6): documents, arrangements and their versions with files. The records go through
 * the same services as editors' changes; {@link ImportEngine} keeps track of the source keys.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ArchiveImport {

    public static final String DOCUMENT = "DOCUMENT";
    public static final String ARRANGEMENT = "ARRANGEMENT";
    public static final String VERSION = "VERSION";

    private final ImportEngine engine;
    private final ImportFileStore files;
    private final MaterialService materials;
    private final FileService fileService;
    private final DocumentKindService kinds;
    private final InstrumentRepository instruments;

    public ArchiveImport(final ImportEngine engine, final ImportFileStore files, final MaterialService materials,
                         final FileService fileService, final DocumentKindService kinds, final InstrumentRepository instruments) {
        this.engine = engine;
        this.files = files;
        this.materials = materials;
        this.fileService = fileService;
        this.kinds = kinds;
        this.instruments = instruments;
    }

    public List<ItemResult> run(final ImportRecords.Batch input, final boolean dryRun) {
        final int size = sizeOf(input.documents()) + sizeOf(input.documentVersions()) + sizeOf(input.arrangements())
                + sizeOf(input.arrangementVersions());

        if (size > ImportEngine.MAX_RECORDS) {
            throw new BadRequestException("error.import.tooMany", ImportEngine.MAX_RECORDS);
        }

        final Batch batch = new Batch(dryRun, Boolean.TRUE.equals(input.createMissingKinds()));
        final List<ItemResult> results = new ArrayList<>();

        results.addAll(engine.run(documentHandler, input.documents(), batch));
        results.addAll(engine.run(versionHandler, input.documentVersions(), batch));
        results.addAll(engine.run(arrangementHandler, input.arrangements(), batch));
        results.addAll(engine.run(versionHandler, input.arrangementVersions(), batch));
        return results;
    }

    public int forget(final String sourcePrefix) {
        return engine.forget(sourcePrefix, List.of(versionHandler, arrangementHandler, documentHandler));
    }

    private static int sizeOf(final @Nullable List<?> list) {
        return list == null ? 0 : list.size();
    }

    private @Nullable UUID documentKind(final @Nullable String name, final Batch batch) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return kinds.match(name).map(DocumentKind::getUuid).orElseGet(() -> {
            if (batch.createMissingKinds()) {
                batch.warn("Ny dokumentsort: " + name.strip());
                return kinds.createForImport(name.strip()).getUuid();
            }
            throw new BadRequestException("error.kind.unknown", name,
                    kinds.kinds(false).stream().map(DocumentKind::getNameSv).collect(Collectors.joining(", ")));
        });
    }

    // Documents and arrangements ---------------------------------------------------------------------------------------

    private final Handler<ImportDocument> documentHandler = new Handler<>() {

        @Override
        public String kind() {
            return DOCUMENT;
        }

        @Override
        public String sourceKey(final ImportDocument record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportDocument record, final @Nullable UUID existing, final Batch batch) {
            final MaterialDtos.ManuscriptInput input = new MaterialDtos.ManuscriptInput(record.spexId(), record.title(),
                    record.authors(), record.description(), documentKind(record.kind(), batch), record.volume(), record.publishedYear());
            final UUID id = existing == null ? materials.createManuscript(input).id() : materials.updateManuscript(existing, input).id();

            if (record.songs() != null) {
                materials.setBookSongs(id, record.songs().stream()
                        .map(song -> new MaterialDtos.BookSongInput(song.numberId(), song.title(), song.textOnly())).toList());
            }
            return id;
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return materials.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return materials.forget(id);
        }
    };

    private final Handler<ImportArrangement> arrangementHandler = new Handler<>() {

        @Override
        public String kind() {
            return ARRANGEMENT;
        }

        @Override
        public String sourceKey(final ImportArrangement record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportArrangement record, final @Nullable UUID existing, final Batch batch) {
            final MaterialDtos.ArrangementInput input = new MaterialDtos.ArrangementInput(record.numberId(), record.description());
            final UUID current = existing != null ? existing : materials.arrangementOfNumber(record.numberId());

            if (current != null && existing == null) {
                batch.warn("Numret har redan ett arrangemang; versionerna läggs till i det");
            }
            return current == null ? materials.createArrangement(input).id() : materials.updateArrangement(current, input).id();
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return materials.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return materials.forget(id);
        }
    };

    // Versions with files ----------------------------------------------------------------------------------------------

    private final Handler<ImportVersion> versionHandler = new Handler<>() {

        @Override
        public String kind() {
            return VERSION;
        }

        @Override
        public String sourceKey(final ImportVersion record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportVersion record, final @Nullable UUID existing, final Batch batch) {
            final UUID parent = engine.recordOf(DOCUMENT, record.parentSourceKey()) != null ? engine.recordOf(DOCUMENT, record.parentSourceKey())
                    : engine.recordOf(ARRANGEMENT, record.parentSourceKey());

            if (parent == null) {
                if (batch.dryRun() && batch.planned(record.parentSourceKey())) {
                    // The parent is made earlier in this dry run but not kept: check the files and stop there
                    if (record.files() != null) {
                        record.files().forEach(file -> files.require(file.file().sha256()));
                    }
                    return nu.fgv.archive.server.util.Uuids.v7();
                }
                throw new BadRequestException("error.field.invalid", "parentSourceKey");
            }

            final MaterialDtos.VersionInput input = new MaterialDtos.VersionInput(record.label(), record.usedInSpexId(), record.date(),
                    record.notes(), record.arranger(), null, null, record.tempo(), record.duration(), instrumentation(record),
                    record.restricted());
            final UUID reuse = existing != null ? existing : materials.untouchedOriginal(parent);
            final UUID id = reuse != null ? materials.updateVersion(reuse, input).id() : materials.createVersion(parent, input).id();

            if (Boolean.TRUE.equals(record.recommended())) {
                materials.recommendVersion(id, true);
            }
            if (record.files() != null) {
                for (final ImportFile file : record.files()) {
                    final Path path = files.require(file.file().sha256());
                    final String name = file.file().name() == null ? file.file().sha256() : file.file().name();

                    try {
                        fileService.importFile(id, path, name, file.role(), file.instruments(), file.desk(), file.transposition(),
                                file.title(), batch.dryRun());
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
            return id;
        }

        private @Nullable List<MaterialDtos.InstrumentationInput> instrumentation(final ImportVersion record) {
            if (record.instrumentation() == null) {
                return null;
            }

            final List<Instrument> known = instruments.findAllByOrderByPositionAscIdAsc();

            return record.instrumentation().stream()
                    .map(entry -> new MaterialDtos.InstrumentationInput(known.stream()
                            .filter(instrument -> instrument.getNameSv().equalsIgnoreCase(entry.instrument().strip())
                                    || instrument.getNameEn().equalsIgnoreCase(entry.instrument().strip()))
                            .findFirst()
                            .orElseThrow(() -> new BadRequestException("error.kind.unknown", entry.instrument(),
                                    known.stream().map(Instrument::getNameSv).collect(Collectors.joining(", "))))
                            .getUuid(), entry.count() == null ? 1 : entry.count()))
                    .toList();
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return materials.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return materials.forget(id);
        }
    };
}
