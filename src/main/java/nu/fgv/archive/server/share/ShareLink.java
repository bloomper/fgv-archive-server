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

package nu.fgv.archive.server.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.Instrument;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.file.Pitch;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A link that opens chosen versions (all their files) and chosen files, and/or the parts of chosen instruments across a
 * spex' arrangements (including parts added later), to anyone who has it, until it expires or is revoked. Known by its token's SHA-256 only; the token itself is shown once, when the link is created.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "share_link")
@Getter
@Setter
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * For a link made for instruments: the spex whose arrangements' parts it opens.
     */
    @Column(name = "spex_id", length = 36)
    private @Nullable UUID spexId;

    /**
     * For a link made for instruments: the transpositions it opens, comma-separated; empty for all.
     */
    @Column(name = "transpositions")
    private @Nullable String transpositions;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_by_name")
    private @Nullable String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;

    @Column(name = "revoked_by_name")
    private @Nullable String revokedByName;

    @Version
    private Long version;

    @ManyToMany
    @JoinTable(name = "share_link_version",
            joinColumns = @JoinColumn(name = "share_link_id"),
            inverseJoinColumns = @JoinColumn(name = "version_id"))
    private Set<MaterialVersion> versions = new LinkedHashSet<>();

    @ManyToMany
    @JoinTable(name = "share_link_file",
            joinColumns = @JoinColumn(name = "share_link_id"),
            inverseJoinColumns = @JoinColumn(name = "file_id"))
    private Set<ArchiveFile> files = new LinkedHashSet<>();

    /**
     * For a link made for instruments: the instruments whose parts it opens.
     */
    @ManyToMany
    @JoinTable(name = "share_link_instrument",
            joinColumns = @JoinColumn(name = "share_link_id"),
            inverseJoinColumns = @JoinColumn(name = "instrument_id"))
    private Set<Instrument> instruments = new LinkedHashSet<>();

    public List<Pitch> transpositionList() {
        return transpositions == null ? List.of() : Arrays.stream(transpositions.split(",")).map(Pitch::valueOf).toList();
    }

    /**
     * What a link made for instruments opens of its spex' arrangements.
     */
    public PartFilter partFilter() {
        return new PartFilter(instruments.stream().map(Instrument::getUuid).toList(), transpositionList());
    }

    public ShareLinkStatus status(final Instant now) {
        if (revokedAt != null) {
            return ShareLinkStatus.REVOKED;
        }
        return expiresAt.isAfter(now) ? ShareLinkStatus.VALID : ShareLinkStatus.EXPIRED;
    }
}
