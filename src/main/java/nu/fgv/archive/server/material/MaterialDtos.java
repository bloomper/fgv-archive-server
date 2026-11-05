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

package nu.fgv.archive.server.material;

import nu.fgv.archive.server.file.FileDtos;
import nu.fgv.archive.server.file.FileDtos.InstrumentDto;
import nu.fgv.archive.server.user.Caller;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the GraphQL API returns and takes for manuscripts, arrangements and their versions, and who sees them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class MaterialDtos {

    private MaterialDtos() {
    }

    /**
     * Whether the caller sees the version: editors every version, others published ones outside the trash.
     */
    public static boolean sees(final Caller caller, final MaterialVersion version) {
        return caller.isEditor() || version.isListed();
    }

    /**
     * Whether the caller may open the version's files: editors always, others when it is listed and not restricted.
     */
    public static boolean canOpen(final Caller caller, final MaterialVersion version) {
        return caller.isEditor() || (version.isListed() && !version.isRestricted());
    }

    /**
     * Whether the caller sees the manuscript: editors every one, others one with a listed version.
     */
    public static boolean sees(final Caller caller, final Manuscript manuscript) {
        return caller.isEditor() || (manuscript.getTrashedAt() == null && manuscript.getVersions().stream().anyMatch(MaterialVersion::isListed));
    }

    public static boolean sees(final Caller caller, final Arrangement arrangement) {
        return caller.isEditor() || (arrangement.getTrashedAt() == null && arrangement.getVersions().stream().anyMatch(MaterialVersion::isListed));
    }

    public static ManuscriptDto toDto(final Manuscript manuscript) {
        return new ManuscriptDto(manuscript.getUuid(), manuscript.getSpexId(), manuscript.getTitle(), manuscript.getAuthors(),
                manuscript.getDescription(), manuscript.getTrashedAt() != null, manuscript.getCreatedAt());
    }

    public static ArrangementDto toDto(final Arrangement arrangement) {
        return new ArrangementDto(arrangement.getUuid(), arrangement.getNumberId(), arrangement.getDescription(),
                arrangement.getTrashedAt() != null, arrangement.getCreatedAt());
    }

    public static VersionDto toDto(final MaterialVersion version, final Caller caller) {
        return new VersionDto(version.getUuid(), version.getKind(), version.parentUuid(),
                version.getManuscript() == null ? null : version.getManuscript().getUuid(),
                version.getArrangement() == null ? null : version.getArrangement().getUuid(),
                version.getLabel(), version.getUsedInSpexId(), version.getDate() == null ? null : version.getDate().toString(),
                version.getNotes(), version.getArranger(), version.getKey(), List.copyOf(version.getModulations()),
                version.getTempo(), version.getDuration(),
                instrumentation(version), version.isRecommended(), version.isRestricted(), version.isPublished(),
                version.getPublishedAt(), version.isInTrash(), canOpen(caller, version), version.getCreatedAt(),
                version.getLastModifiedAt());
    }

    public static List<InstrumentationDto> instrumentation(final MaterialVersion version) {
        return version.getInstrumentation().stream()
                .map(entry -> new InstrumentationDto(FileDtos.toDto(entry.getInstrument()), entry.getCount()))
                .toList();
    }

    public record ManuscriptDto(UUID id, UUID spexId, String title, @Nullable String authors,
                                @Nullable String description, boolean trashed, Instant createdAt) {
    }

    public record ArrangementDto(UUID id, UUID numberId, @Nullable String description, boolean trashed,
                                 Instant createdAt) {
    }

    public record VersionDto(UUID id, MaterialKind kind, UUID parentId, @Nullable UUID manuscriptId,
                             @Nullable UUID arrangementId, String label, @Nullable UUID usedInSpexId,
                             @Nullable String date, @Nullable String notes, @Nullable String arranger,
                             @Nullable MusicKey key, List<MusicKey> modulations, @Nullable String tempo,
                             @Nullable String duration, List<InstrumentationDto> instrumentation, boolean recommended, boolean restricted,
                             boolean published, @Nullable Instant publishedAt, boolean trashed, boolean canOpen,
                             Instant createdAt, @Nullable Instant lastModifiedAt) {
    }

    public record InstrumentationDto(InstrumentDto instrument, int count) {
    }

    public record ManuscriptInput(@Nullable UUID spexId, @Nullable String title, @Nullable String authors,
                                  @Nullable String description) {
    }

    public record ArrangementInput(@Nullable UUID numberId, @Nullable String description) {
    }

    public record VersionInput(@Nullable String label, @Nullable UUID usedInSpexId, @Nullable String date,
                               @Nullable String notes, @Nullable String arranger, @Nullable MusicKey key,
                               @Nullable List<MusicKey> modulations, @Nullable String tempo, @Nullable String duration,
                               @Nullable List<InstrumentationInput> instrumentation, @Nullable Boolean restricted) {
    }

    public record InstrumentationInput(UUID instrumentId, int count) {
    }
}
