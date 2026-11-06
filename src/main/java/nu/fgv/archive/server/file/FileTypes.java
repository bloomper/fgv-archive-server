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

import nu.fgv.archive.server.util.error.BadRequestException;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The types the archive accepts, checked by content with Apache Tika, never by name alone (product spec 4.3): PDF,
 * documents (DOCX, ODT, RTF, TXT), notation (MusicXML, MuseScore, Sibelius, Finale, Dorico), MIDI and images (JPEG, PNG,
 * TIFF). Notation formats that Tika does not know are accepted by their extension only when their content is a zip
 * archive, XML or unknown binary, never when it is something Tika recognises as another type.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class FileTypes {

    public static final String PDF = "application/pdf";
    public static final String OCTET_STREAM = "application/octet-stream";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String ODT = "application/vnd.oasis.opendocument.text";
    public static final String RTF = "application/rtf";
    public static final String TEXT = "text/plain";
    public static final String MUSICXML = "application/vnd.recordare.musicxml+xml";
    public static final String MIDI = "audio/midi";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String TIFF = "image/tiff";

    /**
     * Types Tika recognises, with the extension a file of the type gets when its name has none of the type's.
     */
    private static final Map<String, List<String>> KNOWN = Map.ofEntries(
            Map.entry(PDF, List.of("pdf")),
            Map.entry(DOCX, List.of("docx")),
            Map.entry("application/x-tika-ooxml", List.of("docx")),
            Map.entry(ODT, List.of("odt")),
            Map.entry(RTF, List.of("rtf")),
            Map.entry(TEXT, List.of("txt")),
            Map.entry(MUSICXML, List.of("musicxml", "xml")),
            Map.entry("application/vnd.recordare.musicxml", List.of("mxl")),
            Map.entry(MIDI, List.of("mid", "midi")),
            Map.entry(JPEG, List.of("jpg", "jpeg")),
            Map.entry(PNG, List.of("png")),
            Map.entry(TIFF, List.of("tif", "tiff"))
    );
    /**
     * Generic containers, accepted only with these extensions.
     */
    private static final Map<String, Set<String>> CONTAINERS = Map.of(
            "application/zip", Set.of("mxl", "mscz", "dorico"),
            "application/xml", Set.of("musicxml", "xml", "mscx"),
            OCTET_STREAM, Set.of("sib", "mus", "musx", "dorico")
    );
    private static final Map<String, String> CANONICAL = Map.of(
            "application/x-tika-ooxml", DOCX
    );
    private static final Set<String> TEXT_TYPES = Set.of(DOCX, ODT, RTF, TEXT, MUSICXML, "application/xml");
    private static final Set<String> IMAGE_TYPES = Set.of(JPEG, PNG, TIFF);
    private static final Set<String> INLINE_TYPES = Set.of(PDF, JPEG, PNG, TIFF, "image/webp");
    /** Binary formats a browser does not render, served with their own type. */
    private static final Set<String> OPAQUE_TYPES = Set.of(DOCX, ODT, MIDI, "application/zip");

    private final Tika tika = new Tika();

    public static String extensionOf(final String fileName) {
        final int dot = fileName.lastIndexOf('.');

        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether text can be extracted from files of this type with Tika (PDFs are read page by page with PDFBox).
     */
    public static boolean hasExtractableText(final String contentType) {
        return TEXT_TYPES.contains(contentType);
    }

    public static boolean isImage(final String contentType) {
        return IMAGE_TYPES.contains(contentType);
    }

    /**
     * Whether a file of this type may be shown in the browser: PDFs, for pdf.js, and raster images. Every other type is
     * only ever downloaded, since a browser could run what is in it (an XHTML script in XML, say).
     */
    public static boolean isInline(final String contentType) {
        return INLINE_TYPES.contains(contentType);
    }

    /**
     * The type a file is served with: its own for PDFs, images and binary office and audio formats, and
     * {@code application/octet-stream} for XML, text, RTF and notation formats, which a browser might otherwise render.
     */
    public static String servedType(final String contentType) {
        return INLINE_TYPES.contains(contentType) || OPAQUE_TYPES.contains(contentType) ? contentType : OCTET_STREAM;
    }

    /**
     * Detects the type of a file by its content, with its name as a hint.
     *
     * @throws BadRequestException when the type is not accepted
     */
    public Detected detect(final Path file, final String name) throws IOException {
        final String detected;

        // A file-backed TikaInputStream lets Tika open zips as such instead of guessing from a stream
        try (InputStream in = TikaInputStream.get(file)) {
            detected = tika.detect(in, name);
        }

        final String extension = extensionOf(name);
        final List<String> known = KNOWN.get(detected);

        if (known != null) {
            return new Detected(CANONICAL.getOrDefault(detected, detected), known.contains(extension) ? extension : known.getFirst());
        }

        final Set<String> container = CONTAINERS.get(detected);

        if (container != null && container.contains(extension)) {
            return new Detected(detected, extension);
        }
        throw new BadRequestException("error.file.type", name, detected);
    }

    /**
     * A file's accepted type and the extension it is stored with.
     */
    public record Detected(String contentType, String extension) {
    }
}
