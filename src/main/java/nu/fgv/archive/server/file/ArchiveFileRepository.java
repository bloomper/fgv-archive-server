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

import nu.fgv.archive.server.material.MaterialVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ArchiveFile}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ArchiveFileRepository extends JpaRepository<ArchiveFile, Long> {

    Optional<ArchiveFile> findByUuid(UUID uuid);

    List<ArchiveFile> findByUuidIn(Collection<UUID> uuids);

    List<ArchiveFile> findByIdIn(Collection<Long> ids);

    List<ArchiveFile> findByVersionInOrderByPositionAscIdAsc(Collection<MaterialVersion> versions);

    List<ArchiveFile> findByProcessingStatusOrderByIdAsc(ProcessingStatus status, Pageable pageable);

    List<ArchiveFile> findByProcessingStatusAndHasPreviewTrue(ProcessingStatus status);

    @Query("SELECT f FROM ArchiveFile f JOIN f.instruments i WHERE i = :instrument")
    List<ArchiveFile> findByInstrument(Instrument instrument);

    @Query("SELECT COALESCE(SUM(f.size), 0) FROM ArchiveFile f")
    long totalSize();

    /**
     * Queues failed files, and PDFs and images without a page count or preview, for the worker again.
     */
    @Modifying
    @Query("""
            UPDATE ArchiveFile f SET f.processingStatus = nu.fgv.archive.server.file.ProcessingStatus.PENDING, f.processingAttempts = 0
            WHERE f.processingStatus = nu.fgv.archive.server.file.ProcessingStatus.FAILED
               OR (f.processingStatus = nu.fgv.archive.server.file.ProcessingStatus.DONE AND f.contentType = 'application/pdf'
                   AND (f.hasPreview = false OR f.pages IS NULL))""")
    int requeueIncomplete();
}
