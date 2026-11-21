-- Ownership (fgv-infra-deploy docs/data-ownership.md): a number's credits are Spexipedia's, so the lyricist and composer
-- left from when the archive kept numbers go. Books (spex books, songbooks) are the archive's: a document of a kind
-- marked as a book has a volume, a year and a song list, and may be recorded before it is scanned. Spexipedia's own
-- books are gone; its numbers show the archive's. Nothing is live, so no data is carried over.

ALTER TABLE number
    DROP COLUMN lyricist,
    DROP COLUMN composer;

ALTER TABLE document_kind
    ADD COLUMN book BIT NOT NULL DEFAULT 0 AFTER aliases;

-- Spex book is a book kind; songbooks and anniversary books get kinds of their own
UPDATE document_kind SET book = 1, aliases = 'spexbok\nspexboken\ntill spexbok'
WHERE uuid = '00000000-0000-7000-8000-00000000e010';

INSERT INTO document_kind (uuid, name_sv, name_en, aliases, book, position, created_by, created_at)
SELECT '00000000-0000-7000-8000-00000000e020', 'Sångbok', 'Songbook', 'sångbok\nsånghäfte\nsångboken\nsongbook', 1,
       MAX(position) + 1, 'system', NOW(6)
FROM document_kind;
INSERT INTO document_kind (uuid, name_sv, name_en, aliases, book, position, created_by, created_at)
SELECT '00000000-0000-7000-8000-00000000e021', 'Jubileumsbok', 'Anniversary book', 'jubileumsbok\njubileumsboken\nanniversary book', 1,
       MAX(position) + 1, 'system', NOW(6)
FROM document_kind;

ALTER TABLE manuscript
    ADD COLUMN volume         INT         NULL AFTER title,
    -- A year, or a date as precise as known
    ADD COLUMN published_year VARCHAR(10) NULL AFTER volume;

-- A book's songs, in the book's order: a number from Spexipedia (any spex) or a free title, and whether the book has
-- only the words
CREATE TABLE book_song
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    manuscript_id BIGINT       NOT NULL,
    position      INT          NOT NULL,
    number_id     CHAR(36)     NULL,
    title         VARCHAR(255) NULL,
    text_only     BIT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_book_song_manuscript FOREIGN KEY (manuscript_id) REFERENCES manuscript (id) ON DELETE CASCADE,
    INDEX idx_book_song_manuscript (manuscript_id, position),
    INDEX idx_book_song_number (number_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
