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

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One edition of a manuscript or an arrangement, with its files. Unpublished versions are seen only by editors;
 * restricted ones have their files opened only by editors and through share links. The arranger, key, tempo, duration
 * and instrumentation are an arrangement's only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "material_version")
@Getter
@Setter
public class MaterialVersion extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private MaterialKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manuscript_id")
    private @Nullable Manuscript manuscript;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arrangement_id")
    private @Nullable Arrangement arrangement;

    @Column(name = "label", nullable = false)
    private String label;

    /**
     * The spex the version was performed in: the original or one of its revivals.
     */
    @Column(name = "used_in_spex_id", length = 36)
    private @Nullable UUID usedInSpexId;

    @Column(name = "version_date")
    private @Nullable LocalDate date;

    @Column(name = "notes")
    private @Nullable String notes;

    @Column(name = "arranger")
    private @Nullable String arranger;

    /**
     * The concert key it sounds in.
     */
    @Embedded
    @AttributeOverride(name = "tonic", column = @Column(name = "key_tonic"))
    @AttributeOverride(name = "mode", column = @Column(name = "key_mode"))
    private @Nullable MusicKey key;

    /**
     * The keys it modulates to, in order.
     */
    @ElementCollection
    @CollectionTable(name = "version_modulation", joinColumns = @JoinColumn(name = "version_id"))
    @OrderColumn(name = "position")
    private List<MusicKey> modulations = new ArrayList<>();

    @Column(name = "tempo")
    private @Nullable String tempo;

    @Column(name = "duration")
    private @Nullable String duration;

    @Column(name = "recommended", nullable = false)
    private boolean recommended;

    @Column(name = "restricted", nullable = false)
    private boolean restricted;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private @Nullable Instant publishedAt;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    @ElementCollection
    @CollectionTable(name = "instrumentation_entry", joinColumns = @JoinColumn(name = "version_id"))
    @OrderBy("position")
    private List<InstrumentationEntry> instrumentation = new ArrayList<>();

    @OneToMany(mappedBy = "version")
    @OrderBy("position, id")
    private List<ArchiveFile> files = new ArrayList<>();

    /**
     * The manuscript's or arrangement's id.
     */
    public UUID parentUuid() {
        return manuscript != null ? manuscript.getUuid() : Objects.requireNonNull(arrangement).getUuid();
    }

    /**
     * Whether the version, or the manuscript or arrangement it belongs to, is in the trash.
     */
    public boolean isInTrash() {
        return trashedAt != null
                || (manuscript != null && manuscript.getTrashedAt() != null)
                || (arrangement != null && arrangement.getTrashedAt() != null);
    }

    /**
     * Whether everyone with {@code USER} sees the version: published and not in the trash.
     */
    public boolean isListed() {
        return published && !isInTrash();
    }
}
