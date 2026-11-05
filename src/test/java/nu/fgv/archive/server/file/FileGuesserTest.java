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

import nu.fgv.archive.server.material.MaterialKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roles and instruments are guessed from file names, in Swedish and English, with abbreviations.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class FileGuesserTest {

    private static final List<FileRole> ROLES = List.of(
            role(FileRole.MANUSCRIPT, "Manus"), role(FileRole.SCORE, "Partitur"), role(FileRole.PART, "Stämma"),
            role(FileRole.SOURCE, "Källfil"), role(FileRole.REFERENCE_AUDIO, "Referensljud"), role(FileRole.SCAN, "Skanning"),
            role(FileRole.OTHER, "Övrigt"));
    private static final List<Instrument> INSTRUMENTS = List.of(
            instrument("Trumpet", "Trumpet", "tp,tpt,trp"), instrument("Trombon", "Trombone", "tb,tbn,trb"),
            instrument("Bastrombon", "Bass trombone", "btb"), instrument("Altsaxofon", "Alto saxophone", "as,altsax,alto sax,alt sax"),
            instrument("Tenorsaxofon", "Tenor saxophone", "ts,tenor sax"), instrument("Trummor", "Drums", "dr,drums"),
            instrument("Elbas", "Bass guitar", "bas,bass"), instrument("Sång", "Vocals", "voc"), instrument("Alt", "Alto", "alto"),
            instrument("Flöjt", "Flute", "fl"), instrument("Piccolaflöjt", "Piccolo", "picc,piccolo"),
            instrument("Klarinett", "Clarinet", "kl"), instrument("Valthorn", "Horn", "hn,horn"));

    private static FileRole role(final UUID id, final String name) {
        final FileRole role = new FileRole();

        role.setUuid(id);
        role.setNameSv(name);
        role.setNameEn(name);
        return role;
    }

    private static Instrument instrument(final String sv, final String en, final String abbreviations) {
        final Instrument instrument = new Instrument();

        instrument.setNameSv(sv);
        instrument.setNameEn(en);
        instrument.setAbbreviations(abbreviations);
        instrument.setGroup(InstrumentGroup.OTHER);
        instrument.setDefaultPitch("Trumpet".equals(sv) || "Klarinett".equals(sv) ? Pitch.B_FLAT : "Valthorn".equals(sv) ? Pitch.F : Pitch.C);
        return instrument;
    }

    private static FileGuesser.Guess guess(final String name, final MaterialKind kind) {
        return FileGuesser.guess(name, FileTypes.extensionOf(name), kind, ROLES, INSTRUMENTS);
    }

    @ParameterizedTest
    @CsvSource({
            "Trumpet 1.pdf, Trumpet",
            "Trumpet 1, Trumpet",
            "Tp1.pdf, Trumpet",
            "TRP_2.pdf, Trumpet",
            "Trombone.pdf, Trombon",
            "Bastrombon.pdf, Bastrombon",
            "Bass trombone.pdf, Bastrombon",
            "Alto Sax 1.pdf, Altsaxofon",
            "05 Kärleksduetten - Trummor.pdf, Trummor",
            "Sång (melodi).pdf, Sång",
            "Elbas.pdf, Elbas"
    })
    void partsAreRecognisedByTheirInstrument(final String name, final String instrument) {
        final FileGuesser.Guess guess = guess(name, MaterialKind.ARRANGEMENT);

        assertThat(guess.role().getUuid()).isEqualTo(FileRole.PART);
        assertThat(guess.instruments()).extracting(Instrument::getNameSv).containsExactly(instrument);
    }

    @Test
    void aPartForSeveralInstrumentsCarriesThemAll() {
        assertThat(guess("Trumpet 1-2.pdf", MaterialKind.ARRANGEMENT).instruments()).extracting(Instrument::getNameSv).containsExactly("Trumpet");
        assertThat(guess("Trumpet 1-2.pdf", MaterialKind.ARRANGEMENT).desk()).isEqualTo("1–2");
        assertThat(guess("Trumpet 1 & 2.pdf", MaterialKind.ARRANGEMENT).desk()).isEqualTo("1–2");
        assertThat(guess("Trumpet 1 och 2.pdf", MaterialKind.ARRANGEMENT).desk()).isEqualTo("1–2");
        assertThat(guess("Trumpet 1+2.pdf", MaterialKind.ARRANGEMENT).desk()).isEqualTo("1–2");
        assertThat(guess("Tp1.pdf", MaterialKind.ARRANGEMENT).desk()).isEqualTo("1");
        assertThat(guess("Flöjt & Piccolo.pdf", MaterialKind.ARRANGEMENT).instruments()).extracting(Instrument::getNameSv)
                .containsExactly("Flöjt", "Piccolaflöjt");
        assertThat(guess("Alt/Tenor sax.pdf", MaterialKind.ARRANGEMENT).instruments()).extracting(Instrument::getNameSv)
                .containsExactly("Altsaxofon", "Tenorsaxofon");
    }

    @ParameterizedTest
    @CsvSource({
            "Trumpet in Bb.pdf, B_FLAT",
            "Trumpet 1.pdf, B_FLAT",
            "Trumpet i C.pdf, C",
            "Klarinett B.pdf, B_FLAT",
            "Klarinett H.pdf, B",
            "Altsax Ess.pdf, E_FLAT",
            "Altsax Eb.pdf, E_FLAT",
            "Horn F.pdf, F",
            "Valthorn.pdf, F",
            "Trombon.pdf, C"
    })
    void transpositionsAreReadFromTheNameElseTheInstrumentsDefault(final String name, final Pitch pitch) {
        assertThat(guess(name, MaterialKind.ARRANGEMENT).transposition()).isNotNull()
                .extracting(FileDtos.Transposition::pitch).isEqualTo(pitch);
    }

    @Test
    void scoresSourcesAudioAndScansByNameAndFormat() {
        assertThat(guess("Partitur.pdf", MaterialKind.ARRANGEMENT).role().getUuid()).isEqualTo(FileRole.SCORE);
        assertThat(guess("Full score.pdf", MaterialKind.ARRANGEMENT).role().getUuid()).isEqualTo(FileRole.SCORE);
        assertThat(guess("Kärleksduetten.pdf", MaterialKind.ARRANGEMENT).role().getUuid()).isEqualTo(FileRole.SCORE);
        assertThat(guess("Kärleksduetten.musicxml", MaterialKind.ARRANGEMENT).role().getUuid()).isEqualTo(FileRole.SOURCE);
        assertThat(guess("Manus.docx", MaterialKind.MANUSCRIPT).role().getUuid()).isEqualTo(FileRole.SOURCE);
        assertThat(guess("Melodi.mid", MaterialKind.ARRANGEMENT).role().getUuid()).isEqualTo(FileRole.REFERENCE_AUDIO);
        assertThat(guess("Sida 1.jpg", MaterialKind.MANUSCRIPT).role().getUuid()).isEqualTo(FileRole.SCAN);
        assertThat(guess("Inskannad trumpet.pdf", MaterialKind.ARRANGEMENT).role().getUuid()).isEqualTo(FileRole.SCAN);
        assertThat(guess("Cleopatra 1987.pdf", MaterialKind.MANUSCRIPT).role().getUuid()).isEqualTo(FileRole.MANUSCRIPT);
    }

    @Test
    void theTitleIsTheNameWithoutItsExtension() {
        assertThat(guess("Trumpet 1.pdf", MaterialKind.ARRANGEMENT).title()).isEqualTo("Trumpet 1");
        assertThat(FileGuesser.title(".pdf")).isEqualTo(".pdf");
    }

    @Test
    void aDeletedDefaultRoleFallsBackToTheFirst() {
        final FileGuesser.Guess guess = FileGuesser.guess("Partitur.pdf", "pdf", MaterialKind.ARRANGEMENT,
                List.of(role(UUID.randomUUID(), "Egen")), INSTRUMENTS);

        assertThat(guess.role().getNameSv()).isEqualTo("Egen");
    }
}
