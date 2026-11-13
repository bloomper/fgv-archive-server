-- Spexipedia is the source of spex, numbers, characters and, through tasks in its instrument categories, instruments.
-- The archive keeps copies from its change feed. Numbers keep their ids, so arrangements keep pointing at them.
ALTER TABLE spex
    ADD COLUMN retired BIT NOT NULL DEFAULT 0;

-- Spexipedia orders numbers by act, then position in the act
ALTER TABLE number
    ADD COLUMN act     INT NOT NULL DEFAULT 1 AFTER spex_id,
    ADD COLUMN retired BIT NOT NULL DEFAULT 0,
    DROP INDEX idx_number_spex,
    ADD INDEX idx_number_spex (spex_id, act, position);

CREATE TABLE spex_character
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    uuid       CHAR(36)     NOT NULL,
    spex_id    CHAR(36)     NOT NULL,
    name       VARCHAR(255) NOT NULL,
    position   INT          NOT NULL,
    retired    BIT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_spex_character_uuid UNIQUE (uuid),
    INDEX idx_spex_character_spex (spex_id, position)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- An instrument from Spexipedia is a task in one of its instrument categories; the others are the archive's own.
-- One that leaves those categories, or whose task is retired, is retired here: kept on material, not offered anew.
ALTER TABLE instrument
    ADD COLUMN task_id CHAR(36) NULL,
    ADD COLUMN retired BIT      NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_instrument_task UNIQUE (task_id);

CREATE TABLE spexipedia_task_category
(
    id          CHAR(36)     NOT NULL,
    name        VARCHAR(255) NOT NULL,
    instruments BIT          NOT NULL DEFAULT 0,
    retired     BIT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE spexipedia_task
(
    id          CHAR(36)     NOT NULL,
    name        VARCHAR(255) NOT NULL,
    category_id CHAR(36)     NULL,
    retired     BIT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_spexipedia_task_category (category_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- Category names by id, for the spex copied here
CREATE TABLE spexipedia_category
(
    id   CHAR(36)     NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- How far the archive has read Spexipedia's change feed: one row
CREATE TABLE spexipedia_sync
(
    id             TINYINT       NOT NULL,
    cursor_value   VARCHAR(64)   NULL,
    last_pulled_at DATETIME(6)   NULL,
    last_success   DATETIME(6)   NULL,
    last_error     VARCHAR(1000) NULL,
    applied        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO spexipedia_sync (id) VALUES (1);
