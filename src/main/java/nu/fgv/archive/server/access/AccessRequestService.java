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

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.file.FileDtos;
import nu.fgv.archive.server.file.FileDtos.FileDto;
import nu.fgv.archive.server.material.MaterialDtos;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Requests for access to restricted files (product spec 5.5): a user names the files and a reason, and the editors are
 * told by email after the request is saved. Editors list them, open or handled, and mark them handled (or open again)
 * once they have answered, e.g. with a share link.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AccessRequestService {

    static final int MAX_REASON = 2000;
    static final int MAX_FILES = 200;

    private final AccessRequestRepository repository;
    private final ArchiveFileRepository fileRepository;
    private final CallerService callerService;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;
    private final Clock clock;

    public AccessRequestService(final AccessRequestRepository repository, final ArchiveFileRepository fileRepository,
                                final CallerService callerService, final ApplicationEventPublisher events,
                                final AuditService auditService, final Clock clock) {
        this.repository = repository;
        this.fileRepository = fileRepository;
        this.callerService = callerService;
        this.events = events;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public void create(final AccessRequestInput input) {
        final Caller caller = callerService.current();

        caller.requireUser();

        final String reason = StringUtils.hasText(input.reason()) ? input.reason().strip() : "";

        if (reason.isEmpty() || reason.length() > MAX_REASON) {
            throw new BadRequestException("error.accessRequest.reason", MAX_REASON);
        }
        if (input.fileIds().isEmpty() || input.fileIds().size() > MAX_FILES) {
            throw new BadRequestException("error.accessRequest.files");
        }

        final AccessRequest request = new AccessRequest();

        for (final UUID id : input.fileIds()) {
            final ArchiveFile file = fileRepository.findByUuid(id)
                    .filter(found -> MaterialDtos.sees(caller, found.getVersion()))
                    .orElseThrow(() -> new BadRequestException("error.accessRequest.files"));

            request.getFiles().add(file);
        }
        request.setUserSubject(caller.subject());
        request.setUserName(caller.name());
        request.setUserEmail(caller.email());
        request.setReason(reason);
        request.setCreatedAt(clock.instant());
        repository.save(request);
        events.publishEvent(new AccessRequested(request.getId()));
    }

    /**
     * The requests, newest first: all of them, or with {@code handled} only the handled (true) or open (false) ones.
     */
    @Transactional(readOnly = true)
    public List<AccessRequestDto> list(final @Nullable Boolean handled) {
        callerService.current().requireEditor();
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .filter(request -> handled == null || handled == (request.getHandledAt() != null))
                .map(AccessRequestService::toDto)
                .toList();
    }

    /**
     * Marks a request handled by the caller, or open again; audited.
     */
    @Transactional
    public AccessRequestDto handle(final UUID id, final boolean handled) {
        final Caller caller = callerService.current();

        caller.requireEditor();

        final AccessRequest request = repository.findByUuid(id)
                .orElseThrow(() -> new ResourceNotFoundException("error.accessRequest.notFound", id));
        final String who = request.getUserName() == null ? "" : " från " + request.getUserName();

        if (handled && request.getHandledAt() == null) {
            request.setHandledAt(clock.instant());
            request.setHandledBy(caller.subject());
            request.setHandledByName(caller.name());
            auditService.record("UPDATE", "ACCESS_REQUEST", request.getUuid(), "Hanterade åtkomstförfrågan" + who);
        } else if (!handled && request.getHandledAt() != null) {
            request.setHandledAt(null);
            request.setHandledBy(null);
            request.setHandledByName(null);
            auditService.record("UPDATE", "ACCESS_REQUEST", request.getUuid(), "Öppnade åtkomstförfrågan" + who + " igen");
        }
        return toDto(request);
    }

    private static AccessRequestDto toDto(final AccessRequest request) {
        return new AccessRequestDto(request.getUuid(), request.getUserName(), request.getUserEmail(), request.getReason(),
                request.getCreatedAt(), request.getHandledAt(), request.getHandledByName(),
                request.getFiles().stream().map(file -> FileDtos.toDto(file, true, true)).toList());
    }

    public record AccessRequestInput(List<UUID> fileIds, @Nullable String reason) {
    }

    public record AccessRequestDto(UUID id, @Nullable String userName, @Nullable String userEmail, String reason,
                                   Instant createdAt, @Nullable Instant handledAt, @Nullable String handledByName,
                                   List<FileDto> files) {
    }

    /**
     * A request was saved; the editors are told after the commit.
     */
    public record AccessRequested(Long requestId) {
    }
}
