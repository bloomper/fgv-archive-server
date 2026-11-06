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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.archive.server.util.AbstractAuditable;
import nu.fgv.archive.server.util.Uuids;

import java.util.UUID;

/**
 * What a file is in its version, e.g. the score or a part. Configured by admins, with a Swedish and an English name. The
 * defaults have fixed ids, which guessing a file's role from its name looks for.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "file_role")
@Getter
@Setter
public class FileRole extends AbstractAuditable {

    public static final UUID MANUSCRIPT = UUID.fromString("00000000-0000-7000-8000-00000000d001");
    public static final UUID SCORE = UUID.fromString("00000000-0000-7000-8000-00000000d002");
    public static final UUID PART = UUID.fromString("00000000-0000-7000-8000-00000000d003");
    public static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000d004");
    public static final UUID REFERENCE_AUDIO = UUID.fromString("00000000-0000-7000-8000-00000000d005");
    public static final UUID SCAN = UUID.fromString("00000000-0000-7000-8000-00000000d006");
    public static final UUID OTHER = UUID.fromString("00000000-0000-7000-8000-00000000d007");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "name_sv", nullable = false)
    private String nameSv;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "position", nullable = false)
    private int position;
}
