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

package nu.fgv.archive.server.file;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the GraphQL API returns and takes for files, file roles and instruments.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class FileDtos {

    private FileDtos() {
    }

    public static FileRoleDto toDto(final FileRole role) {
        return new FileRoleDto(role.getUuid(), role.getNameSv(), role.getNameEn(), role.getPosition());
    }

    public static InstrumentDto toDto(final Instrument instrument) {
        return new InstrumentDto(instrument.getUuid(), instrument.getNameSv(), instrument.getNameEn(), instrument.getGroup(),
                instrument.abbreviationList(), new Transposition(instrument.getDefaultPitch(), instrument.getDefaultOctave()),
                instrument.getPosition());
    }

    /**
     * A file as the caller sees it.
     *
     * @param canOpen       whether the caller may open and download it
     * @param editor        whether the caller is an editor, who sees how often it was downloaded
     */
    public static FileDto toDto(final ArchiveFile file, final boolean canOpen, final boolean editor) {
        return new FileDto(file.getUuid(), file.getVersion().getUuid(), file.getTitle(), file.getComment(), file.getOriginalName(),
                file.getExtension(), file.getContentType(), file.getSize(), file.getChecksum(), file.getPages(), file.getPosition(),
                toDto(file.getRole()), file.getInstruments().stream().map(FileDtos::toDto).toList(), file.getDesk(),
                file.getTranspositionPitch() == null ? null : new Transposition(file.getTranspositionPitch(), file.getTranspositionOctave()),
                file.getProcessingStatus(),
                file.isHasPreview(), file.isHasText(), canOpen, editor ? file.getDownloadCount() : null, file.getCreatedAt());
    }

    public record FileRoleDto(UUID id, String nameSv, String nameEn, int position) {
    }

    public record FileRoleInput(@Nullable String nameSv, @Nullable String nameEn) {
    }

    public record InstrumentDto(UUID id, String nameSv, String nameEn, InstrumentGroup group, List<String> abbreviations,
                                Transposition defaultTransposition, int position) {
    }

    public record InstrumentInput(@Nullable String nameSv, @Nullable String nameEn, @Nullable InstrumentGroup group,
                                  @Nullable List<String> abbreviations, @Nullable Transposition defaultTransposition) {
    }

    public record FileDto(UUID id, UUID versionId, String title, @Nullable String comment, String name, String extension,
                          String contentType, double size, String checksum, @Nullable Integer pages, int position,
                          FileRoleDto role, List<InstrumentDto> instruments, @Nullable String desk,
                          @Nullable Transposition transposition, ProcessingStatus processing,
                          boolean hasPreview, boolean hasText, boolean canOpen, @Nullable Integer downloadCount,
                          Instant createdAt) {
    }

    /**
     * A new file: the role and instruments are guessed from the upload's name unless the role is given.
     */
    public record FileAddInput(UUID versionId, String uploadId, @Nullable String title, @Nullable String comment,
                               @Nullable UUID roleId, @Nullable List<UUID> instrumentIds, @Nullable String desk,
                               @Nullable Transposition transposition) {
    }

    public record FileInput(@Nullable String title, @Nullable String comment, UUID roleId, @Nullable List<UUID> instrumentIds,
                            @Nullable String desk, @Nullable Transposition transposition) {
    }

    public record FileGuessDto(FileRoleDto role, List<InstrumentDto> instruments, @Nullable String desk,
                               @Nullable Transposition transposition, String title) {
    }

    /**
     * The pitch a part is written in, C for concert pitch, and the octave it sounds in relative to it: -1, 0 or 1.
     */
    public record Transposition(Pitch pitch, int octave) {
    }
}
