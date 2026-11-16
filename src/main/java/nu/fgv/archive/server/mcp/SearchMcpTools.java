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

import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.mcp.McpToolSupport.FileRef;
import nu.fgv.archive.server.mcp.McpToolSupport.MaterialRef;
import nu.fgv.archive.server.mcp.McpToolSupport.NumberRef;
import nu.fgv.archive.server.mcp.McpToolSupport.SpexRef;
import nu.fgv.archive.server.mcp.McpToolSupport.VersionRef;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import nu.fgv.archive.server.search.SearchService;
import nu.fgv.archive.server.search.SearchService.SearchResultDto;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Search across the archive over MCP, as the web's search: metadata, and the text of the files the caller may read.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SearchMcpTools {

    private final SearchService searchService;
    private final MaterialService materialService;
    private final SpexService spexService;
    private final McpToolSupport support;

    public SearchMcpTools(final SearchService searchService, final MaterialService materialService, final SpexService spexService,
                          final McpToolSupport support) {
        this.searchService = searchService;
        this.materialService = materialService;
        this.spexService = spexService;
        this.support = support;
    }

    @McpTool(name = "search_archive",
            description = "Search the archive: the text of the manuscripts' and arrangements' files, page by page, and spex, manuscripts (title, authors), "
                    + "numbers (title, original song, credits) and arrangements (arranger, description). Text hits give the file, its version, the "
                    + "manuscript or arrangement, the spex, the page and a short excerpt; they are only in files you may read. Words shorter than "
                    + "three letters are not searched in the text. At most 50 of each.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public SearchResult searchArchive(@McpToolParam(description = "What to search for, e.g. kungamantel or a song's title") final String query) {
        return support.call(() -> {
            final SearchResultDto result = searchService.search(query, PartFilter.NONE, null);
            final Map<UUID, MaterialRef> materials = support.materialsOf(result.textHits().stream().map(SearchService.TextHitDto::version).toList());
            final Map<UUID, MaterialRef> found = support.materials(result.manuscripts().stream().map(ManuscriptDto::id).toList(),
                    result.arrangements().stream().map(ArrangementDto::id).toList());
            final Map<UUID, SpexDto> spex = spexService.byIds(result.numbers().stream().map(NumberDto::spexId).distinct().toList());
            final Map<UUID, ArrangementDto> arrangements = materialService.arrangementsOfNumbers(result.numbers().stream().map(NumberDto::id).toList());

            return new SearchResult(
                    result.textHits().stream().map(hit -> new TextHit(
                            new FileRef(hit.file().id(), hit.file().title(), hit.file().name(), hit.file().contentType(), hit.file().pages(),
                                    hit.file().hasText(), hit.file().createdAt()),
                            McpToolSupport.versionRef(hit.version()),
                            materials.get(McpToolSupport.parentOf(hit.version())),
                            hit.page(), hit.snippet())).toList(),
                    result.spex().stream().map(McpToolSupport::spexRef).toList(),
                    result.manuscripts().stream().map(manuscript -> found.get(manuscript.id())).filter(Objects::nonNull).toList(),
                    result.arrangements().stream().map(arrangement -> found.get(arrangement.id())).filter(Objects::nonNull).toList(),
                    result.numbers().stream().map(number -> new NumberHit(McpToolSupport.numberRef(number), number.lyricist(), number.composer(),
                            McpToolSupport.spexRefOrNull(spex.get(number.spexId())),
                            arrangements.containsKey(number.id()) ? arrangements.get(number.id()).id() : null)).toList());
        });
    }

    /**
     * What a search found.
     */
    public record SearchResult(List<TextHit> textHits, List<SpexRef> spex, List<MaterialRef> manuscripts,
                               List<MaterialRef> arrangements, List<NumberHit> numbers) {
    }

    /**
     * A page of a file whose text matches: the page is null for a file without pages, e.g. a DOCX.
     */
    public record TextHit(FileRef file, VersionRef version, @Nullable MaterialRef material, @Nullable Integer page, String excerpt) {
    }

    /**
     * A number that matches, with its arrangement when there is one you see.
     */
    public record NumberHit(NumberRef number, @Nullable String lyricist, @Nullable String composer, @Nullable SpexRef spex,
                            @Nullable UUID arrangementId) {
    }
}
