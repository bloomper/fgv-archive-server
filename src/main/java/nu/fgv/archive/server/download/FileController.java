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

import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.FileService.StoredFile;
import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.file.Pitch;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

/**
 * Files, previews and zips for logged-in users, over plain HTTP, which GraphQL does not suit. The web streams them to
 * the browser with the user's token. A thin adapter: {@link FileService} and {@link DownloadService} decide access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class FileController {

    private final FileService fileService;
    private final DownloadService downloadService;

    public FileController(final FileService fileService, final DownloadService downloadService) {
        this.fileService = fileService;
        this.downloadService = downloadService;
    }

    /**
     * A file, to read in the page or, with {@code download=true}, as a download, which is counted and logged.
     */
    @GetMapping("/api/files/{id}")
    public ResponseEntity<Resource> file(@PathVariable final UUID id,
                                         @RequestParam(defaultValue = "false") final boolean download) {
        final StoredFile file = fileService.open(id);
        final ResponseEntity<Resource> response = FileResponses.file(file, download);

        if (download) {
            downloadService.record(List.of(file), null);
        }
        return response;
    }

    @GetMapping("/api/files/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable final UUID id) {
        return FileResponses.preview(fileService.open(id));
    }

    /**
     * Every file of a version.
     */
    @GetMapping("/api/versions/{id}/zip")
    public ResponseEntity<StreamingResponseBody> versionZip(@PathVariable final UUID id) {
        return zip(downloadService.versionZip(id, null));
    }

    /**
     * The parts of an arrangement version: all of them, or those of the given instruments or transpositions.
     */
    @GetMapping("/api/versions/{id}/parts/zip")
    public ResponseEntity<StreamingResponseBody> partsZip(@PathVariable final UUID id,
                                                          @RequestParam(required = false) final @Nullable List<UUID> instrumentIds,
                                                          @RequestParam(required = false) final @Nullable List<Pitch> transpositions) {
        return zip(downloadService.versionZip(id, new PartFilter(instrumentIds, transpositions)));
    }

    /**
     * The parts of the given instruments or transpositions (or all parts) across a spex' arrangements, in the order of
     * the show.
     */
    @GetMapping("/api/spex/{spexId}/parts/zip")
    public ResponseEntity<StreamingResponseBody> spexZip(@PathVariable final UUID spexId,
                                                         @RequestParam(required = false) final @Nullable List<UUID> instrumentIds,
                                                         @RequestParam(required = false) final @Nullable List<Pitch> transpositions) {
        return zip(downloadService.spexZip(spexId, new PartFilter(instrumentIds, transpositions)));
    }

    private ResponseEntity<StreamingResponseBody> zip(final DownloadService.ZipPlan plan) {
        final ResponseEntity<StreamingResponseBody> response = FileResponses.zip(plan);

        downloadService.record(plan.items().stream().map(DownloadService.ZipItem::file).toList(), plan.shareLinkId());
        return response;
    }
}
