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

import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.FileRole;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.number.SpexNumber;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.spex.SpexDto;
import nu.fgv.archive.server.spex.SpexService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The parts of a spex' arrangements, in the order of the show (product spec 4.5): for every number shown for the spex
 * (its own, or its original's for a revival without any), the arrangement's best version that may be used, and of it
 * the parts the filter matches, or every part without a filter. The best version is the one used in the spex, else
 * the recommended one, else the newest. Used for zips across a spex and for share links made for instruments, which so
 * open parts added later. Runs in the caller's transaction.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpexParts {

    private final SpexNumberRepository numberRepository;
    private final ArrangementRepository arrangementRepository;
    private final SpexService spexService;

    public SpexParts(final SpexNumberRepository numberRepository, final ArrangementRepository arrangementRepository,
                     final SpexService spexService) {
        this.numberRepository = numberRepository;
        this.arrangementRepository = arrangementRepository;
        this.spexService = spexService;
    }

    /**
     * @param usable which versions may be used, e.g. those the caller may open
     */
    public List<Part> find(final UUID spexId, final PartFilter filter, final Predicate<MaterialVersion> usable) {
        List<SpexNumber> numbers = numberRepository.findBySpexIdOrderByPositionAscIdAsc(spexId);
        final SpexDto spex = spexService.byIds(List.of(spexId)).get(spexId);

        if (numbers.isEmpty() && spex != null && spex.parentId() != null) {
            numbers = numberRepository.findBySpexIdOrderByPositionAscIdAsc(spex.parentId());
        }

        final Map<UUID, Arrangement> arrangements = arrangementRepository.findByNumberIdIn(numbers.stream().map(SpexNumber::getUuid).toList())
                .stream().filter(arrangement -> arrangement.getTrashedAt() == null)
                .collect(Collectors.toMap(Arrangement::getNumberId, Function.identity()));
        final Predicate<ArchiveFile> wanted = filter.isEmpty() ? file -> FileRole.PART.equals(file.getRole().getUuid()) : filter::matches;
        final List<Part> parts = new ArrayList<>();

        for (final SpexNumber number : numbers) {
            final Arrangement arrangement = arrangements.get(number.getUuid());
            final Optional<MaterialVersion> best = arrangement == null ? Optional.empty() : arrangement.getVersions().stream()
                    .filter(version -> version.getTrashedAt() == null && usable.test(version))
                    .min(Comparator.comparing((MaterialVersion version) -> !spexId.equals(version.getUsedInSpexId()))
                            .thenComparing(version -> !version.isRecommended())
                            .thenComparing(MaterialVersion::getId, Comparator.reverseOrder()));

            best.ifPresent(version -> version.getFiles().stream().filter(wanted)
                    .forEach(file -> parts.add(new Part(number, version, file))));
        }
        return parts;
    }

    /**
     * A part of a number's arrangement version.
     */
    public record Part(SpexNumber number, MaterialVersion version, ArchiveFile file) {
    }
}
