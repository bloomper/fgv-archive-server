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

import nu.fgv.archive.server.material.ArrangementRepository;
import nu.fgv.archive.server.material.ManuscriptRepository;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What Spexipedia asks the archive with {@code LINKS}: the manuscripts of spex and the arrangements of numbers that have
 * a published version outside the trash. Titles and ids only; never files.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SpexipediaLinksGraphqlApi {

    private static final int MAX_IDS = 500;

    private final ManuscriptRepository manuscriptRepository;
    private final ArrangementRepository arrangementRepository;
    private final NumberService numberService;
    private final CallerService callerService;

    public SpexipediaLinksGraphqlApi(final ManuscriptRepository manuscriptRepository, final ArrangementRepository arrangementRepository,
                                     final NumberService numberService, final CallerService callerService) {
        this.manuscriptRepository = manuscriptRepository;
        this.arrangementRepository = arrangementRepository;
        this.numberService = numberService;
        this.callerService = callerService;
    }

    private static boolean published(final List<MaterialVersion> versions) {
        return versions.stream().anyMatch(version -> version.isPublished() && version.getTrashedAt() == null);
    }

    @QueryMapping
    @Transactional(readOnly = true)
    public Links spexipediaLinks(@Argument final List<UUID> spexIds, @Argument final List<UUID> numberIds) {
        if (!callerService.current().isLinksService()) {
            throw new AccessDeniedException("LINKS required");
        }

        final List<UUID> spex = spexIds.subList(0, Math.min(spexIds.size(), MAX_IDS));
        final List<UUID> numbers = numberIds.subList(0, Math.min(numberIds.size(), MAX_IDS));
        final List<ManuscriptLink> manuscripts = spex.isEmpty() ? List.of() : manuscriptRepository.findBySpexIdInOrderByIdAsc(spex).stream()
                .filter(manuscript -> manuscript.getTrashedAt() == null && published(manuscript.getVersions()))
                .map(manuscript -> new ManuscriptLink(manuscript.getUuid(), manuscript.getTitle(), manuscript.getSpexId()))
                .toList();
        final Map<UUID, NumberService.NumberDto> titles = numberService.byIds(numbers);
        final List<ArrangementLink> arrangements = numbers.isEmpty() ? List.of() : arrangementRepository.findByNumberIdIn(numbers).stream()
                .filter(arrangement -> arrangement.getTrashedAt() == null && published(arrangement.getVersions()))
                .map(arrangement -> new ArrangementLink(arrangement.getUuid(),
                        titles.containsKey(arrangement.getNumberId()) ? titles.get(arrangement.getNumberId()).title() : "",
                        arrangement.getNumberId()))
                .toList();

        return new Links(manuscripts, arrangements);
    }

    public record ManuscriptLink(UUID id, String title, @Nullable UUID spexId) {
    }

    public record ArrangementLink(UUID id, String title, @Nullable UUID numberId) {
    }

    public record Links(List<ManuscriptLink> manuscripts, List<ArrangementLink> arrangements) {
    }
}
