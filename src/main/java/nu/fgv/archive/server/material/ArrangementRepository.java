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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Arrangement}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ArrangementRepository extends JpaRepository<Arrangement, Long> {

    Optional<Arrangement> findByUuid(UUID uuid);

    Optional<Arrangement> findByNumberId(UUID numberId);

    List<Arrangement> findByNumberIdIn(Collection<UUID> numberIds);

    List<Arrangement> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    /**
     * The numbers that have an arrangement with a published version outside the trash.
     */
    @Query("""
            SELECT DISTINCT a.numberId FROM Arrangement a JOIN a.versions v
            WHERE a.trashedAt IS NULL AND v.trashedAt IS NULL AND v.published = true""")
    List<UUID> findNumbersWithPublished();

    /**
     * Arrangements whose versions' arrangers contain the text, regardless of case.
     */
    @Query("""
            SELECT DISTINCT a FROM Arrangement a JOIN a.versions v
            WHERE LOWER(v.arranger) LIKE :pattern ESCAPE '\\' OR LOWER(a.description) LIKE :pattern ESCAPE '\\'""")
    List<Arrangement> search(String pattern);
}
