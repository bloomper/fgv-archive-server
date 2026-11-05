-- Spexarkivet's schema. Every exposed entity has a UUIDv7 (uuid); the BIGINT id stays internal. References to
-- Spexregister's (later Spexipedia's) spex and to numbers hold their UUIDs, so they survive a re-import of either side.

CREATE TABLE app_user
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)     NOT NULL,
    subject      VARCHAR(64)  NOT NULL,
    username     VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NULL,
    created_at   DATETIME(6)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_uuid UNIQUE (uuid),
    CONSTRAINT uk_app_user_subject UNIQUE (subject),
    INDEX idx_app_user_last_seen (last_seen_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A copy of every spex in Spexregister, refreshed every night, so that the archive works while Spexregister is down
CREATE TABLE spex
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    uuid          CHAR(36)     NOT NULL,
    parent_id     CHAR(36)     NULL,
    title         VARCHAR(255) NULL,
    year          SMALLINT     NULL,
    revival       BIT          NOT NULL DEFAULT 0,
    category_id   CHAR(36)     NULL,
    category_name VARCHAR(255) NULL,
    refreshed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_spex_uuid UNIQUE (uuid),
    INDEX idx_spex_parent (parent_id),
    INDEX idx_spex_category (category_id, year),
    INDEX idx_spex_year (year)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- The numbers of a spex, kept by Spexarkivet's editors until Spexipedia takes them over with the same ids
CREATE TABLE number
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    spex_id          CHAR(36)     NOT NULL,
    position         INT          NOT NULL,
    title            VARCHAR(255) NOT NULL,
    original_song    VARCHAR(255) NULL,
    lyricist         VARCHAR(255) NULL,
    composer         VARCHAR(255) NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_number_uuid UNIQUE (uuid),
    INDEX idx_number_spex (spex_id, position)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE manuscript
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    spex_id          CHAR(36)     NOT NULL,
    title            VARCHAR(255) NOT NULL,
    authors          VARCHAR(500) NULL,
    description      TEXT         NULL,
    trashed_at       DATETIME(6)  NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_manuscript_uuid UNIQUE (uuid),
    INDEX idx_manuscript_spex (spex_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- At most one arrangement per number
CREATE TABLE arrangement
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    number_id        CHAR(36)     NOT NULL,
    description      TEXT         NULL,
    trashed_at       DATETIME(6)  NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_arrangement_uuid UNIQUE (uuid),
    CONSTRAINT uk_arrangement_number UNIQUE (number_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A version of a manuscript or of an arrangement; the arrangement's fields are empty for manuscripts
CREATE TABLE material_version
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    kind             VARCHAR(20)  NOT NULL,
    manuscript_id    BIGINT       NULL,
    arrangement_id   BIGINT       NULL,
    label            VARCHAR(255) NOT NULL,
    used_in_spex_id  CHAR(36)     NULL,
    version_date     DATE         NULL,
    notes            TEXT         NULL,
    arranger         VARCHAR(255) NULL,
    -- The concert key: a tonic as the arranger spelled it, e.g. B_FLAT, and MAJOR or MINOR
    key_tonic        VARCHAR(10)  NULL,
    key_mode         VARCHAR(5)   NULL,
    tempo            VARCHAR(100) NULL,
    duration         VARCHAR(100) NULL,
    recommended      BIT          NOT NULL DEFAULT 0,
    restricted       BIT          NOT NULL DEFAULT 0,
    published        BIT          NOT NULL DEFAULT 0,
    published_at     DATETIME(6)  NULL,
    trashed_at       DATETIME(6)  NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_material_version_uuid UNIQUE (uuid),
    CONSTRAINT fk_material_version_manuscript FOREIGN KEY (manuscript_id) REFERENCES manuscript (id),
    CONSTRAINT fk_material_version_arrangement FOREIGN KEY (arrangement_id) REFERENCES arrangement (id),
    INDEX idx_material_version_used_in (used_in_spex_id),
    INDEX idx_material_version_modified (published, last_modified_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- The keys an arrangement version modulates to, in order
CREATE TABLE version_modulation
(
    version_id BIGINT      NOT NULL,
    position   INT         NOT NULL,
    tonic      VARCHAR(10) NOT NULL,
    mode       VARCHAR(5)  NOT NULL,
    PRIMARY KEY (version_id, position),
    CONSTRAINT fk_version_modulation_version FOREIGN KEY (version_id) REFERENCES material_version (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Roles and instruments are configured by admins; V1_2 adds the defaults
CREATE TABLE file_role
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    name_sv          VARCHAR(100) NOT NULL,
    name_en          VARCHAR(100) NOT NULL,
    position         INT          NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_file_role_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE instrument
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    name_sv          VARCHAR(100) NOT NULL,
    name_en          VARCHAR(100) NOT NULL,
    instrument_group VARCHAR(20)  NOT NULL,
    -- Other names and abbreviations that recognise the instrument in file names, comma-separated
    abbreviations    VARCHAR(500) NULL,
    -- The pitch a new part for the instrument is written in, C for concert pitch, and its octave shift (-1, 0, 1)
    default_pitch    VARCHAR(10)  NOT NULL DEFAULT 'C',
    default_octave   INT          NOT NULL DEFAULT 0,
    position         INT          NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_by       VARCHAR(255) NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    last_modified_by VARCHAR(255) NULL,
    last_modified_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instrument_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE instrumentation_entry
(
    version_id    BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    count         INT    NOT NULL,
    position      INT    NOT NULL,
    PRIMARY KEY (version_id, instrument_id),
    CONSTRAINT fk_instrumentation_version FOREIGN KEY (version_id) REFERENCES material_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_instrumentation_instrument FOREIGN KEY (instrument_id) REFERENCES instrument (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- A file lies at files/<version uuid>/<file uuid>.<extension>
CREATE TABLE archive_file
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    uuid                CHAR(36)      NOT NULL,
    version_id          BIGINT        NOT NULL,
    role_id             BIGINT        NOT NULL,
    title               VARCHAR(255)  NOT NULL,
    comment             VARCHAR(1000) NULL,
    -- Which desk or voice a part is, e.g. 1 or 1–2
    desk                VARCHAR(50)   NULL,
    -- The pitch a part is written in, C for concert pitch, and its octave shift; empty for files without one
    transposition_pitch VARCHAR(10)   NULL,
    transposition_octave INT          NOT NULL DEFAULT 0,
    original_name       VARCHAR(255)  NOT NULL,
    extension           VARCHAR(20)   NOT NULL,
    content_type        VARCHAR(100)  NOT NULL,
    size                BIGINT        NOT NULL,
    checksum            CHAR(64)      NOT NULL,
    pages               INT           NULL,
    position            INT           NOT NULL,
    processing_status   VARCHAR(20)   NOT NULL,
    processing_attempts INT           NOT NULL DEFAULT 0,
    processing_error    VARCHAR(1000) NULL,
    has_preview         BIT           NOT NULL DEFAULT 0,
    has_text            BIT           NOT NULL DEFAULT 0,
    download_count      INT           NOT NULL DEFAULT 0,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_by          VARCHAR(255)  NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    last_modified_by    VARCHAR(255)  NULL,
    last_modified_at    DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_archive_file_uuid UNIQUE (uuid),
    CONSTRAINT fk_archive_file_version FOREIGN KEY (version_id) REFERENCES material_version (id),
    CONSTRAINT fk_archive_file_role FOREIGN KEY (role_id) REFERENCES file_role (id),
    INDEX idx_archive_file_processing (processing_status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- The instruments a file carries, in order; a part carries at least one, e.g. trumpet 1 and 2, or flute and piccolo
CREATE TABLE file_instrument
(
    file_id       BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    position      INT    NOT NULL,
    PRIMARY KEY (file_id, instrument_id),
    CONSTRAINT fk_file_instrument_file FOREIGN KEY (file_id) REFERENCES archive_file (id) ON DELETE CASCADE,
    CONSTRAINT fk_file_instrument_instrument FOREIGN KEY (instrument_id) REFERENCES instrument (id),
    INDEX idx_file_instrument_instrument (instrument_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- The text of a file, page by page so that hits show their page; page 0 for files without pages, e.g. DOCX
CREATE TABLE file_page
(
    id      BIGINT     NOT NULL AUTO_INCREMENT,
    file_id BIGINT     NOT NULL,
    page    INT        NOT NULL,
    text    MEDIUMTEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_file_page_file FOREIGN KEY (file_id) REFERENCES archive_file (id) ON DELETE CASCADE,
    FULLTEXT INDEX ft_file_page_text (text)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Share links are known by their token's SHA-256 only. A link opens chosen versions and files, and/or the parts of
-- chosen instruments across a spex's arrangements (spex_id and share_link_instrument), including parts added later
CREATE TABLE share_link
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)     NOT NULL,
    name             VARCHAR(255) NOT NULL,
    spex_id          CHAR(36)     NULL,
    -- With spex_id: the transpositions shared, comma-separated, e.g. B_FLAT,E_FLAT; empty for all
    transpositions   VARCHAR(200) NULL,
    token_hash       CHAR(64)     NOT NULL,
    created_by       VARCHAR(64)  NOT NULL,
    created_by_name  VARCHAR(255) NULL,
    created_at       DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    revoked_at       DATETIME(6)  NULL,
    revoked_by_name  VARCHAR(255) NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_link_uuid UNIQUE (uuid),
    CONSTRAINT uk_share_link_token UNIQUE (token_hash),
    INDEX idx_share_link_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE share_link_version
(
    share_link_id BIGINT NOT NULL,
    version_id    BIGINT NOT NULL,
    PRIMARY KEY (share_link_id, version_id),
    CONSTRAINT fk_share_link_version_link FOREIGN KEY (share_link_id) REFERENCES share_link (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_link_version_version FOREIGN KEY (version_id) REFERENCES material_version (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE share_link_instrument
(
    share_link_id BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    PRIMARY KEY (share_link_id, instrument_id),
    CONSTRAINT fk_share_link_instrument_link FOREIGN KEY (share_link_id) REFERENCES share_link (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_link_instrument_instrument FOREIGN KEY (instrument_id) REFERENCES instrument (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE share_link_file
(
    share_link_id BIGINT NOT NULL,
    file_id       BIGINT NOT NULL,
    PRIMARY KEY (share_link_id, file_id),
    CONSTRAINT fk_share_link_file_link FOREIGN KEY (share_link_id) REFERENCES share_link (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_link_file_file FOREIGN KEY (file_id) REFERENCES archive_file (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Downloads of restricted files and through share links, with who; kept 24 months
CREATE TABLE download_log
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    file_id       BIGINT       NOT NULL,
    at            DATETIME(6)  NOT NULL,
    user_subject  VARCHAR(64)  NULL,
    user_name     VARCHAR(255) NULL,
    share_link_id BIGINT       NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_download_log_file FOREIGN KEY (file_id) REFERENCES archive_file (id) ON DELETE CASCADE,
    CONSTRAINT fk_download_log_share_link FOREIGN KEY (share_link_id) REFERENCES share_link (id) ON DELETE SET NULL,
    INDEX idx_download_log_at (at),
    INDEX idx_download_log_link (share_link_id, file_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Kept 24 months
CREATE TABLE access_request
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         CHAR(36)     NOT NULL,
    user_subject VARCHAR(64)  NOT NULL,
    user_name    VARCHAR(255) NULL,
    user_email   VARCHAR(255) NULL,
    reason       TEXT         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_access_request_uuid UNIQUE (uuid),
    INDEX idx_access_request_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE access_request_file
(
    access_request_id BIGINT NOT NULL,
    file_id           BIGINT NOT NULL,
    PRIMARY KEY (access_request_id, file_id),
    CONSTRAINT fk_access_request_file_request FOREIGN KEY (access_request_id) REFERENCES access_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_access_request_file_file FOREIGN KEY (file_id) REFERENCES archive_file (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE settings
(
    id                        BIGINT        NOT NULL,
    welcome_sv                TEXT          NULL,
    welcome_en                TEXT          NULL,
    max_upload_mb             INT           NOT NULL,
    manuscript_restricted     BIT           NOT NULL,
    arrangement_restricted    BIT           NOT NULL,
    max_share_link_months     INT           NOT NULL,
    -- Comma-separated email addresses; empty means everyone with EDITOR
    access_request_recipients VARCHAR(1000) NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    last_modified_by          VARCHAR(255)  NULL,
    last_modified_at          DATETIME(6)   NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO settings (id, max_upload_mb, manuscript_restricted, arrangement_restricted, max_share_link_months)
VALUES (1, 500, 0, 1, 24);

CREATE TABLE audit_entry
(
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    at            DATETIME(6)   NOT NULL,
    actor_subject VARCHAR(64)   NOT NULL,
    actor_name    VARCHAR(255)  NULL,
    action        VARCHAR(40)   NOT NULL,
    entity_type   VARCHAR(20)   NOT NULL,
    entity_id     VARCHAR(36)   NULL,
    summary       VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_entry_at (at),
    INDEX idx_audit_entry_actor (actor_subject, at),
    INDEX idx_audit_entry_entity (entity_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE upload_session
(
    id            CHAR(36)     NOT NULL,
    owner         VARCHAR(64)  NOT NULL,
    length        BIGINT       NOT NULL,
    upload_offset BIGINT       NOT NULL,
    file_name     VARCHAR(255) NULL,
    created_at    DATETIME(6)  NOT NULL,
    completed_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_upload_session_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
