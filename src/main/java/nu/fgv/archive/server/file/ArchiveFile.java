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
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A file of a version, stored by id at {@code files/<version uuid>/<file uuid>.<extension>}, never by name, so edits
 * never move it. Downloads get its title as their name. Its text and preview are made by the worker.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "archive_file")
@Getter
@Setter
public class ArchiveFile extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false, updatable = false)
    private MaterialVersion version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private FileRole role;

    /**
     * The instruments the file carries, in order; a part carries at least one.
     */
    @ManyToMany
    @JoinTable(name = "file_instrument",
            joinColumns = @JoinColumn(name = "file_id"),
            inverseJoinColumns = @JoinColumn(name = "instrument_id"))
    @OrderColumn(name = "position")
    private List<Instrument> instruments = new ArrayList<>();

    /**
     * Which desk or voice a part is, e.g. "1" or "1–2".
     */
    @Column(name = "desk")
    private @Nullable String desk;

    /**
     * The pitch a part is written in, C for concert pitch; empty for files without one, e.g. a manuscript.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "transposition_pitch")
    private @Nullable Pitch transpositionPitch;

    /**
     * The octave the part sounds in relative to its pitch: -1, 0 or 1.
     */
    @Column(name = "transposition_octave", nullable = false)
    private int transpositionOctave;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "comment")
    private @Nullable String comment;

    /**
     * The name the file was uploaded with.
     */
    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "extension", nullable = false)
    private String extension;

    /**
     * The type detected from the content.
     */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size", nullable = false)
    private long size;

    /**
     * SHA-256, hex.
     */
    @Column(name = "checksum", nullable = false)
    private String checksum;

    @Column(name = "pages")
    private @Nullable Integer pages;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    @Column(name = "processing_attempts", nullable = false)
    private int processingAttempts;

    @Column(name = "processing_error")
    private @Nullable String processingError;

    @Column(name = "has_preview", nullable = false)
    private boolean hasPreview;

    @Column(name = "has_text", nullable = false)
    private boolean hasText;

    @Column(name = "download_count", nullable = false)
    private int downloadCount;

    public String fileName() {
        return uuid + "." + extension;
    }

    /**
     * Whether the file carries any of the instruments, by id.
     */
    public boolean carriesAny(final Collection<UUID> instrumentIds) {
        return instruments.stream().anyMatch(instrument -> instrumentIds.contains(instrument.getUuid()));
    }
}
