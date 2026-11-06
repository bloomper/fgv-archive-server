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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * An instrument or a voice, used in instrumentation and for the parts. Configured by admins; the names and
 * abbreviations also recognise parts by their file names.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "instrument")
@Getter
@Setter
public class Instrument extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "name_sv", nullable = false)
    private String nameSv;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_group", nullable = false)
    private InstrumentGroup group;

    /**
     * Other names and abbreviations, comma-separated, e.g. "tp,tpt,trp".
     */
    @Column(name = "abbreviations")
    private @Nullable String abbreviations;

    /**
     * The pitch a new part for the instrument is written in, C for concert pitch.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_pitch", nullable = false)
    private Pitch defaultPitch = Pitch.C;

    /**
     * The octave a part for the instrument sounds in relative to its pitch: -1, 0 or 1.
     */
    @Column(name = "default_octave", nullable = false)
    private int defaultOctave;

    @Column(name = "position", nullable = false)
    private int position;

    public List<String> abbreviationList() {
        return abbreviations == null ? List.of()
                : Arrays.stream(abbreviations.split(",")).map(String::strip).filter(value -> !value.isEmpty()).toList();
    }
}
