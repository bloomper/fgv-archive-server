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

import nu.fgv.archive.server.TestCallers;
import nu.fgv.archive.server.TestClock;
import nu.fgv.archive.server.file.FileService.StoredFile;
import nu.fgv.archive.server.user.CallerService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static nu.fgv.archive.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What counts as a download, and that a file fetched again soon counts once.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class DownloadServiceTest {

    private final DownloadLogRepository logRepository = mock(DownloadLogRepository.class);
    private final CallerService callerService = mock(CallerService.class);
    private final TestClock clock = new TestClock(Instant.parse("2026-11-05T10:00:00Z"));
    private final DownloadService service = new DownloadService(logRepository, null, null, null, null, null, null, callerService, clock);
    private final StoredFile restricted = new StoredFile(1L, uuid(1), Path.of("a.pdf"), Path.of("a.png"), "A.pdf", "application/pdf", true, false);

    @Test
    void aWholeFileOrARangeFromItsStartIsADownload() {
        assertThat(DownloadService.countable(null)).isTrue();
        assertThat(DownloadService.countable("bytes=0-")).isTrue();
        assertThat(DownloadService.countable("bytes=0-65535")).isTrue();
        assertThat(DownloadService.countable("bytes=65536-131071")).isFalse();
        assertThat(DownloadService.countable("bytes=-500")).isFalse();
    }

    @Test
    void aFileFetchedAgainSoonCountsOnce() {
        when(callerService.currentIfAny()).thenReturn(Optional.of(TestCallers.viewer()));

        service.record(List.of(restricted), null);
        service.record(List.of(restricted), null);
        verify(logRepository, times(1)).countDownload(1L);
        verify(logRepository, times(1)).save(any());

        // Through a link it is another download
        service.record(List.of(restricted), 7L);
        verify(logRepository, times(2)).save(any());

        clock.advance(Duration.ofMinutes(6));
        service.record(List.of(restricted), null);
        verify(logRepository, times(3)).countDownload(1L);
    }
}
