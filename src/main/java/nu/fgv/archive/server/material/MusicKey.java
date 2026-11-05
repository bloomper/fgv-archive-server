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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * A key: a tonic as spelled and major or minor, e.g. F major.
 *
 * @param tonic the tonic
 * @param mode  major or minor
 * @author Anders Jacobsson
 * @since 1.0
 */
@Embeddable
public record MusicKey(@Enumerated(EnumType.STRING) @Column(name = "tonic", nullable = false) Tonic tonic,
                       @Enumerated(EnumType.STRING) @Column(name = "mode", nullable = false) Mode mode) {

    /**
     * Whether the keys sound the same, whatever the spelling: C♯ major is D♭ major.
     */
    public boolean soundsLike(final MusicKey other) {
        return tonic.semitone() == other.tonic().semitone() && mode == other.mode();
    }
}
