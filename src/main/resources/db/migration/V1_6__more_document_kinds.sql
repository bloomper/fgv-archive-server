-- More document kinds (spec 4.1) for what the spex folders' committees kept (content-import.md, 2.1): press cuttings,
-- the handover notes of each post, tours, technique, records, the spex book and make-up. Övrigt stays last.
UPDATE document_kind SET position = 11 WHERE uuid = '00000000-0000-7000-8000-00000000e004';

INSERT INTO document_kind (uuid, name_sv, name_en, aliases, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000e005', 'Pressklipp', 'Press cuttings', 'pressklipp\nrecension\nrecensioner\nskrivet om spexet\npress', 4, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e006', 'Testamente', 'Handover notes', 'testamente\ntestamenten\ntestament\növerlämning', 5, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e007', 'Turné', 'Tour', 'turné\nturne\nturnéer\nturneer\nturnéchef', 6, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e008', 'Teknik', 'Technique', 'teknik\nljus\nljud\nteknikdetaljen\nljusplot', 7, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e009', 'Skiva', 'Record', 'skiva\nskivor\nskivkommitté\nskivomslag', 8, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e010', 'Spexbok', 'Spex book', 'spexbok\nspexboken\ntill spexbok\nsångbok\nsånghäfte', 9, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000e011', 'Sminkning', 'Make-up', 'sminkning\nsmink\nsminkningar', 10, 'system', NOW(6));
