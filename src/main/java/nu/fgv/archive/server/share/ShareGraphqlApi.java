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

package nu.fgv.archive.server.share;

import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.FileDtos.InstrumentDto;
import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.InstrumentService;
import nu.fgv.archive.server.material.MaterialDtos.VersionDto;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.share.ShareLinkService.CreatedShareLink;
import nu.fgv.archive.server.share.ShareLinkService.FileDownloads;
import nu.fgv.archive.server.share.ShareLinkService.ShareLinkDto;
import nu.fgv.archive.server.share.ShareLinkService.ShareLinkInput;
import nu.fgv.archive.server.share.ShareLinkService.SharedViewDto;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GraphQL for share links: editors manage them, and anyone with a token reads what it shares ({@code sharedView}). A
 * thin adapter: {@link ShareLinkService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ShareGraphqlApi {

    private final ShareLinkService shareLinkService;
    private final MaterialService materialService;
    private final FileService fileService;
    private final InstrumentService instrumentService;
    private final SpexService spexService;

    public ShareGraphqlApi(final ShareLinkService shareLinkService, final MaterialService materialService, final FileService fileService,
                           final InstrumentService instrumentService, final SpexService spexService) {
        this.shareLinkService = shareLinkService;
        this.materialService = materialService;
        this.fileService = fileService;
        this.instrumentService = instrumentService;
        this.spexService = spexService;
    }

    @QueryMapping
    public List<ShareLinkDto> shareLinks(@Argument final @Nullable Boolean all) {
        return shareLinkService.list(Boolean.TRUE.equals(all));
    }

    @QueryMapping
    public @Nullable SharedViewDto sharedView(@Argument final String token) {
        return shareLinkService.view(token);
    }

    @BatchMapping(typeName = "ShareLink", field = "versions")
    public Map<ShareLinkDto, List<VersionDto>> versions(final List<ShareLinkDto> links) {
        final Map<UUID, VersionDto> versions = materialService.versionsByIds(links.stream().flatMap(link -> link.versionIds().stream()).distinct().toList());
        final Map<ShareLinkDto, List<VersionDto>> result = new LinkedHashMap<>();

        links.forEach(link -> result.put(link, link.versionIds().stream().map(versions::get).filter(Objects::nonNull).toList()));
        return result;
    }

    @BatchMapping(typeName = "ShareLink", field = "files")
    public Map<ShareLinkDto, List<FileDto>> files(final List<ShareLinkDto> links) {
        final Map<UUID, FileDto> files = fileService.byIds(links.stream().flatMap(link -> link.fileIds().stream()).distinct().toList(), true);
        final Map<ShareLinkDto, List<FileDto>> result = new LinkedHashMap<>();

        links.forEach(link -> result.put(link, link.fileIds().stream().map(files::get).filter(Objects::nonNull).toList()));
        return result;
    }

    @BatchMapping(typeName = "ShareLink", field = "spex")
    public Map<ShareLinkDto, @Nullable SpexDto> spex(final List<ShareLinkDto> links) {
        final Map<UUID, SpexDto> spex = spexService.byIds(links.stream().map(ShareLinkDto::spexId).filter(Objects::nonNull).distinct().toList());
        final Map<ShareLinkDto, @Nullable SpexDto> result = new LinkedHashMap<>();

        links.forEach(link -> result.put(link, link.spexId() == null ? null : spex.get(link.spexId())));
        return result;
    }

    @BatchMapping(typeName = "ShareLink", field = "instruments")
    public Map<ShareLinkDto, List<InstrumentDto>> instruments(final List<ShareLinkDto> links) {
        final Map<UUID, InstrumentDto> instruments = instrumentService.byIds(links.stream().flatMap(link -> link.instrumentIds().stream()).distinct().toList());
        final Map<ShareLinkDto, List<InstrumentDto>> result = new LinkedHashMap<>();

        links.forEach(link -> result.put(link, link.instrumentIds().stream().map(instruments::get).filter(Objects::nonNull).toList()));
        return result;
    }

    @BatchMapping(typeName = "ShareLink", field = "downloads")
    public Map<ShareLinkDto, List<FileDownloads>> downloads(final List<ShareLinkDto> links) {
        final Map<UUID, List<FileDownloads>> downloads = shareLinkService.downloads(links.stream().map(ShareLinkDto::id).toList());
        final Map<ShareLinkDto, List<FileDownloads>> result = new LinkedHashMap<>();

        links.forEach(link -> result.put(link, downloads.getOrDefault(link.id(), List.of())));
        return result;
    }

    @BatchMapping(typeName = "ShareLink", field = "downloadCount")
    public Map<ShareLinkDto, Integer> downloadCount(final List<ShareLinkDto> links) {
        final Map<UUID, List<FileDownloads>> downloads = shareLinkService.downloads(links.stream().map(ShareLinkDto::id).toList());
        final Map<ShareLinkDto, Integer> result = new LinkedHashMap<>();

        links.forEach(link -> result.put(link, downloads.getOrDefault(link.id(), List.of()).stream().mapToInt(FileDownloads::count).sum()));
        return result;
    }

    @MutationMapping
    public CreatedShareLink shareLinkCreate(@Argument final ShareLinkInput input) {
        return shareLinkService.create(input);
    }

    @MutationMapping
    public ShareLinkDto shareLinkExtend(@Argument final UUID id, @Argument final int months) {
        return shareLinkService.extend(id, months);
    }

    @MutationMapping
    public ShareLinkDto shareLinkRevoke(@Argument final UUID id) {
        return shareLinkService.revoke(id);
    }
}
