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

import nu.fgv.archive.server.material.MaterialDtos.ArrangementDto;
import nu.fgv.archive.server.material.MaterialDtos.ManuscriptDto;
import nu.fgv.archive.server.material.MaterialDtos.VersionDto;
import nu.fgv.archive.server.material.MaterialKind;
import nu.fgv.archive.server.material.MaterialService;
import nu.fgv.archive.server.number.NumberService;
import nu.fgv.archive.server.number.NumberService.NumberDto;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Shared by the MCP tools, trimmed from Nyuppsättning's: refusals as readable messages, the result records, and what a
 * manuscript or an arrangement is by name. The tools only read, so nothing is stamped for the audit log.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class McpToolSupport {

    private final MessageSource messageSource;
    private final MaterialService materialService;
    private final NumberService numberService;
    private final SpexService spexService;

    public McpToolSupport(final MessageSource messageSource, final MaterialService materialService, final NumberService numberService,
                          final SpexService spexService) {
        this.messageSource = messageSource;
        this.materialService = materialService;
        this.numberService = numberService;
        this.spexService = spexService;
    }

    static SpexRef spexRef(final SpexDto spex) {
        return new SpexRef(spex.id(), spex.title(), spex.year(), spex.revival());
    }

    static @Nullable SpexRef spexRefOrNull(final @Nullable SpexDto spex) {
        return spex == null ? null : spexRef(spex);
    }

    static NumberRef numberRef(final NumberDto number) {
        return new NumberRef(number.id(), number.act(), number.position(), number.title(), number.originalSong());
    }

    static VersionRef versionRef(final VersionDto version) {
        return new VersionRef(version.id(), version.label(), version.date(), version.recommended(), version.published(),
                version.restricted(), version.canOpen());
    }

    /**
     * Runs a tool. A refusal becomes a tool error with the API's error code and the same English message the web shows,
     * e.g. "FORBIDDEN: You are not allowed to do that.".
     */
    <T> T call(final Supplier<T> body) {
        try {
            return body.get();
        } catch (final AccessDeniedException e) {
            throw refusal("FORBIDDEN", "error.forbidden", null, e);
        } catch (final AuthenticationException e) {
            throw refusal("UNAUTHORIZED", "error.unauthorized", null, e);
        } catch (final BadRequestException e) {
            throw refusal("BAD_REQUEST", e);
        } catch (final ResourceNotFoundException e) {
            throw refusal("NOT_FOUND", e);
        }
    }

    private McpToolRefusal refusal(final String code, final ResponseStatusException e) {
        return refusal(code, e.getDetailMessageCode(), e.getDetailMessageArguments(), e);
    }

    private McpToolRefusal refusal(final String code, final @Nullable String messageCode, final Object @Nullable [] arguments,
                                   final Exception cause) {
        final String key = messageCode == null ? "error.notFound" : messageCode;

        return new McpToolRefusal("%s: %s".formatted(code, messageSource.getMessage(key, arguments, key, Locale.ENGLISH)), cause);
    }

    /**
     * Manuscripts and arrangements the caller sees, by id, with their spex and, for an arrangement, its number; ids the
     * caller does not see are left out.
     */
    Map<UUID, MaterialRef> materials(final Collection<UUID> manuscriptIds, final Collection<UUID> arrangementIds) {
        final Map<UUID, ManuscriptDto> manuscripts = materialService.manuscriptsByIds(new HashSet<>(manuscriptIds));
        final Map<UUID, ArrangementDto> arrangements = materialService.arrangementsByIds(new HashSet<>(arrangementIds));
        final Map<UUID, NumberDto> numbers = numberService.byIds(arrangements.values().stream().map(ArrangementDto::numberId).distinct().toList());
        final Set<UUID> spexIds = new HashSet<>();

        manuscripts.values().stream().map(ManuscriptDto::spexId).filter(Objects::nonNull).forEach(spexIds::add);
        numbers.values().forEach(number -> spexIds.add(number.spexId()));

        final Map<UUID, SpexDto> spex = spexService.byIds(spexIds);
        final Map<UUID, MaterialRef> result = new HashMap<>();

        manuscripts.values().forEach(manuscript -> result.put(manuscript.id(), new MaterialRef(manuscript.id(), MaterialKind.MANUSCRIPT,
                manuscript.title(), spexRefOrNull(manuscript.spexId() == null ? null : spex.get(manuscript.spexId())), null)));
        arrangements.values().forEach(arrangement -> {
            final NumberDto number = numbers.get(arrangement.numberId());

            result.put(arrangement.id(), new MaterialRef(arrangement.id(), MaterialKind.ARRANGEMENT, number == null ? null : number.title(),
                    number == null ? null : spexRefOrNull(spex.get(number.spexId())), number == null ? null : numberRef(number)));
        });
        return result;
    }

    /**
     * The manuscript or arrangement of each version, as {@link #materials}.
     */
    Map<UUID, MaterialRef> materialsOf(final Collection<VersionDto> versions) {
        return materials(versions.stream().map(VersionDto::manuscriptId).filter(Objects::nonNull).toList(),
                versions.stream().map(VersionDto::arrangementId).filter(Objects::nonNull).toList());
    }

    static UUID parentOf(final VersionDto version) {
        return Objects.requireNonNull(Stream.of(version.manuscriptId(), version.arrangementId()).filter(Objects::nonNull).findFirst()
                .orElse(version.parentId()));
    }

    static final class McpToolRefusal extends RuntimeException {

        McpToolRefusal(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    public record ListResult<T>(List<T> items) {
    }

    /**
     * A spex by name: an original, or a revival of one.
     */
    public record SpexRef(UUID id, @Nullable String title, @Nullable Integer year, boolean revival) {
    }

    /**
     * A number (a song) of a spex, by act and position in the show.
     */
    public record NumberRef(UUID id, int act, int position, String title, @Nullable String originalSong) {
    }

    /**
     * A manuscript or an arrangement by name: a manuscript's title, an arrangement's number's.
     */
    public record MaterialRef(UUID id, MaterialKind kind, @Nullable String title, @Nullable SpexRef spex,
                              @Nullable NumberRef number) {
    }

    /**
     * A version by name, with whether it is published and restricted, and whether the caller may read its files.
     */
    public record VersionRef(UUID id, String label, @Nullable String date, boolean recommended, boolean published,
                             boolean restricted, boolean canOpen) {
    }

    /**
     * A file by name, with its page count when it is a PDF.
     */
    public record FileRef(UUID id, String title, String name, String contentType, @Nullable Integer pages, boolean hasText,
                          Instant createdAt) {
    }
}
