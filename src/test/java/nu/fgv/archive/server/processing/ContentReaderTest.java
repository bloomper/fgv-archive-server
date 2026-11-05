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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
