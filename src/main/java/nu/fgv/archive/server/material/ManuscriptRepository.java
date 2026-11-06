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
 * Repository for {@link Manuscript}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ManuscriptRepository extends JpaRepository<Manuscript, Long> {

    Optional<Manuscript> findByUuid(UUID uuid);

    List<Manuscript> findBySpexIdInOrderByIdAsc(Collection<UUID> spexIds);

    List<Manuscript> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    /**
     * Manuscripts whose title or authors contain the text, regardless of case.
     */
    @Query("SELECT m FROM Manuscript m WHERE LOWER(m.title) LIKE :pattern ESCAPE '\\' OR LOWER(m.authors) LIKE :pattern ESCAPE '\\' ORDER BY m.title")
    List<Manuscript> search(String pattern);

    /**
     * The spex that have a manuscript with a published version outside the trash.
     */
    @Query("""
            SELECT DISTINCT m.spexId FROM Manuscript m JOIN m.versions v
            WHERE m.trashedAt IS NULL AND v.trashedAt IS NULL AND v.published = true""")
    List<UUID> findSpexWithPublished();
}
