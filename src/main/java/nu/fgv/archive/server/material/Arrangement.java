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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The sheet music of one number, with its versions: the original, re-arrangements and transpositions. A number has at
 * most one arrangement; it gives the spex, the title, the original song and the credits.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "arrangement")
@Getter
@Setter
public class Arrangement extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "number_id", nullable = false, length = 36)
    private UUID numberId;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "trashed_at")
    private @Nullable Instant trashedAt;

    @OneToMany(mappedBy = "arrangement")
    @OrderBy("id")
    private List<MaterialVersion> versions = new ArrayList<>();
}
