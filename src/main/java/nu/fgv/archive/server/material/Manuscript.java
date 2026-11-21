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

import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import nu.fgv.archive.server.document.DocumentKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The script of a spex, with its versions: the original and the rewrites for revivals. Belongs to the original spex,
 * never to a revival.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "manuscript")
@Getter
@Setter
public class Manuscript extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    /** The original spex, or none for a document of FGV's own (a songbook, a jubilee). */
    @Column(name = "spex_id", length = 36)
    private @Nullable UUID spexId;

    /** A document kind kept by admins (spec 4.1): a manuscript, a programme, a synopsis … */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kind_id", nullable = false)
    private DocumentKind kind;

    @Column(name = "title", nullable = false)
    private String title;

    /** A book's number in its series, e.g. 1 for Chalmersspex I (spec 4.7). */
    @Column(name = "volume")
    private @Nullable Integer volume;

    /** When a book was published: a year, or a date as precise as known. */
    @Column(name = "published_year")
    private @Nullable String publishedYear;

    /** A book's songs, in the book's order; only documents of a book kind have them. */
    @jakarta.persistence.OneToMany(mappedBy = "book", cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<BookSong> songs = new ArrayList<>();

    @Column(name = "authors")
    private @Nullable String authors;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    @OneToMany(mappedBy = "manuscript")
    @OrderBy("id")
    private List<MaterialVersion> versions = new ArrayList<>();
}
