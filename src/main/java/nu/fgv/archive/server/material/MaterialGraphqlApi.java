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

import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ArrangementInput;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptInput;
import nu.fgv.archive.server.material.MaterialDtos.VersionDto;
import nu.fgv.archive.server.material.MaterialDtos.VersionInput;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * GraphQL for manuscripts, arrangements and versions. A thin adapter: {@link MaterialService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class MaterialGraphqlApi {

    private final MaterialService materialService;
    private final FileService fileService;
    private final SpexService spexService;
    private final NumberService numberService;

    public MaterialGraphqlApi(final MaterialService materialService, final FileService fileService, final SpexService spexService,
                              final NumberService numberService) {
        this.materialService = materialService;
        this.fileService = fileService;
        this.spexService = spexService;
        this.numberService = numberService;
    }

    private static <K, T> Map<K, @Nullable T> mapped(final List<K> keys, final Function<K, @Nullable UUID> id,
                                                     final Function<List<UUID>, Map<UUID, T>> lookup) {
        final Map<UUID, T> values = lookup.apply(keys.stream().map(id).filter(Objects::nonNull).distinct().toList());
        final Map<K, @Nullable T> result = new LinkedHashMap<>();

        keys.forEach(key -> {
            final UUID value = id.apply(key);

            result.put(key, value == null ? null : values.get(value));
        });
        return result;
    }

    @QueryMapping
    public @Nullable ManuscriptDto manuscript(@Argument final UUID id) {
        return materialService.manuscript(id);
    }

    @QueryMapping
    public @Nullable ArrangementDto arrangement(@Argument final UUID id) {
        return materialService.arrangement(id);
    }

    @QueryMapping
    public @Nullable VersionDto version(@Argument final UUID id) {
        return materialService.version(id);
    }

    @QueryMapping
    public List<VersionDto> recentVersions(@Argument final @Nullable Integer limit) {
        return materialService.recent(limit == null ? 12 : limit);
    }

    @BatchMapping(typeName = "Manuscript", field = "spex")
    public Map<ManuscriptDto, @Nullable SpexDto> manuscriptSpex(final List<ManuscriptDto> manuscripts) {
        return mapped(manuscripts, ManuscriptDto::spexId, spexService::byIds);
    }

    @BatchMapping(typeName = "Manuscript", field = "versions")
    public Map<ManuscriptDto, @Nullable List<VersionDto>> manuscriptVersions(final List<ManuscriptDto> manuscripts) {
        return mapped(manuscripts, ManuscriptDto::id, ids -> materialService.versionsOf(ids, null, PartFilter.NONE));
    }

    @BatchMapping(typeName = "Arrangement", field = "number")
    public Map<ArrangementDto, @Nullable NumberDto> arrangementNumber(final List<ArrangementDto> arrangements) {
        return mapped(arrangements, ArrangementDto::numberId, numberService::byIds);
    }

    /**
     * The versions, narrowed to a key and to versions with matching parts when asked.
     */
    @SchemaMapping(typeName = "Arrangement", field = "versions")
    public List<VersionDto> arrangementVersions(final ArrangementDto arrangement, @Argument final @Nullable MusicKey key,
                                                @Argument final @Nullable PartFilter parts) {
        return materialService.versionsOf(List.of(arrangement.id()), key, PartFilter.orNone(parts)).get(arrangement.id());
    }

    @BatchMapping(typeName = "Version", field = "manuscript")
    public Map<VersionDto, @Nullable ManuscriptDto> versionManuscript(final List<VersionDto> versions) {
        return mapped(versions, VersionDto::manuscriptId, materialService::manuscriptsByIds);
    }

    @BatchMapping(typeName = "Version", field = "arrangement")
    public Map<VersionDto, @Nullable ArrangementDto> versionArrangement(final List<VersionDto> versions) {
        return mapped(versions, VersionDto::arrangementId, materialService::arrangementsByIds);
    }

    @BatchMapping(typeName = "Version", field = "usedIn")
    public Map<VersionDto, @Nullable SpexDto> versionUsedIn(final List<VersionDto> versions) {
        return mapped(versions, VersionDto::usedInSpexId, spexService::byIds);
    }

    /**
     * The files, narrowed to the parts of some instruments or transpositions when asked.
     */
    @SchemaMapping(typeName = "Version", field = "files")
    public List<FileDto> versionFiles(final VersionDto version, @Argument final @Nullable PartFilter parts) {
        return fileService.filesOf(List.of(version.id()), PartFilter.orNone(parts)).get(version.id());
    }

    @MutationMapping
    public ManuscriptDto manuscriptCreate(@Argument final ManuscriptInput input) {
        return materialService.createManuscript(input);
    }

    @MutationMapping
    public ManuscriptDto manuscriptUpdate(@Argument final UUID id, @Argument final ManuscriptInput input) {
        return materialService.updateManuscript(id, input);
    }

    @MutationMapping
    public ManuscriptDto manuscriptTrash(@Argument final UUID id) {
        return materialService.trashManuscript(id, true);
    }

    @MutationMapping
    public ManuscriptDto manuscriptRestore(@Argument final UUID id) {
        return materialService.trashManuscript(id, false);
    }

    @MutationMapping
    public @Nullable Void manuscriptDelete(@Argument final UUID id) {
        materialService.deleteManuscript(id);
        return null;
    }

    @MutationMapping
    public ArrangementDto arrangementCreate(@Argument final ArrangementInput input) {
        return materialService.createArrangement(input);
    }

    @MutationMapping
    public ArrangementDto arrangementUpdate(@Argument final UUID id, @Argument final ArrangementInput input) {
        return materialService.updateArrangement(id, input);
    }

    @MutationMapping
    public ArrangementDto arrangementTrash(@Argument final UUID id) {
        return materialService.trashArrangement(id, true);
    }

    @MutationMapping
    public ArrangementDto arrangementRestore(@Argument final UUID id) {
        return materialService.trashArrangement(id, false);
    }

    @MutationMapping
    public @Nullable Void arrangementDelete(@Argument final UUID id) {
        materialService.deleteArrangement(id);
        return null;
    }

    @MutationMapping
    public VersionDto versionCreate(@Argument final UUID parentId, @Argument final VersionInput input) {
        return materialService.createVersion(parentId, input);
    }

    @MutationMapping
    public VersionDto versionUpdate(@Argument final UUID id, @Argument final VersionInput input) {
        return materialService.updateVersion(id, input);
    }

    @MutationMapping
    public VersionDto versionCopy(@Argument final UUID id) {
        return materialService.copyVersion(id);
    }

    @MutationMapping
    public VersionDto versionPublish(@Argument final UUID id, @Argument final boolean published) {
        return materialService.publishVersion(id, published);
    }

    @MutationMapping
    public VersionDto versionRecommend(@Argument final UUID id, @Argument final boolean recommended) {
        return materialService.recommendVersion(id, recommended);
    }

    @MutationMapping
    public VersionDto versionTrash(@Argument final UUID id) {
        return materialService.trashVersion(id, true);
    }

    @MutationMapping
    public VersionDto versionRestore(@Argument final UUID id) {
        return materialService.trashVersion(id, false);
    }

    @MutationMapping
    public @Nullable Void versionDelete(@Argument final UUID id) {
        materialService.deleteVersion(id);
        return null;
    }
}
