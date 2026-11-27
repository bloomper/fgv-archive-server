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

import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.Instrument;
import nu.fgv.archive.server.material.Arrangement;
import nu.fgv.archive.server.material.ArrangementRepository;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MusicKey;
import nu.fgv.archive.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * What Spexify asks the archive with {@code SPEXIFY}: the arrangements of numbers with their open versions, that is,
 * published, outside the trash and not restricted, and those versions' files, so that Spexify can show the sheet music
 * beside the recordings. Restricted and unpublished versions are never shown to it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexifySheetMusicGraphqlApi {

    private static final int MAX_IDS = 500;

    /**
     * The recommended version first, then the newest, as the archive lists them.
     */
    private static final Comparator<MaterialVersion> VERSION_ORDER = Comparator
            .comparing((MaterialVersion version) -> !version.isRecommended())
            .thenComparing(MaterialVersion::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MaterialVersion::getId, Comparator.reverseOrder());

    private final ArrangementRepository arrangementRepository;
    private final CallerService callerService;

    public SpexifySheetMusicGraphqlApi(final ArrangementRepository arrangementRepository, final CallerService callerService) {
        this.arrangementRepository = arrangementRepository;
        this.callerService = callerService;
    }

    private static SheetMusicVersion toDto(final MaterialVersion version) {
        final MusicKey key = version.getKey();

        return new SheetMusicVersion(version.getUuid(), version.getLabel(), version.getArranger(), version.isRecommended(),
                key == null ? null : key.tonic().name(), key == null ? null : key.mode().name(), version.getTempo(),
                version.getFiles().stream()
                        .sorted(Comparator.comparingInt(ArchiveFile::getPosition).thenComparing(ArchiveFile::getId))
                        .map(SpexifySheetMusicGraphqlApi::toDto)
                        .toList());
    }

    private static SheetMusicFile toDto(final ArchiveFile file) {
        return new SheetMusicFile(file.getUuid(), file.getTitle(), file.getOriginalName(), file.getExtension(), file.getContentType(),
                file.getSize(), file.getChecksum(), file.getPages(), file.getRole().getNameSv(), file.getRole().getNameEn(),
                file.getInstruments().stream().map(Instrument::getNameSv).toList(),
                file.getInstruments().stream().map(Instrument::getNameEn).toList(), file.getDesk());
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public List<SheetMusic> spexifySheetMusic(@Argument final List<UUID> numberIds) {
        if (!callerService.current().isSpexifyService()) {
            throw new AccessDeniedException("SPEXIFY required");
        }

        final List<UUID> numbers = numberIds.subList(0, Math.min(numberIds.size(), MAX_IDS));

        return numbers.isEmpty() ? List.of() : arrangementRepository.findByNumberIdIn(numbers).stream()
                .filter(arrangement -> arrangement.getTrashedAt() == null)
                .map(SpexifySheetMusicGraphqlApi::toDto)
                .filter(sheetMusic -> !sheetMusic.versions().isEmpty())
                .toList();
    }

    private static SheetMusic toDto(final Arrangement arrangement) {
        return new SheetMusic(arrangement.getUuid(), arrangement.getNumberId(), arrangement.getVersions().stream()
                .filter(MaterialDtos::openToServices)
                .sorted(VERSION_ORDER)
                .map(SpexifySheetMusicGraphqlApi::toDto)
                .toList());
    }

    public record SheetMusic(UUID arrangementId, UUID numberId, List<SheetMusicVersion> versions) {
    }

    public record SheetMusicVersion(UUID id, String label, @Nullable String arranger, boolean recommended,
                                    @Nullable String keyTonic, @Nullable String keyMode, @Nullable String tempo,
                                    List<SheetMusicFile> files) {
    }

    public record SheetMusicFile(UUID id, String title, String name, String extension, String contentType, double size,
                                 String checksum, @Nullable Integer pages, String roleSv, String roleEn,
                                 List<String> instrumentsSv,
                                 List<String> instrumentsEn, @Nullable String desk) {
    }
}
