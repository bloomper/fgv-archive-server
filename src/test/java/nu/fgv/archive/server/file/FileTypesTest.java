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

package nu.fgv.archive.server.file;

import nu.fgv.archive.server.util.data.SampleFiles;
import nu.fgv.archive.server.util.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Files are accepted by their content, not their names.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class FileTypesTest {

    private final FileTypes types = new FileTypes();
    @TempDir
    private Path folder;

    @Test
    void acceptsTheArchivesFormats() throws IOException {
        final Path pdf = folder.resolve("Trumpet 1.pdf");
        final Path docx = folder.resolve("Manus.docx");
        final Path xml = folder.resolve("Melodi.musicxml");
        final Path midi = folder.resolve("Melodi.mid");
        final Path text = folder.resolve("Anteckningar.txt");

        SampleFiles.music(pdf, "Ouvertyr", "Trumpet 1", 1, 1);
        SampleFiles.docx(docx, "Manus");
        SampleFiles.musicXml(xml, "Melodi");
        SampleFiles.midi(midi);
        Files.writeString(text, "Repliker att stryka i andra akten.", StandardCharsets.UTF_8);

        assertThat(types.detect(pdf, "Trumpet 1.pdf")).isEqualTo(new FileTypes.Detected(FileTypes.PDF, "pdf"));
        assertThat(types.detect(docx, "Manus.docx")).isEqualTo(new FileTypes.Detected(FileTypes.DOCX, "docx"));
        assertThat(types.detect(xml, "Melodi.musicxml").extension()).isEqualTo("musicxml");
        assertThat(types.detect(midi, "Melodi.mid")).isEqualTo(new FileTypes.Detected(FileTypes.MIDI, "mid"));
        assertThat(types.detect(text, "Anteckningar.txt")).isEqualTo(new FileTypes.Detected(FileTypes.TEXT, "txt"));
        // The content decides: a PDF named .doc is stored as a PDF
        assertThat(types.detect(pdf, "Trumpet 1.doc")).isEqualTo(new FileTypes.Detected(FileTypes.PDF, "pdf"));
    }

    @Test
    void refusesOtherTypesWhateverTheirName() throws IOException {
        final Path program = folder.resolve("Partitur.pdf");

        Files.write(program, SampleFiles.disallowed());

        assertThatThrownBy(() -> types.detect(program, "Partitur.pdf")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> types.detect(program, "Partitur.sib")).isInstanceOf(BadRequestException.class);
    }
}
