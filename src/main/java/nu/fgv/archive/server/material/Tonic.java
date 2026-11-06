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

/**
 * The tonic of a key, spelled as the arranger wrote it: 17 spellings, so that F♯ major and G♭ major are told apart.
 * ASCII in the API; the web shows ♯ and ♭, and Swedish note names in Swedish.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum Tonic {
    C(0),
    C_SHARP(1),
    D_FLAT(1),
    D(2),
    D_SHARP(3),
    E_FLAT(3),
    E(4),
    F(5),
    F_SHARP(6),
    G_FLAT(6),
    G(7),
    G_SHARP(8),
    A_FLAT(8),
    A(9),
    A_SHARP(10),
    B_FLAT(10),
    B(11);

    private final int semitone;

    Tonic(final int semitone) {
        this.semitone = semitone;
    }

    /**
     * The pitch class, from C = 0, which enharmonic spellings share.
     */
    public int semitone() {
        return semitone;
    }
}
