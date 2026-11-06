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
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
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
    /**
     * The tallest a preview may be, in pixels, so that a long, narrow page cannot make a huge image.
     */
    static final int PREVIEW_MAX_HEIGHT = 1200;
    /**
     * The most pixels a rendered preview may have.
     */
    static final long PREVIEW_MAX_PIXELS = 2_000_000L;
    /**
     * The largest image read for a preview; bigger ones are refused before they are decoded.
     */
    static final long MAX_IMAGE_PIXELS = 100_000_000L;
    static final int MAX_IMAGE_SIDE = 30_000;
    /**
     * Memory a PDF may use while it is read; beyond it PDFBox caches in temporary files, up to the second limit.
     */
    static final long PDF_MEMORY = 32L * 1024 * 1024;
    static final long PDF_TEMP_FILES = 1024L * 1024 * 1024;

    private final Tika tika = new Tika();

    public ContentReader() {
        tika.setMaxStringLength(MAX_TEXT);
    }

    private static String clip(final String text) {
        final String trimmed = text.replace('\u0000', ' ').strip();

        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
    }

    /**
     * The scale that fits a page or image of this size in a preview: {@link #PREVIEW_WIDTH} wide unless that would make
     * it taller than {@link #PREVIEW_MAX_HEIGHT}, never more than {@link #PREVIEW_MAX_PIXELS}, and never enlarging
     * beyond twice the original.
     */
    static float previewScale(final double width, final double height) {
        if (!(width > 0) || !(height > 0)) {
            return 1f;
        }

        double scale = Math.min(PREVIEW_WIDTH / width, PREVIEW_MAX_HEIGHT / height);

        if (width * scale * height * scale > PREVIEW_MAX_PIXELS) {
            scale = Math.sqrt(PREVIEW_MAX_PIXELS / (width * height));
        }
        return (float) Math.min(scale, 2.0);
    }

    private static BufferedImage scale(final BufferedImage image) {
        final float factor = previewScale(image.getWidth(), image.getHeight());

        if (factor >= 1f) {
            return image;
        }

        final int width = Math.max(1, Math.round(image.getWidth() * factor));
        final int height = Math.max(1, Math.round(image.getHeight() * factor));
        final BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D graphics = scaled.createGraphics();

        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
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
        // Bounded memory: a PDF that needs more is cached in temporary files, and fails beyond that
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(file.toFile()),
                MemoryUsageSetting.setupMixed(PDF_MEMORY, PDF_TEMP_FILES).streamCache)) {
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
                // Rotated pages render with their sides swapped, so the scale depends on both
                final var box = document.getPage(0).getCropBox();
                final float scale = previewScale(box.getWidth(), box.getHeight());
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
        try (InputStream in = TikaInputStream.get(file)) {
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
        final BufferedImage image = readBounded(file);

        if (image == null) {
            return false;
        }
        Files.createDirectories(preview.getParent());
        return ImageIO.write(scale(image), "png", preview.toFile());
    }

    /**
     * Reads an image no larger than {@link #MAX_IMAGE_PIXELS}, decoding only every n-th pixel of a big one, so that a
     * small file declaring a huge image cannot exhaust memory. Null when no reader knows the format.
     *
     * @throws IOException when the image is too large
     */
    static BufferedImage readBounded(final Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            final Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);

            if (readers == null || !readers.hasNext()) {
                return null;
            }

            final ImageReader reader = readers.next();

            try {
                reader.setInput(in, true, true);

                final int width = reader.getWidth(0);
                final int height = reader.getHeight(0);

                if (width <= 0 || height <= 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                        || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IOException("The image is too large: %d × %d pixels".formatted(width, height));
                }

                final ImageReadParam param = reader.getDefaultReadParam();
                final int step = Math.max(1, Math.min(width / (2 * PREVIEW_WIDTH), height / (2 * PREVIEW_MAX_HEIGHT)));

                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
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
