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

import nu.fgv.archive.server.file.FileDtos.FileAddInput;
import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.file.FileDtos.FileGuessDto;
import nu.fgv.archive.server.file.FileDtos.FileInput;
import nu.fgv.archive.server.file.FileDtos.FileRoleDto;
import nu.fgv.archive.server.file.FileDtos.FileRoleInput;
import nu.fgv.archive.server.file.FileDtos.InstrumentDto;
import nu.fgv.archive.server.file.FileDtos.InstrumentInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for files, file roles and instruments. A thin adapter: the services decide access. Files' content goes over
 * plain HTTP: uploads with tus, downloads from {@code /api/files/{id}}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class FileGraphqlApi {

    private final FileService fileService;
    private final FileRoleService roleService;
    private final InstrumentService instrumentService;

    public FileGraphqlApi(final FileService fileService, final FileRoleService roleService, final InstrumentService instrumentService) {
        this.fileService = fileService;
        this.roleService = roleService;
        this.instrumentService = instrumentService;
    }

    @QueryMapping
    public @Nullable FileDto file(@Argument final UUID id) {
        return fileService.get(id);
    }

    @QueryMapping
    public List<FileRoleDto> fileRoles() {
        return roleService.roles();
    }

    @QueryMapping
    public List<InstrumentDto> instruments() {
        return instrumentService.instruments();
    }

    @QueryMapping
    public FileGuessDto fileGuess(@Argument final UUID versionId, @Argument final String name) {
        return fileService.guess(versionId, name);
    }

    @BatchMapping(typeName = "FileRole", field = "fileCount")
    public Map<FileRoleDto, @Nullable Integer> fileCount(final List<FileRoleDto> roles) {
        final Map<UUID, Integer> counts = roleService.fileCounts(roles.stream().map(FileRoleDto::id).toList());
        final Map<FileRoleDto, @Nullable Integer> result = new LinkedHashMap<>();

        roles.forEach(role -> result.put(role, counts.get(role.id())));
        return result;
    }

    @BatchMapping(typeName = "Instrument", field = "useCount")
    public Map<InstrumentDto, @Nullable Integer> useCount(final List<InstrumentDto> instruments) {
        final Map<UUID, Integer> counts = instrumentService.useCounts(instruments.stream().map(InstrumentDto::id).toList());
        final Map<InstrumentDto, @Nullable Integer> result = new LinkedHashMap<>();

        instruments.forEach(instrument -> result.put(instrument, counts.get(instrument.id())));
        return result;
    }

    @MutationMapping
    public FileDto fileAdd(@Argument final FileAddInput input) throws IOException {
        return fileService.add(input);
    }

    @MutationMapping
    public FileDto fileUpdate(@Argument final UUID id, @Argument final FileInput input) {
        return fileService.update(id, input);
    }

    @MutationMapping
    public FileDto fileReplace(@Argument final UUID id, @Argument final String uploadId) throws IOException {
        return fileService.replace(id, uploadId);
    }

    @MutationMapping
    public List<FileDto> filesReorder(@Argument final UUID versionId, @Argument final List<UUID> ids) {
        return fileService.reorder(versionId, ids);
    }

    @MutationMapping
    public @Nullable Void fileDelete(@Argument final UUID id) {
        fileService.delete(id);
        return null;
    }

    @MutationMapping
    public FileRoleDto fileRoleCreate(@Argument final FileRoleInput input) {
        return roleService.create(input);
    }

    @MutationMapping
    public FileRoleDto fileRoleUpdate(@Argument final UUID id, @Argument final FileRoleInput input) {
        return roleService.update(id, input);
    }

    @MutationMapping
    public List<FileRoleDto> fileRolesReorder(@Argument final List<UUID> ids) {
        return roleService.reorder(ids);
    }

    @MutationMapping
    public @Nullable Void fileRoleDelete(@Argument final UUID id) {
        roleService.delete(id);
        return null;
    }

    @MutationMapping
    public InstrumentDto instrumentCreate(@Argument final InstrumentInput input) {
        return instrumentService.create(input);
    }

    @MutationMapping
    public InstrumentDto instrumentUpdate(@Argument final UUID id, @Argument final InstrumentInput input) {
        return instrumentService.update(id, input);
    }

    @MutationMapping
    public List<InstrumentDto> instrumentsReorder(@Argument final List<UUID> ids) {
        return instrumentService.reorder(ids);
    }

    @MutationMapping
    public @Nullable Void instrumentDelete(@Argument final UUID id) {
        instrumentService.delete(id);
        return null;
    }

    @MutationMapping
    public InstrumentDto instrumentMerge(@Argument final UUID id, @Argument final UUID intoId) {
        return instrumentService.merge(id, intoId);
    }
}
