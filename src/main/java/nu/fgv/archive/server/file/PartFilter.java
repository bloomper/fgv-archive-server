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

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Narrows parts to some instruments and transpositions (product spec 4.5, 4.6). A file matches when it carries any of
 * the instruments and is written in any of the transpositions; an empty list does not narrow, and an empty filter
 * matches every file.
 *
 * @param instrumentIds the instruments, any of which a file carries
 * @param transpositions the pitches, any of which a file is written in
 * @author Anders Jacobsson
 * @since 1.0
 */
public record PartFilter(@Nullable List<UUID> instrumentIds, @Nullable List<Pitch> transpositions) {

    public static final PartFilter NONE = new PartFilter(null, null);

    public static PartFilter orNone(final @Nullable PartFilter filter) {
        return filter == null ? NONE : filter;
    }

    public boolean isEmpty() {
        return (instrumentIds == null || instrumentIds.isEmpty()) && (transpositions == null || transpositions.isEmpty());
    }

    /**
     * Whether a file is a part (role {@link FileRole#PART}) that the filter matches; a score or a source that carries
     * instruments is never one. What lists, zips and shares parts uses this.
     */
    public boolean matchesPart(final ArchiveFile file) {
        return FileRole.PART.equals(file.getRole().getUuid()) && matches(file);
    }

    /**
     * The same for a file as the caller sees it.
     */
    public boolean matchesPart(final FileDtos.FileDto file) {
        return FileRole.PART.equals(file.role().id()) && matches(file);
    }

    public boolean matches(final ArchiveFile file) {
        return matches(file.getInstruments().stream().map(Instrument::getUuid).toList(), file.getTranspositionPitch());
    }

    /**
     * The same for a file as the caller sees it.
     */
    public boolean matches(final FileDtos.FileDto file) {
        return matches(file.instruments().stream().map(FileDtos.InstrumentDto::id).toList(),
                file.transposition() == null ? null : file.transposition().pitch());
    }

    private boolean matches(final Collection<UUID> carried, final @Nullable Pitch pitch) {
        if (isEmpty()) {
            return true;
        }
        return (instrumentIds == null || instrumentIds.isEmpty() || carried.stream().anyMatch(instrumentIds::contains))
                && (transpositions == null || transpositions.isEmpty() || transpositions.contains(pitch));
    }
}
