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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * Repository for {@link FilePage}, with MySQL's full-text search.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface FilePageRepository extends JpaRepository<FilePage, Long> {

    @Modifying
    @Query("DELETE FROM FilePage p WHERE p.file = :file")
    void deleteByFile(ArchiveFile file);

    /**
     * The best matching pages, as rows of file id, page and text.
     *
     * @param query a boolean mode query, e.g. {@code +kärlek* +duett*}
     */
    @Query(value = """
            SELECT p.file_id, p.page, p.text FROM file_page p
            WHERE MATCH(p.text) AGAINST (:query IN BOOLEAN MODE)
            ORDER BY MATCH(p.text) AGAINST (:query IN BOOLEAN MODE) DESC, p.file_id, p.page
            LIMIT :limit""", nativeQuery = true)
    List<Object[]> search(String query, int limit);
}
