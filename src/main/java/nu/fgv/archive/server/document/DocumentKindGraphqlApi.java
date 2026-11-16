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

package nu.fgv.archive.server.document;

import java.util.Map;
import nu.fgv.archive.server.material.MaterialDtos;
import org.jspecify.annotations.Nullable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * GraphQL for document kinds (spec 4.1).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class DocumentKindGraphqlApi {

    private final DocumentKindService service;

    public DocumentKindGraphqlApi(final DocumentKindService service) {
        this.service = service;
    }

    @QueryMapping
    public List<DocumentKind> documentKinds(@Argument final boolean includeRetired) {
        return service.kinds(includeRetired);
    }

    @MutationMapping
    public DocumentKind documentKindSave(@Argument final @Nullable UUID id, @Argument final DocumentKindService.DocumentKindInput input) {
        return service.save(id, input);
    }

    @MutationMapping
    public List<DocumentKind> documentKindsReorder(@Argument final List<UUID> ids) {
        return service.reorder(ids);
    }

    @MutationMapping
    public DocumentKind documentKindRetire(@Argument final UUID id, @Argument final boolean retired) {
        return service.retire(id, retired);
    }

    @MutationMapping
    public DocumentKind documentKindMerge(@Argument final UUID id, @Argument final UUID intoId) {
        return service.merge(id, intoId);
    }

    @SchemaMapping(typeName = "DocumentKind", field = "id")
    public UUID id(final DocumentKind kind) {
        return kind.getUuid();
    }

    @SchemaMapping(typeName = "DocumentKind", field = "name")
    public String name(final DocumentKind kind) {
        return Locale.ENGLISH.getLanguage().equals(LocaleContextHolder.getLocale().getLanguage()) ? kind.getNameEn() : kind.getNameSv();
    }

    @org.springframework.graphql.data.method.annotation.BatchMapping(typeName = "Manuscript", field = "kind")
    public Map<MaterialDtos.ManuscriptDto, DocumentKind> manuscriptKind(final List<MaterialDtos.ManuscriptDto> manuscripts) {
        final Map<Long, DocumentKind> kinds = service.byKeys(manuscripts.stream().map(MaterialDtos.ManuscriptDto::kindKey).collect(java.util.stream.Collectors.toSet()));

        return manuscripts.stream().collect(java.util.stream.Collectors.toMap(m -> m, m -> kinds.get(m.kindKey()), (a, _) -> a));
    }

    @SchemaMapping(typeName = "DocumentKind", field = "aliases")
    public List<String> aliases(final DocumentKind kind) {
        return kind.aliasList();
    }
}
