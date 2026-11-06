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
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Instrument}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByUuid(UUID uuid);

    List<Instrument> findAllByOrderByPositionAscIdAsc();

    /**
     * How many files, instrumentation entries and share links use the instrument.
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM file_instrument WHERE instrument_id = :id)
                 + (SELECT COUNT(*) FROM instrumentation_entry WHERE instrument_id = :id)
                 + (SELECT COUNT(*) FROM share_link_instrument WHERE instrument_id = :id)""", nativeQuery = true)
    long countUses(Long id);

    @Query(value = """
            SELECT i.id, (SELECT COUNT(*) FROM file_instrument f WHERE f.instrument_id = i.id)
                       + (SELECT COUNT(*) FROM instrumentation_entry e WHERE e.instrument_id = i.id)
                       + (SELECT COUNT(*) FROM share_link_instrument s WHERE s.instrument_id = i.id)
            FROM instrument i""", nativeQuery = true)
    List<Object[]> countUsesByInstrument();

    /**
     * Gives the share links made for one instrument the other instead, once per link.
     */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO share_link_instrument (share_link_id, instrument_id)
            SELECT share_link_id, :into FROM share_link_instrument WHERE instrument_id = :from""", nativeQuery = true)
    int copyShareLinks(Long from, Long into);

    @Modifying
    @Query(value = "DELETE FROM share_link_instrument WHERE instrument_id = :id", nativeQuery = true)
    int removeShareLinks(Long id);
}
