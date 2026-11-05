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
import org.jspecify.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Guesses a file's role, instruments and desk from its name and extension, e.g. {@code Trumpet 1.pdf}, {@code Trumpet 1}
 * and {@code Tp1.pdf} become parts for the trumpet, desk 1; {@code Trumpet 1-2} a part for trumpet, desk 1–2;
 * {@code Flöjt & Piccolo} a part for flute and piccolo; {@code Alt/Tenor sax} a part for alto and tenor saxophone;
 * {@code Partitur.pdf} the score and {@code Manus.docx} a source. The instruments are recognised by their Swedish and
 * English names and their abbreviations; within each part of the name the longest match wins, so {@code Bastrombon} is
 * a bass trombone, not a trombone. The editor corrects the guess.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class FileGuesser {

    private static final Set<String> SOURCE_EXTENSIONS = Set.of("docx", "odt", "rtf", "txt", "musicxml", "xml", "mxl",
            "mscz", "mscx", "sib", "mus", "musx", "dorico");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mid", "midi");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "tif", "tiff");
    private static final Set<String> SCORE_WORDS = Set.of("partitur", "score", "fullscore", "full score", "dirigent", "conductor");
    private static final Set<String> MANUSCRIPT_WORDS = Set.of("manus", "manuskript", "manuscript", "script");
    private static final Set<String> SCAN_WORDS = Set.of("scan", "skanning", "skannad", "scanned", "inskannad");
    /**
     * What separates the instruments of a part shared by several: "Flöjt &amp; Piccolo", "Alt/Tenor sax", "Fl + Picc".
     */
    private static final Pattern SEPARATORS = Pattern.compile("\\s*(?:/|&|\\+|,|\\soch\\s|\\sand\\s)\\s*");
    private static final Pattern DESK_RANGE = Pattern.compile("(?<![\\p{L}\\d])(\\d{1,2})\\s*(?:[-–&+/]|och|and)\\s*(\\d{1,2})(?!\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESK = Pattern.compile("(?<=[\\p{L}\\s_])(\\d{1,2})\\s*$");

    /**
     * Pitches as written in file names, English and Swedish: Swedish B is B flat and H is B; Ess is E flat. "Db" and "As"
     * are left out, being abbreviations of double bass and alto saxophone.
     */
    private static final Map<String, Pitch> PITCHES = Map.ofEntries(
            Map.entry("c", Pitch.C), Map.entry("des", Pitch.D_FLAT), Map.entry("dess", Pitch.D_FLAT), Map.entry("d", Pitch.D),
            Map.entry("eb", Pitch.E_FLAT), Map.entry("es", Pitch.E_FLAT), Map.entry("ess", Pitch.E_FLAT), Map.entry("e", Pitch.E),
            Map.entry("f", Pitch.F), Map.entry("f#", Pitch.F_SHARP), Map.entry("fiss", Pitch.F_SHARP), Map.entry("g", Pitch.G),
            Map.entry("ab", Pitch.A_FLAT), Map.entry("ass", Pitch.A_FLAT), Map.entry("a", Pitch.A), Map.entry("bb", Pitch.B_FLAT),
            Map.entry("b", Pitch.B_FLAT), Map.entry("h", Pitch.B));

    private FileGuesser() {
    }

    /**
     * The pitch a file name says the part is written in, e.g. "Trumpet in Bb", "Klarinett B", "Horn F" or "Altsax Ess",
     * or null.
     */
    static @Nullable Pitch pitch(final String name) {
        final String normalized = normalize(title(name).replace("\u266D", "b").replace("\u266F", "#"));

        for (final String word : normalized.strip().split(" ")) {
            final Pitch pitch = PITCHES.get(word);

            if (pitch != null) {
                return pitch;
            }
        }
        return null;
    }

    /**
     * The name in lower case, with separators and the extension's dot as spaces, and letters split from digits.
     */
    static String normalize(final String name) {
        final String lower = Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        return (" " + lower.replaceAll("[_\\-–.,()\\[\\]+&/]", " ")
                .replaceAll("(?<=\\p{L})(?=\\d)|(?<=\\d)(?=\\p{L})", " ")
                .replaceAll("\\s+", " ").strip() + " ");
    }

    private static boolean containsWord(final String normalized, final String word) {
        return normalized.contains(" " + word + " ");
    }

    /**
     * The name without its extension, as a file's default title.
     */
    public static String title(final String name) {
        final int dot = name.lastIndexOf('.');
        final String title = (dot > 0 ? name.substring(0, dot) : name).strip();

        return title.isEmpty() ? name : title;
    }

    /**
     * The instrument a piece of a name names, the longest match first, or null.
     */
    private static @Nullable Instrument instrument(final String text, final List<Instrument> instruments) {
        final String normalized = normalize(text);
        Instrument best = null;
        int bestLength = 0;

        for (final Instrument instrument : instruments) {
            final List<String> names = new ArrayList<>(List.of(instrument.getNameSv(), instrument.getNameEn()));

            names.addAll(instrument.abbreviationList());
            for (final String candidate : names) {
                final String word = normalize(candidate).strip();

                if (!word.isEmpty() && word.length() > bestLength && containsWord(normalized, word)) {
                    best = instrument;
                    bestLength = word.length();
                }
            }
        }
        return best;
    }

    /**
     * The instruments a file name names, in order. Each piece between separators names one; a single word before a
     * last piece of several words borrows its last word, so that "Alt/Tenor sax" is alto and tenor saxophone.
     */
    static List<Instrument> instruments(final String name, final List<Instrument> instruments) {
        final String[] pieces = SEPARATORS.split(title(name).replaceAll("(?<=\\d)\\s*[-–]\\s*(?=\\d)", "–"));
        final String[] lastWords = normalize(pieces[pieces.length - 1]).strip().split(" ");
        final String suffix = lastWords.length > 1 ? lastWords[lastWords.length - 1] : null;
        final List<Instrument> found = new ArrayList<>();

        for (final String piece : pieces) {
            final boolean single = normalize(piece).strip().split(" ").length == 1;
            Instrument instrument = suffix != null && single ? instrument(piece + " " + suffix, instruments) : null;

            if (instrument == null) {
                instrument = instrument(piece, instruments);
            }
            if (instrument != null && !found.contains(instrument)) {
                found.add(instrument);
            }
        }
        return found;
    }

    /**
     * The desk or voice a file name gives, e.g. "1" for "Trumpet 1" or "1–2" for "Trumpet 1-2", or null.
     */
    static @Nullable String desk(final String name) {
        final String title = title(name);
        final Matcher range = DESK_RANGE.matcher(title);

        if (range.find()) {
            return range.group(1) + "–" + range.group(2);
        }

        final Matcher single = DESK.matcher(title.replaceAll("(?<=\\p{L})(?=\\d)", " "));

        return single.find() ? single.group(1) : null;
    }

    /**
     * The guess for a file: its role, its instruments and desk for a part, and its title.
     *
     * @param kind  what the version belongs to
     * @param roles the configured roles; the defaults are found by their fixed ids, else the first role is used
     */
    public static Guess guess(final String name, final String extension, final MaterialKind kind, final List<FileRole> roles,
                              final List<Instrument> instruments) {
        final Map<UUID, FileRole> byId = roles.stream().collect(Collectors.toMap(FileRole::getUuid, Function.identity()));
        final Function<UUID, FileRole> role = id -> byId.getOrDefault(id, roles.getFirst());
        final String normalized = normalize(title(name));
        final String title = title(name);

        if (AUDIO_EXTENSIONS.contains(extension)) {
            return new Guess(role.apply(FileRole.REFERENCE_AUDIO), List.of(), null, null, title);
        }
        if (SOURCE_EXTENSIONS.contains(extension)) {
            return new Guess(role.apply(FileRole.SOURCE), List.of(), null, null, title);
        }
        if (IMAGE_EXTENSIONS.contains(extension) || SCAN_WORDS.stream().anyMatch(word -> containsWord(normalized, word))) {
            return new Guess(role.apply(FileRole.SCAN), List.of(), null, null, title);
        }
        if (SCORE_WORDS.stream().anyMatch(word -> containsWord(normalized, word))) {
            return new Guess(role.apply(FileRole.SCORE), List.of(), null, null, title);
        }
        if (MANUSCRIPT_WORDS.stream().anyMatch(word -> containsWord(normalized, word))) {
            return new Guess(role.apply(FileRole.MANUSCRIPT), List.of(), null, null, title);
        }
        if (kind == MaterialKind.ARRANGEMENT) {
            final List<Instrument> found = instruments(name, instruments);

            return found.isEmpty()
                    ? new Guess(role.apply(FileRole.SCORE), List.of(), null, null, title)
                    : new Guess(role.apply(FileRole.PART), found, desk(name), transposition(name, found.getFirst()), title);
        }
        return new Guess(role.apply(FileRole.MANUSCRIPT), List.of(), null, null, title);
    }

    /**
     * The pitch named in the file name, with the instrument's octave, else the instrument's default transposition.
     */
    private static FileDtos.Transposition transposition(final String name, final Instrument instrument) {
        final Pitch pitch = pitch(name);

        return new FileDtos.Transposition(pitch == null ? instrument.getDefaultPitch() : pitch, instrument.getDefaultOctave());
    }

    /**
     * A guess for a file.
     */
    public record Guess(FileRole role, List<Instrument> instruments, @Nullable String desk,
                        FileDtos.@Nullable Transposition transposition, String title) {
    }
}
