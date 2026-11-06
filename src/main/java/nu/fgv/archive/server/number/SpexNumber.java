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

package nu.fgv.archive.server.number;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A number (song) of a spex, in the order of the show. Kept by Spexarkivet's editors until Spexipedia takes the numbers
 * over with the same ids; arrangements refer to it by its id.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "number")
@Getter
@Setter
public class SpexNumber extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "spex_id", nullable = false, length = 36)
    private UUID spexId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "title", nullable = false)
    private String title;

    /**
     * The melody the number is sung to, e.g. "Yesterday (The Beatles)".
     */
    @Column(name = "original_song")
    private @Nullable String originalSong;

    @Column(name = "lyricist")
    private @Nullable String lyricist;

    @Column(name = "composer")
    private @Nullable String composer;
}
