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

import nu.fgv.archive.server.file.FileDtos;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The records the archive's import API accepts (spec 5.6). Kinds, file roles and instruments are given by name or alias;
 * spex and numbers by their Spexipedia ids; documents and arrangements by the source keys of earlier records.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ImportRecords {

    private ImportRecords() {
    }

    public record FileRef(String sha256, @Nullable String name) {
    }

    public record Batch(@Nullable Boolean createMissingKinds,
                        @Nullable List<ImportDocument> documents,
                        @Nullable List<ImportVersion> documentVersions,
                        @Nullable List<ImportArrangement> arrangements,
                        @Nullable List<ImportVersion> arrangementVersions) {
    }

    /**
     * A document of a kind (a manuscript, a programme, a synopsis …) of an original spex, or of FGV's own. One of a
     * book kind may have a volume, a year and songs (spec 4.7), which replace the book's songs
     * when given.
     */
    public record ImportDocument(String sourceKey, @Nullable UUID spexId, @Nullable String kind, @Nullable String title,
                                 @Nullable String authors, @Nullable String description, @Nullable Integer volume,
                                 @Nullable String publishedYear, @Nullable List<ImportBookSong> songs) {
    }

    public record ImportBookSong(@Nullable UUID numberId, @Nullable String title, @Nullable Boolean textOnly) {
    }

    public record ImportArrangement(String sourceKey, UUID numberId, @Nullable String description) {
    }

    /**
     * A version of a document or an arrangement (parentSourceKey), with its files. Files already on the version (by
     * content) are not added again; files left out are kept.
     */
    public record ImportVersion(String sourceKey, String parentSourceKey, String label, @Nullable UUID usedInSpexId,
                                @Nullable String date, @Nullable String notes, @Nullable String arranger,
                                @Nullable String tempo, @Nullable String duration,
                                @Nullable List<ImportInstrumentation> instrumentation, @Nullable Boolean recommended,
                                @Nullable Boolean restricted, @Nullable List<ImportFile> files) {
    }

    public record ImportInstrumentation(String instrument, @Nullable Integer count) {
    }

    /**
     * A file of a version: a file role and instruments by name (guessed from the file name when left out), the desk
     * or voice, and the pitch it is written in.
     */
    public record ImportFile(FileRef file, @Nullable String role, @Nullable List<String> instruments, @Nullable String desk,
                             FileDtos.@Nullable Transposition transposition, @Nullable String title) {
    }
}
