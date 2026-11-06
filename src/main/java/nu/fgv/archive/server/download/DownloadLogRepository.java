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

package nu.fgv.archive.server.download;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link DownloadLog}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface DownloadLogRepository extends JpaRepository<DownloadLog, Long> {

    /**
     * Downloads through each share link, per file, as rows of share link id, file id and count.
     */
    @Query("""
            SELECT d.shareLinkId, d.fileId, COUNT(d) FROM DownloadLog d
            WHERE d.shareLinkId IN :shareLinkIds GROUP BY d.shareLinkId, d.fileId ORDER BY COUNT(d) DESC""")
    List<Object[]> countByShareLinks(Collection<Long> shareLinkIds);

    @Modifying
    @Query("DELETE FROM DownloadLog d WHERE d.at < :before")
    int deleteOlderThan(Instant before);

    @Modifying
    @Query(value = "UPDATE archive_file SET download_count = download_count + 1 WHERE id = :fileId", nativeQuery = true)
    void countDownload(Long fileId);
}
