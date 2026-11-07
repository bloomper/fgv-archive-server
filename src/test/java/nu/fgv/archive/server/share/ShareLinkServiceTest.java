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

package nu.fgv.archive.server.share;

import nu.fgv.archive.server.TestCallers;
import nu.fgv.archive.server.TestClock;
import nu.fgv.archive.server.download.DownloadLogRepository;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.user.CallerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static nu.fgv.archive.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who sees how often the files of a share link were downloaded.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ShareLinkServiceTest {

    private final ShareLinkRepository repository = mock(ShareLinkRepository.class);
    private final DownloadLogRepository downloadLogRepository = mock(DownloadLogRepository.class);
    private final ArchiveFileRepository fileRepository = mock(ArchiveFileRepository.class);
    private final CallerService callerService = mock(CallerService.class);
    private final ShareLinkService service = new ShareLinkService(repository, null, fileRepository, null, downloadLogRepository, null,
            null, null, null, callerService, null, new TestClock(Instant.parse("2026-11-05T10:00:00Z")));

    private static ShareLink link(final long id, final String createdBy) {
        final ShareLink link = new ShareLink();

        link.setId(id);
        link.setUuid(uuid(id));
        link.setCreatedBy(createdBy);
        return link;
    }

    @Test
    void anEditorSeesTheDownloadsOfTheirOwnLinksOnly() {
        final List<UUID> ids = List.of(uuid(1), uuid(2));

        when(callerService.current()).thenReturn(TestCallers.editor());
        when(repository.findByUuidIn(ids)).thenReturn(List.of(link(1, "s-editor"), link(2, "s-someone-else")));
        when(downloadLogRepository.countByShareLinks(anyCollection())).thenReturn(List.of());

        assertThat(service.downloads(ids)).containsOnlyKeys(uuid(1), uuid(2));
        verify(downloadLogRepository).countByShareLinks(Set.of(1L));
    }

    @Test
    void anAdminSeesTheDownloadsOfEveryLink() {
        final List<UUID> ids = List.of(uuid(1), uuid(2));

        when(callerService.current()).thenReturn(TestCallers.admin());
        when(repository.findByUuidIn(ids)).thenReturn(List.of(link(1, "s-editor"), link(2, "s-someone-else")));
        when(downloadLogRepository.countByShareLinks(anyCollection())).thenReturn(List.of());

        service.downloads(ids);
        verify(downloadLogRepository).countByShareLinks(Set.of(1L, 2L));
    }
}
