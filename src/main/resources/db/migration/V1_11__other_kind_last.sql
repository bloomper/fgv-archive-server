-- Övrigt stays last (V1_6): the songbook and anniversary book kinds (V1_9) were added after it
UPDATE document_kind dk
    JOIN (SELECT MAX(position) + 1 AS last FROM document_kind) m
SET dk.position = m.last
WHERE dk.uuid = '00000000-0000-7000-8000-00000000e004';
