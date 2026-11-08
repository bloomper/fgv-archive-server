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

package nu.fgv.archive.server.util.data;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generated sample files, never real material: PDFs with a title, version and page number on every page and
 * searchable Swedish text, scores and parts with staves, a scanned page without a text layer, and short MusicXML, DOCX
 * and MIDI files; and a file whose type the archive refuses. The integration tests use the same files.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class SampleFiles {

    /**
     * Words that only the sample manuscripts' text holds, for searching.
     */
    public static final String SEARCH_WORD = "kungamantel";
    private static final List<String> LINES = List.of(
            "KUNGEN: Vem har stulit min kungamantel? Jag fryser om öronen!",
            "DROTTNINGEN: Ers majestät, den hänger på tork efter balen.",
            "NARREN: Å, vilken förtjusande förvirring på slottet i år.",
            "HJÄLTEN: Jag svär vid min mors kålsoppa att rädda prinsessan.",
            "PRINSESSAN: Räddningen får vänta, jag övar på min duett.",
            "KÖREN: Tra-la-la, här kommer ännu en scen med sång!",
            "BERÄTTAREN: Och så gick det till, när ingen trodde det.",
            "SKURKEN: Ha! Ni får aldrig veta var ölförrådet är gömt.");

    private SampleFiles() {
    }

    private static PDType1Font font(final Standard14Fonts.FontName name) {
        return new PDType1Font(name);
    }

    private static void text(final PDPageContentStream content, final PDType1Font font, final float size, final float x,
                             final float y, final String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    /**
     * A manuscript: a title page and scenes of dialogue, with the title, version and page number on every page.
     */
    public static void manuscript(final Path target, final String title, final String version, final int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            final PDType1Font bold = font(Standard14Fonts.FontName.HELVETICA_BOLD);
            final PDType1Font regular = font(Standard14Fonts.FontName.HELVETICA);

            for (int page = 1; page <= pages; page++) {
                final PDPage pdPage = new PDPage(PDRectangle.A4);

                document.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                    text(content, regular, 9, 50, 810, "%s - %s".formatted(title, version));
                    text(content, regular, 9, 520, 30, "Sida " + page);
                    if (page == 1) {
                        text(content, bold, 28, 50, 600, title);
                        text(content, regular, 16, 50, 560, "Manus, " + version);
                        text(content, regular, 12, 50, 530, "Exempeldata, genererad av Spexarkiv");
                    } else {
                        text(content, bold, 14, 50, 760, "Akt %d, scen %d".formatted((page - 2) / 10 + 1, (page - 2) % 10 + 1));
                        for (int line = 0; line < 24; line++) {
                            text(content, regular, 11, 50, 720 - line * 26, LINES.get((page + line) % LINES.size()));
                        }
                    }
                }
            }
            document.save(target.toFile());
        }
    }

    /**
     * A score or a part: staves on every page, with the title and the instrument.
     *
     * @param staves staves per page, e.g. one per instrument for a score
     */
    public static void music(final Path target, final String title, final String label, final int pages, final int staves) throws IOException {
        try (PDDocument document = new PDDocument()) {
            final PDType1Font bold = font(Standard14Fonts.FontName.HELVETICA_BOLD);
            final PDType1Font regular = font(Standard14Fonts.FontName.HELVETICA);

            for (int page = 1; page <= pages; page++) {
                final PDPage pdPage = new PDPage(PDRectangle.A4);

                document.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                    text(content, bold, 18, 50, 790, title);
                    text(content, regular, 12, 50, 770, label);
                    text(content, regular, 9, 520, 30, "Sida " + page);
                    content.setLineWidth(0.6f);

                    final float gap = 680f / Math.max(staves, 1);

                    for (int staff = 0; staff < staves; staff++) {
                        final float top = 740 - staff * gap;

                        for (int line = 0; line < 5; line++) {
                            content.moveTo(50, top - line * 6);
                            content.lineTo(545, top - line * 6);
                        }
                        content.moveTo(50, top);
                        content.lineTo(50, top - 24);
                        for (int bar = 1; bar <= 4; bar++) {
                            content.moveTo(50 + bar * 123.75f, top);
                            content.lineTo(50 + bar * 123.75f, top - 24);
                        }
                        content.stroke();
                        for (int note = 0; note < 16; note++) {
                            content.addRect(60 + note * 30.5f, top - 3 - ((note * 7 + staff) % 8) * 3, 5, 4);
                        }
                        content.fill();
                    }
                }
            }
            document.save(target.toFile());
        }
    }

    /**
     * A scanned page: one image, no text layer, so that it is not searchable.
     */
    public static void scan(final Path target, final String title) throws IOException {
        final BufferedImage image = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = image.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(244, 238, 222));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(new Color(60, 50, 40));
            graphics.setFont(new Font(Font.SERIF, Font.ITALIC, 64));
            graphics.drawString(title, 120, 300);
            graphics.setFont(new Font(Font.SERIF, Font.PLAIN, 36));
            for (int line = 0; line < 30; line++) {
                graphics.drawLine(120, 420 + line * 42, 1100 - (line * 37) % 300, 420 + line * 42 + (line % 3));
            }
        } finally {
            graphics.dispose();
        }
        try (PDDocument document = new PDDocument()) {
            final PDPage page = new PDPage(PDRectangle.A4);
            final PDImageXObject picture = LosslessFactory.createFromImage(document, image);

            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(picture, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            document.save(target.toFile());
        }
    }

    /**
     * A short MusicXML score of a few notes.
     */
    public static void musicXml(final Path target, final String title) throws IOException {
        final StringBuilder notes = new StringBuilder();

        for (final String step : List.of("C", "D", "E", "F", "G", "A", "B", "C")) {
            notes.append("<note><pitch><step>").append(step).append("</step><octave>4</octave></pitch><duration>1</duration><type>quarter</type></note>");
        }
        Files.writeString(target, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
                <score-partwise version="4.0">
                  <work><work-title>%s</work-title></work>
                  <part-list><score-part id="P1"><part-name>Sång</part-name></score-part></part-list>
                  <part id="P1">
                    <measure number="1"><attributes><divisions>1</divisions><time><beats>4</beats><beat-type>4</beat-type></time>
                      <clef><sign>G</sign><line>2</line></clef></attributes>%s</measure>
                  </part>
                </score-partwise>
                """.formatted(title.replace("&", "&amp;").replace("<", "&lt;"), notes), StandardCharsets.UTF_8);
    }

    /**
     * A DOCX with the manuscript's opening lines, as the source it was written in.
     */
    public static void docx(final Path target, final String title) throws IOException {
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(target)) {
            document.createParagraph().createRun().setText(title);
            for (final String line : LINES) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(out);
        }
    }

    /**
     * A few seconds of a scale as MIDI.
     */
    public static void midi(final Path target) throws IOException {
        try {
            final Sequence sequence = new Sequence(Sequence.PPQ, 4);
            final Track track = sequence.createTrack();
            int tick = 0;

            for (final int note : new int[]{60, 62, 64, 65, 67, 69, 71, 72}) {
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, note, 90), tick));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, note, 0), tick + 4));
                tick += 4;
            }
            MidiSystem.write(sequence, 0, target.toFile());
        } catch (final InvalidMidiDataException e) {
            throw new IOException(e);
        }
    }

    /**
     * A Windows executable's first bytes: a type the archive refuses, for testing rejection.
     */
    public static byte[] disallowed() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] header = new byte[256];

        header[0] = 'M';
        header[1] = 'Z';
        header[0x3c] = (byte) 0x80;
        header[0x80] = 'P';
        header[0x81] = 'E';
        out.writeBytes(header);
        return out.toByteArray();
    }
}
