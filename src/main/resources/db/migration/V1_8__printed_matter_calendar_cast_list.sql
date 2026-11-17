-- More document kinds (spec 4.1): printed matter (tickets, banners, logos, the print files of published PR), the
-- calendar of a spex year, and the cast lists of the old programmes. Övrigt stays last.
UPDATE document_kind SET position = 14 WHERE uuid = '00000000-0000-7000-8000-00000000e004';

INSERT INTO document_kind (uuid, name_sv, name_en, aliases, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000e012', 'Trycksak', 'Printed matter', 'trycksak\ntrycksaker\npr\nbiljetter\nbanderoll\nbordsryttare\nlogga', 11, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e013', 'Kalendarium', 'Calendar', 'kalendarium\nkalender', 12, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e014', 'Rollista', 'Cast list', 'rollista\nrollistor\nkaraktärsbeskrivning\nkaraktärsbeskrivningar', 13, 'system', NOW(6));
