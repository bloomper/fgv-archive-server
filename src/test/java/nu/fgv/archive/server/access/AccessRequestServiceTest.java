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

package nu.fgv.archive.server.access;

import nu.fgv.archive.server.TestCallers;
import nu.fgv.archive.server.TestClock;
import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static nu.fgv.archive.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Access requests email every editor, so a user can make only so many of them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessRequestServiceTest {

    private final AccessRequestRepository repository = mock(AccessRequestRepository.class);
    private final ArchiveFileRepository fileRepository = mock(ArchiveFileRepository.class);
    private final CallerService callerService = mock(CallerService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TestClock clock = new TestClock(Instant.parse("2026-11-05T10:00:00Z"));
    private final AccessRequestService service = new AccessRequestService(repository, fileRepository, callerService, events,
            mock(AuditService.class), clock);

    @BeforeEach
    void setUp() {
        final MaterialVersion version = new MaterialVersion();
        final ArchiveFile file = new ArchiveFile();

        version.setPublished(true);
        version.setRestricted(true);
        file.setVersion(version);
        when(callerService.current()).thenReturn(TestCallers.viewer());
        when(fileRepository.findByUuid(uuid(1))).thenReturn(Optional.of(file));
    }

    @Test
    void aUserMakesAtMostTenRequestsAnHour() {
        final AccessRequestService.AccessRequestInput input = new AccessRequestService.AccessRequestInput(List.of(uuid(1)), "Revival 2028");

        when(repository.countByUserSubjectAndCreatedAtAfter("s-viewer", clock.instant().minus(AccessRequestService.RATE_WINDOW)))
                .thenReturn((long) AccessRequestService.MAX_PER_WINDOW - 1);
        service.create(input);
        verify(events).publishEvent(any(AccessRequestService.AccessRequested.class));

        when(repository.countByUserSubjectAndCreatedAtAfter("s-viewer", clock.instant().minus(AccessRequestService.RATE_WINDOW)))
                .thenReturn((long) AccessRequestService.MAX_PER_WINDOW);
        assertThatThrownBy(() -> service.create(input))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getDetailMessageCode()).isEqualTo("error.accessRequest.tooMany"));
        // Only the first was saved
        verify(repository, times(1)).save(any(AccessRequest.class));
    }
}
