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

import nu.fgv.archive.server.util.data.SampleFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDFs are read page by page with a first-page preview; other documents' text is read with Tika.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ContentReaderTest {

    private final ContentReader reader = new ContentReader();
    @TempDir
    private Path folder;

    @Test
    void readsAPdfsPagesAndRendersItsFirstPage() throws IOException {
        final Path pdf = folder.resolve("manus.pdf");
        final Path preview = folder.resolve("previews/manus.png");

        SampleFiles.manuscript(pdf, "Cleopatra", "Original 1987", 4);

        final ContentReader.PdfContent content = reader.pdf(pdf, preview);
        final BufferedImage image = ImageIO.read(preview.toFile());

        assertThat(content.pages()).isEqualTo(4);
        assertThat(content.texts()).hasSize(4);
        assertThat(content.texts().get(0)).contains("Cleopatra").contains("Sida 1");
        assertThat(content.texts().get(2)).contains(SampleFiles.SEARCH_WORD).contains("Sida 3");
        assertThat(content.rendered()).isTrue();
        assertThat(image.getWidth()).isEqualTo(ContentReader.PREVIEW_WIDTH);
    }

    @Test
    void aScanHasNoText() throws IOException {
        final Path pdf = folder.resolve("scan.pdf");

        SampleFiles.scan(pdf, "Handskrivet");

        final ContentReader.PdfContent content = reader.pdf(pdf, folder.resolve("scan.png"));

        assertThat(content.pages()).isEqualTo(1);
        assertThat(content.texts().getFirst()).isBlank();
    }

    @Test
    void readsTheTextOfADocx() throws IOException {
        final Path docx = folder.resolve("manus.docx");

        SampleFiles.docx(docx, "Manus");

        assertThat(reader.text(docx)).contains(SampleFiles.SEARCH_WORD);
    }

    @Test
    void aLongNarrowPageMakesASmallPreview() {
        final float scale = ContentReader.previewScale(10, 100_000);

        assertThat(100_000 * scale).isLessThanOrEqualTo(ContentReader.PREVIEW_MAX_HEIGHT);
        assertThat(ContentReader.previewScale(100_000, 100_000) * 100_000 * ContentReader.previewScale(100_000, 100_000) * 100_000)
                .isLessThanOrEqualTo(ContentReader.PREVIEW_MAX_PIXELS);
        assertThat(ContentReader.previewScale(0, 10)).isEqualTo(1f);
    }

    @Test
    void anImageDeclaringHugeDimensionsIsRefusedBeforeItIsDecoded() throws IOException {
        final Path png = folder.resolve("bomb.png");

        Files.write(png, pngHeader(50_000, 50_000));

        assertThatThrownBy(() -> reader.imagePreview(png, folder.resolve("bomb-preview.png")))
                .isInstanceOf(IOException.class).hasMessageContaining("too large");
    }

    @Test
    void aLargeImageIsReadSubsampled() throws IOException {
        final Path png = folder.resolve("large.png");

        ImageIO.write(new BufferedImage(6000, 6000, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

        assertThat(ContentReader.readBounded(png).getWidth()).isLessThan(6000);
        assertThat(reader.imagePreview(png, folder.resolve("large-preview.png"))).isTrue();
        assertThat(ImageIO.read(folder.resolve("large-preview.png").toFile()).getWidth()).isEqualTo(ContentReader.PREVIEW_WIDTH);
    }

    /**
     * The start of a PNG: its signature and a header chunk declaring the size, without any image data.
     */
    private static byte[] pngHeader(final int width, final int height) {
        final ByteBuffer ihdr = ByteBuffer.allocate(17).put("IHDR".getBytes(StandardCharsets.US_ASCII))
                .putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        final CRC32 crc = new CRC32();

        crc.update(ihdr.array());
        return ByteBuffer.allocate(8 + 4 + 17 + 4)
                .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})
                .putInt(13).put(ihdr.array()).putInt((int) crc.getValue()).array();
    }
}
