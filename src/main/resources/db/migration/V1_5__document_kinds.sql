-- Document kinds (spec 4.1): manuscripts, programmes, synopses and whatever admins add; a manuscript record is a
-- document of a kind. Kinds are data, never a list in code.
CREATE TABLE document_kind
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    uuid             CHAR(36)      NOT NULL,
    name_sv          VARCHAR(100)  NOT NULL,
    name_en          VARCHAR(100)  NOT NULL,
    -- Other spellings the import recognises, one per line
    aliases          VARCHAR(2000) NOT NULL DEFAULT '',
    position         INT           NOT NULL,
    retired          BIT           NOT NULL DEFAULT 0,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_by       VARCHAR(255)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    last_modified_by VARCHAR(255)  NULL,
    last_modified_at DATETIME(6)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_document_kind_uuid UNIQUE (uuid)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO document_kind (uuid, name_sv, name_en, aliases, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000e001', 'Manus', 'Manuscript', 'manus\nmanuskript\nmanuscript\nregimanus\nensemblemanus', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e002', 'Program', 'Programme', 'program\nprogramblad\nprogramme\nprogram', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e003', 'Synopsis', 'Synopsis', 'synopsis\nhandling\nsammanfattning\nmanussammanfattning\nhandlingsutdrag', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e004', 'Övrigt', 'Other', 'övrigt\nannat\nother', 4, 'system', NOW(6));

ALTER TABLE manuscript
    ADD COLUMN kind_id BIGINT NULL AFTER spex_id;

UPDATE manuscript SET kind_id = (SELECT id FROM document_kind WHERE uuid = '00000000-0000-7000-8000-00000000e001');

ALTER TABLE manuscript
    MODIFY kind_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_manuscript_kind FOREIGN KEY (kind_id) REFERENCES document_kind (id);
