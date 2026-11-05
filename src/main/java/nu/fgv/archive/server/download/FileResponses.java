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

package nu.fgv.archive.server.download;

import nu.fgv.archive.server.file.FileService.StoredFile;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * How files, previews and zips are answered, for users and share links alike. A file is answered with byte-range
 * support, so that pdf.js loads a large PDF page by page; Spring answers a {@code Range} request with
 * {@code 206 Partial Content}. Files are never sniffed or run by the browser.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
final class FileResponses {

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate();

    private FileResponses() {
    }

    static ResponseEntity<Resource> file(final StoredFile file, final boolean download) {
        if (!Files.isRegularFile(file.path())) {
            throw new ResourceNotFoundException("error.file.missing", file.uuid());
        }

        final ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.downloadName(), StandardCharsets.UTF_8).build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CACHE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .body(new FileSystemResource(file.path()));
    }

    static ResponseEntity<Resource> preview(final StoredFile file) {
        if (!file.hasPreview() || !Files.isRegularFile(file.preview())) {
            throw new ResourceNotFoundException("error.file.noPreview", file.uuid());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CACHE)
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.preview()));
    }

    /**
     * Streams a zip, stored rather than compressed: PDFs hardly shrink.
     */
    static ResponseEntity<StreamingResponseBody> zip(final DownloadService.ZipPlan plan) {
        final StreamingResponseBody body = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                zip.setLevel(0);
                for (final DownloadService.ZipItem item : plan.items()) {
                    if (Files.isRegularFile(item.file().path())) {
                        zip.putNextEntry(new ZipEntry(item.name()));
                        Files.copy(item.file().path(), zip);
                        zip.closeEntry();
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(plan.name(), StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
