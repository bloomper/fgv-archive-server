-- The file roles and instruments a new archive starts with, with fixed ids so that the file name guessing finds the
-- default roles. Admins rename, reorder, add and delete them.
INSERT INTO file_role (uuid, name_sv, name_en, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000d001', 'Manus', 'Manuscript', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000d002', 'Partitur', 'Score', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000d003', 'Stämma', 'Part', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000d004', 'Källfil', 'Source', 4, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000d005', 'Referensljud', 'Reference audio', 5, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000d006', 'Skanning', 'Scan', 6, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000d007', 'Övrigt', 'Other', 7, 'system', NOW(6));

INSERT INTO instrument (uuid, name_sv, name_en, instrument_group, abbreviations, position, created_by, created_at)
VALUES ('00000000-0000-7000-8000-00000000c001', 'Flöjt', 'Flute', 'WOODWIND', 'fl,flt,tvärflöjt', 1, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c002', 'Piccolaflöjt', 'Piccolo', 'WOODWIND', 'picc,piccolo,pic', 2, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c003', 'Oboe', 'Oboe', 'WOODWIND', 'ob', 3, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c004', 'Klarinett', 'Clarinet', 'WOODWIND', 'cl,kl,klar,clar,cla', 4, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c005', 'Basklarinett', 'Bass clarinet', 'WOODWIND', 'bcl,bkl,bass cl', 5, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c006', 'Fagott', 'Bassoon', 'WOODWIND', 'fg,bsn,fag', 6, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c007', 'Sopransaxofon', 'Soprano saxophone', 'WOODWIND', 'ss,sopsax,sopransax,soprano sax,sopranosax', 7, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c008', 'Altsaxofon', 'Alto saxophone', 'WOODWIND', 'as,asx,altsax,alto sax,alt sax,altosax,asax', 8, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c009', 'Tenorsaxofon', 'Tenor saxophone', 'WOODWIND', 'ts,tsx,tenorsax,tenor sax,tsax', 9, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c00a', 'Barytonsaxofon', 'Baritone saxophone', 'WOODWIND', 'bs,bsx,barsax,bari,bari sax,barytonsax,baritone sax', 10, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c00b', 'Trumpet', 'Trumpet', 'BRASS', 'tp,tpt,trp,tr,trumpeter', 11, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c00c', 'Valthorn', 'Horn', 'BRASS', 'hn,hr,horn,french horn', 12, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c00d', 'Trombon', 'Trombone', 'BRASS', 'tb,tbn,trb,trbn,basun', 13, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c00e', 'Bastrombon', 'Bass trombone', 'BRASS', 'btb,btbn,btrb,bass tbn', 14, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c00f', 'Tuba', 'Tuba', 'BRASS', 'tba', 15, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c010', 'Piano', 'Piano', 'RHYTHM', 'pno,pf,pia,flygel', 16, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c011', 'Keyboard', 'Keyboard', 'RHYTHM', 'kbd,keys,synt,synth,orgel,organ', 17, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c012', 'Gitarr', 'Guitar', 'RHYTHM', 'gtr,git,gt,elgitarr,el-gitarr', 18, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c013', 'Elbas', 'Bass guitar', 'RHYTHM', 'bas,bass,el-bas,elbass,electric bass,bg', 19, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c014', 'Trummor', 'Drums', 'RHYTHM', 'dr,drs,drums,trumset,drum set,drumset', 20, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c015', 'Slagverk', 'Percussion', 'RHYTHM', 'perc,slv,perk', 21, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c016', 'Violin', 'Violin', 'STRINGS', 'vl,vln,vn,fiol,vi', 22, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c017', 'Viola', 'Viola', 'STRINGS', 'vla,va,altfiol', 23, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c018', 'Cello', 'Cello', 'STRINGS', 'vc,vlc,vcl,violoncell', 24, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c019', 'Kontrabas', 'Double bass', 'STRINGS', 'cb,db,kb,kontrabas,contrabass,upright bass', 25, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c01a', 'Sopran', 'Soprano', 'VOICES', 'sop', 26, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c01b', 'Alt', 'Alto', 'VOICES', 'alto', 27, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c01c', 'Tenor', 'Tenor', 'VOICES', 'ten', 28, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c01d', 'Bas (sång)', 'Bass (voice)', 'VOICES', 'basstämma,bass voice', 29, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c01e', 'Sång', 'Vocals', 'VOICES', 'voc,vox,solo,vocal,sångstämma', 30, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c01f', 'Kör', 'Choir', 'VOICES', 'satb,chorus', 31, 'system', NOW(6)),
       ('00000000-0000-7000-8000-00000000c020', 'Övrigt', 'Other', 'OTHER', NULL, 32, 'system', NOW(6));

-- The pitch the parts of transposing instruments are written in, and the octave they sound in relative to it: tenor
-- saxophone sounds a ninth below its written notes (B flat, an octave down), piccolo an octave above, guitar and bass
-- an octave below
UPDATE instrument SET default_pitch = 'B_FLAT'
WHERE uuid IN ('00000000-0000-7000-8000-00000000c004', '00000000-0000-7000-8000-00000000c007', '00000000-0000-7000-8000-00000000c00b');
UPDATE instrument SET default_pitch = 'B_FLAT', default_octave = -1
WHERE uuid IN ('00000000-0000-7000-8000-00000000c005', '00000000-0000-7000-8000-00000000c009');
UPDATE instrument SET default_pitch = 'E_FLAT' WHERE uuid = '00000000-0000-7000-8000-00000000c008';
UPDATE instrument SET default_pitch = 'E_FLAT', default_octave = -1 WHERE uuid = '00000000-0000-7000-8000-00000000c00a';
UPDATE instrument SET default_pitch = 'F' WHERE uuid = '00000000-0000-7000-8000-00000000c00c';
UPDATE instrument SET default_octave = 1 WHERE uuid = '00000000-0000-7000-8000-00000000c002';
UPDATE instrument SET default_octave = -1
WHERE uuid IN ('00000000-0000-7000-8000-00000000c012', '00000000-0000-7000-8000-00000000c013', '00000000-0000-7000-8000-00000000c019');
