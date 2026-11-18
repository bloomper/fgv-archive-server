-- A document may belong to no spex (spec 4.1): songbooks, jubilees, synopses of spex never staged, the templates of
-- the posts' testaments. It is listed under its document kind.
ALTER TABLE manuscript
    MODIFY spex_id CHAR(36) NULL;

CREATE INDEX idx_manuscript_kind ON manuscript (kind_id, trashed_at);
