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

package nu.fgv.archive.server.processing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads files for the archive: a PDF's page count and text page by page, and a first-page preview, with PDFBox; the
 * text of other documents with Tika; previews of images with ImageIO.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ContentReader {

    /**
     * The width of a preview, in pixels; enough for a card on a high-density screen.
     */
    static final int PREVIEW_WIDTH = 600;
    /**
     * Text beyond this many characters per page or document is left out of the index.
     */
    static final int MAX_TEXT = 200_000;

    private final Tika tika = new Tika();

    public ContentReader() {
        tika.setMaxStringLength(MAX_TEXT);
    }

    private static String clip(final String text) {
        final String trimmed = text.replace('\u0000', ' ').strip();

        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
    }

    private static BufferedImage scale(final BufferedImage image) {
        if (image.getWidth() <= PREVIEW_WIDTH) {
            return image;
        }

        final int height = Math.max(1, Math.round(image.getHeight() * (PREVIEW_WIDTH / (float) image.getWidth())));
        final BufferedImage scaled = new BufferedImage(PREVIEW_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = scaled.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, PREVIEW_WIDTH, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * Reads a PDF: its pages' text and, when asked, a preview of its first page.
     *
     * @param preview where to write the preview as PNG, or null for none
     */
    public PdfContent pdf(final Path file, final Path preview) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(file.toFile()))) {
            final int pages = document.getNumberOfPages();
            final List<String> texts = new ArrayList<>(pages);
            final PDFTextStripper stripper = new PDFTextStripper();

            stripper.setSortByPosition(true);
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                texts.add(clip(stripper.getText(document)));
            }

            boolean rendered = false;

            if (pages > 0) {
                final PDFRenderer renderer = new PDFRenderer(document);
                final float width = document.getPage(0).getCropBox().getWidth();
                final float scale = width <= 0 ? 1f : PREVIEW_WIDTH / width;
                final BufferedImage image = renderer.renderImage(0, scale, ImageType.RGB);

                Files.createDirectories(preview.getParent());
                rendered = ImageIO.write(image, "png", preview.toFile());
            }
            return new PdfContent(pages, texts, rendered);
        }
    }

    /**
     * The text of a document that is not a PDF, e.g. DOCX, ODT, RTF, TXT or MusicXML.
     */
    public String text(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return clip(tika.parseToString(in));
        } catch (final TikaException e) {
            throw new IOException("Could not read the text: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a scaled-down copy of an image as a PNG preview.
     *
     * @return false when ImageIO cannot read the image
     */
    public boolean imagePreview(final Path file, final Path preview) throws IOException {
        final BufferedImage image = ImageIO.read(file.toFile());

        if (image == null) {
            return false;
        }
        Files.createDirectories(preview.getParent());
        return ImageIO.write(scale(image), "png", preview.toFile());
    }

    /**
     * What a PDF holds.
     *
     * @param texts    each page's text, from the first page
     * @param rendered whether the first-page preview was written
     */
    public record PdfContent(int pages, List<String> texts, boolean rendered) {
    }
}
