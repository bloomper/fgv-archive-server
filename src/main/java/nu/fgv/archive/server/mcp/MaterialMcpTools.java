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

package nu.fgv.archive.server.mcp;

import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.FileDtos.FileTextDto;
import nu.fgv.archive.server.file.FileDtos.InstrumentDto;
import nu.fgv.archive.server.file.FileDtos.PageTextDto;
import nu.fgv.archive.server.file.FileDtos.Transposition;
import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.file.ProcessingStatus;
import nu.fgv.archive.server.mcp.McpToolSupport.FileRef;
import nu.fgv.archive.server.mcp.McpToolSupport.ListResult;
import nu.fgv.archive.server.mcp.McpToolSupport.MaterialRef;
import nu.fgv.archive.server.mcp.McpToolSupport.NumberRef;
import nu.fgv.archive.server.mcp.McpToolSupport.SpexRef;
import nu.fgv.archive.server.mcp.McpToolSupport.VersionRef;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MaterialDtos.VersionDto;
import nu.fgv.archive.server.material.MaterialKind;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.material.MusicKey;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The spex in the archive, their manuscripts and arrangements, versions, files' metadata and files' extracted text over
 * MCP. Nothing is changed, shared or downloaded: files are described, never returned, and there are no links to them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MaterialMcpTools {

    /**
     * Pages of text per call.
     */
    static final int MAX_PAGES = 10;
    /**
     * Characters of text per call.
     */
    static final int MAX_CHARS = 20_000;
    static final int MAX_SPEX = 200;

    private final SpexService spexService;
    private final MaterialService materialService;
    private final NumberService numberService;
    private final FileService fileService;
    private final McpToolSupport support;

    public MaterialMcpTools(final SpexService spexService, final MaterialService materialService, final NumberService numberService,
                            final FileService fileService, final McpToolSupport support) {
        this.spexService = spexService;
        this.materialService = materialService;
        this.numberService = numberService;
        this.fileService = fileService;
        this.support = support;
    }

    private static FileInfo toMcp(final FileDto file) {
        return new FileInfo(file.id(), file.title(), file.comment(), file.name(), file.extension(), file.contentType(), (long) file.size(),
                file.pages(), file.role().nameSv(), file.instruments().stream().map(InstrumentDto::nameSv).toList(), file.desk(),
                file.transposition(), file.processing(), file.hasText(), file.canOpen(), file.createdAt());
    }

    @McpTool(name = "list_spex",
            description = "List the spex the archive knows, from its copy of Spexipedia: originals newest first, each with its revivals and how many "
                    + "manuscripts and arrangements you see for it (an original and its revivals share their material). By default only spex with "
                    + "material.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public ListResult<SpexSummary> listSpex(
            @McpToolParam(required = false, description = "Part of a title or a year, e.g. Cleopatra") final @Nullable String query,
            @McpToolParam(required = false, description = "Only spex of this year, original or revival") final @Nullable Integer year,
            @McpToolParam(required = false, description = "Also spex without any material you see") final @Nullable Boolean includeWithoutMaterial) {
        return support.call(() -> {
            final List<SpexDto> spex = spexService.list(new SpexService.SpexFilter(null, year, query == null || query.isBlank() ? null : query.strip(), null), null);
            final List<UUID> ids = spex.stream().map(SpexDto::id).toList();
            final Map<UUID, MaterialService.MaterialCount> counts = materialService.counts(ids);
            final Map<UUID, List<SpexDto>> revivals = spexService.revivals(ids);

            return new ListResult<>(spex.stream()
                    .filter(dto -> Boolean.TRUE.equals(includeWithoutMaterial)
                            || counts.get(dto.id()).manuscripts() + counts.get(dto.id()).arrangements() > 0)
                    .limit(MAX_SPEX)
                    .map(dto -> new SpexSummary(dto.id(), dto.title(), dto.year(), dto.categoryName(),
                            counts.get(dto.id()).manuscripts(), counts.get(dto.id()).arrangements(),
                            revivals.getOrDefault(dto.id(), List.of()).stream().map(McpToolSupport::spexRef).toList()))
                    .toList());
        });
    }

    @McpTool(name = "list_spex_material",
            description = "List a spex' manuscripts and arrangements that you see; a revival's include its original's. Arrangements are by number "
                    + "(song), in the order of the show. Each has how many versions you see and its recommended version. get_material gives the "
                    + "versions and files.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public SpexMaterial listSpexMaterial(
            @McpToolParam(description = "The spex, by id from list_spex") final UUID spexId,
            @McpToolParam(required = false, description = "MANUSCRIPT or ARRANGEMENT; both when left out") final @Nullable MaterialKind kind) {
        return support.call(() -> {
            final SpexDto spex = spexService.get(spexId);

            if (spex == null) {
                throw new ResourceNotFoundException("error.spex.notFound", spexId);
            }

            final List<ManuscriptDto> manuscripts = kind == MaterialKind.ARRANGEMENT ? List.of()
                    : materialService.manuscriptsOf(List.of(spexId)).getOrDefault(spexId, List.of());
            final List<ArrangementDto> arrangements = kind == MaterialKind.MANUSCRIPT ? List.of()
                    : materialService.arrangementsOfSpex(spexId, null, PartFilter.NONE);
            final Map<UUID, List<VersionDto>> versions = materialService.versionsOf(Stream.concat(manuscripts.stream().map(ManuscriptDto::id),
                    arrangements.stream().map(ArrangementDto::id)).toList(), null, PartFilter.NONE);
            final Map<UUID, NumberDto> numbers = numberService.byIds(arrangements.stream().map(ArrangementDto::numberId).toList());

            return new SpexMaterial(McpToolSupport.spexRef(spex),
                    spex.parentId() == null ? null : McpToolSupport.spexRefOrNull(spexService.byIds(List.of(spex.parentId())).get(spex.parentId())),
                    manuscripts.stream().map(manuscript -> summary(manuscript.id(), MaterialKind.MANUSCRIPT, manuscript.title(), manuscript.authors(),
                            manuscript.description(), null, versions.get(manuscript.id()))).toList(),
                    arrangements.stream().map(arrangement -> {
                        final NumberDto number = numbers.get(arrangement.numberId());

                        return summary(arrangement.id(), MaterialKind.ARRANGEMENT, number == null ? null : number.title(), null,
                                arrangement.description(), number == null ? null : McpToolSupport.numberRef(number), versions.get(arrangement.id()));
                    }).toList());
        });
    }

    private static MaterialSummary summary(final UUID id, final MaterialKind kind, final @Nullable String title, final @Nullable String authors,
                                           final @Nullable String description, final @Nullable NumberRef number,
                                           final @Nullable List<VersionDto> versions) {
        final List<VersionDto> seen = versions == null ? List.of() : versions;

        return new MaterialSummary(id, kind, title, authors, description, number, seen.size(),
                seen.stream().filter(VersionDto::recommended).findFirst().map(McpToolSupport::versionRef).orElse(null));
    }

    @McpTool(name = "get_material",
            description = "Get a manuscript or an arrangement with its versions that you see, recommended first, and each version's files: name, type, "
                    + "page count, role (e.g. Manus, Partitur, Stämma), instruments, whether its text is extracted and whether you may read it. "
                    + "Never the files themselves.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Material getMaterial(
            @McpToolParam(description = "The manuscript or arrangement, by id") final UUID materialId) {
        return support.call(() -> {
            final ManuscriptDto manuscript = materialService.manuscript(materialId);
            final ArrangementDto arrangement = manuscript == null ? materialService.arrangement(materialId) : null;

            if (manuscript == null && arrangement == null) {
                throw new ResourceNotFoundException("error.notFound", materialId);
            }

            final MaterialRef ref = Objects.requireNonNull(support.materials(manuscript == null ? List.of() : List.of(materialId),
                    arrangement == null ? List.of() : List.of(materialId)).get(materialId));
            final List<VersionDto> versions = materialService.versionsOf(List.of(materialId), null, PartFilter.NONE).getOrDefault(materialId, List.of());
            final Map<UUID, List<FileDto>> files = fileService.filesOf(versions.stream().map(VersionDto::id).toList(), PartFilter.NONE);
            final Map<UUID, SpexDto> usedIn = spexService.byIds(versions.stream().map(VersionDto::usedInSpexId).filter(Objects::nonNull).distinct().toList());

            return new Material(materialId, ref.kind(), ref.title(),
                    manuscript == null ? null : manuscript.authors(),
                    manuscript == null ? Objects.requireNonNull(arrangement).description() : manuscript.description(),
                    manuscript == null ? Objects.requireNonNull(arrangement).trashed() : manuscript.trashed(),
                    ref.spex(), ref.number(),
                    versions.stream().map(version -> new Version(version.id(), version.label(), version.date(), version.notes(), version.arranger(),
                            version.key(), version.modulations(), version.tempo(), version.duration(),
                            version.usedInSpexId() == null ? null : McpToolSupport.spexRefOrNull(usedIn.get(version.usedInSpexId())),
                            version.recommended(), version.restricted(), version.published(), version.publishedAt(), version.canOpen(),
                            version.createdAt(), version.lastModifiedAt(),
                            files.getOrDefault(version.id(), List.of()).stream().map(MaterialMcpTools::toMcp).toList())).toList());
        });
    }

    @McpTool(name = "get_file_text",
            description = "Read the text extracted from a file's pages, for a file you may read (a restricted file only for editors). At most "
                    + MAX_PAGES + " pages and " + MAX_CHARS + " characters per call; nextPage says where to go on. A file without pages, e.g. a "
                    + "DOCX, has its text as one page. Scanned pages have no text.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public FileText getFileText(
            @McpToolParam(description = "The file, by id from get_material or search_archive") final UUID fileId,
            @McpToolParam(required = false, description = "The first page, from 1; 1 when left out") final @Nullable Integer fromPage,
            @McpToolParam(required = false, description = "The last page; " + MAX_PAGES + " pages on when left out") final @Nullable Integer toPage) {
        return support.call(() -> {
            final int from = fromPage == null ? 1 : Math.max(1, fromPage);
            final int to = Math.min(from + MAX_PAGES - 1, toPage == null ? Integer.MAX_VALUE : Math.max(from, toPage));
            final FileTextDto text = fileService.text(fileId, from, to);
            final VersionDto version = materialService.version(text.file().versionId());
            final MaterialRef material = version == null ? null : support.materialsOf(List.of(version)).get(McpToolSupport.parentOf(version));
            final List<PageTextDto> pages = new ArrayList<>();
            int left = MAX_CHARS;
            boolean truncated = false;
            Integer last = null;

            for (final PageTextDto page : text.pages()) {
                if (left <= 0) {
                    truncated = true;
                    break;
                }
                if (page.text().length() > left) {
                    pages.add(new PageTextDto(page.page(), page.text().substring(0, left)));
                    truncated = true;
                } else {
                    pages.add(page);
                }
                left -= page.text().length();
                last = page.page();
            }

            final Integer total = text.file().pages();
            final int reached = last == null ? to : last;
            final Integer next = total != null && reached < total ? reached + 1 : null;

            return new FileText(new FileRef(text.file().id(), text.file().title(), text.file().name(), text.file().contentType(), total,
                    text.file().hasText(), text.file().createdAt()),
                    version == null ? null : McpToolSupport.versionRef(version), material, pages, truncated, next);
        });
    }

    public record SpexSummary(UUID id, @Nullable String title, @Nullable Integer year, @Nullable String category, int manuscripts,
                              int arrangements, List<SpexRef> revivals) {
    }

    /**
     * @param original the original, for a revival
     */
    public record SpexMaterial(SpexRef spex, @Nullable SpexRef original, List<MaterialSummary> manuscripts,
                               List<MaterialSummary> arrangements) {
    }

    /**
     * A manuscript, or an arrangement with its number.
     */
    public record MaterialSummary(UUID id, MaterialKind kind, @Nullable String title, @Nullable String authors,
                                  @Nullable String description, @Nullable NumberRef number, int versions,
                                  @Nullable VersionRef recommendedVersion) {
    }

    public record Material(UUID id, MaterialKind kind, @Nullable String title, @Nullable String authors, @Nullable String description,
                           boolean trashed, @Nullable SpexRef spex, @Nullable NumberRef number, List<Version> versions) {
    }

    /**
     * A version: published ones are seen by all users, unpublished ones only by editors; a restricted version's files are
     * read only by editors.
     *
     * @param usedInSpex the spex, e.g. a revival, the version was made for
     */
    public record Version(UUID id, String label, @Nullable String date, @Nullable String notes, @Nullable String arranger,
                          @Nullable MusicKey key, List<MusicKey> modulations, @Nullable String tempo, @Nullable String duration,
                          @Nullable SpexRef usedInSpex, boolean recommended, boolean restricted, boolean published,
                          @Nullable Instant publishedAt, boolean canOpen, Instant createdAt, @Nullable Instant lastModifiedAt,
                          List<FileInfo> files) {
    }

    /**
     * A file's metadata, never its content.
     *
     * @param size    in bytes
     * @param role    e.g. Manus, Partitur or Stämma
     * @param canOpen whether you may read its text
     */
    public record FileInfo(UUID id, String title, @Nullable String comment, String name, String extension, String contentType, long size,
                           @Nullable Integer pages, String role, List<String> instruments, @Nullable String desk,
                           @Nullable Transposition transposition, ProcessingStatus processing, boolean hasText, boolean canOpen,
                           Instant createdAt) {
    }

    /**
     * @param truncated whether the text was cut at the size limit
     * @param nextPage  the page to go on from, when there are more
     */
    public record FileText(FileRef file, @Nullable VersionRef version, @Nullable MaterialRef material, List<PageTextDto> pages,
                           boolean truncated, @Nullable Integer nextPage) {
    }
}
