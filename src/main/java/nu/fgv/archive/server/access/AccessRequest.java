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

package nu.fgv.archive.server.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A user's request for access to restricted files, with their reason, which the editors are told about by email. Kept
 * 24 months.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "access_request")
@Getter
@Setter
public class AccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "user_subject", nullable = false)
    private String userSubject;

    @Column(name = "user_name")
    private @Nullable String userName;

    @Column(name = "user_email")
    private @Nullable String userEmail;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToMany
    @JoinTable(name = "access_request_file",
            joinColumns = @JoinColumn(name = "access_request_id"),
            inverseJoinColumns = @JoinColumn(name = "file_id"))
    private Set<ArchiveFile> files = new LinkedHashSet<>();
}
