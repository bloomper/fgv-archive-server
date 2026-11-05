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

import nu.fgv.archive.server.file.PartFilter;
import nu.fgv.archive.server.file.Pitch;
import nu.fgv.archive.server.share.ShareLinkService;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.UUID;

/**
 * What a share link opens, for anyone with its token, without logging in: its files, previews and zips. Every
 * download is logged against the link. Expired and revoked links answer {@code 410 Gone} with who made them; unknown
 * tokens {@code 404 Not Found}. A thin adapter: {@link ShareLinkService} decides what the link opens.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
@RequestMapping("/api/share/{token}")
public class ShareController {

    private final DownloadService downloadService;

    public ShareController(final DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> file(@PathVariable final String token, @PathVariable final UUID id,
                                         @RequestParam(defaultValue = "false") final boolean download) {
        final DownloadService.SharedFile shared = downloadService.sharedFile(token, id);
        final ResponseEntity<Resource> response = FileResponses.file(shared.file(), download);

        if (download) {
            downloadService.record(List.of(shared.file()), shared.shareLinkId());
        }
        return response;
    }

    @GetMapping("/files/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable final String token, @PathVariable final UUID id) {
        return FileResponses.preview(downloadService.sharedFile(token, id).file());
    }

    @GetMapping("/versions/{id}/zip")
    public ResponseEntity<StreamingResponseBody> versionZip(@PathVariable final String token, @PathVariable final UUID id) {
        return zip(downloadService.sharedVersionZip(token, id, null));
    }

    @GetMapping("/versions/{id}/parts/zip")
    public ResponseEntity<StreamingResponseBody> partsZip(@PathVariable final String token, @PathVariable final UUID id,
                                                          @RequestParam(required = false) final @Nullable List<UUID> instrumentIds,
                                                          @RequestParam(required = false) final @Nullable List<Pitch> transpositions) {
        return zip(downloadService.sharedVersionZip(token, id, new PartFilter(instrumentIds, transpositions)));
    }

    /**
     * For a link made for instruments: every part it opens across its spex' arrangements, in the order of the show,
     * narrowed further when asked.
     */
    @GetMapping("/parts/zip")
    public ResponseEntity<StreamingResponseBody> spexZip(@PathVariable final String token,
                                                         @RequestParam(required = false) final @Nullable List<UUID> instrumentIds,
                                                         @RequestParam(required = false) final @Nullable List<Pitch> transpositions) {
        return zip(downloadService.sharedSpexZip(token, new PartFilter(instrumentIds, transpositions)));
    }

    private ResponseEntity<StreamingResponseBody> zip(final DownloadService.ZipPlan plan) {
        final ResponseEntity<StreamingResponseBody> response = FileResponses.zip(plan);

        downloadService.record(plan.items().stream().map(DownloadService.ZipItem::file).toList(), plan.shareLinkId());
        return response;
    }
}
