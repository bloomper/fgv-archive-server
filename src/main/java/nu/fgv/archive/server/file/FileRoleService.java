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

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.file.FileDtos.FileRoleDto;
import nu.fgv.archive.server.file.FileDtos.FileRoleInput;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ConflictException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The file roles: everyone reads them, admins add, rename, reorder and delete them. A role that files have cannot be
 * deleted, and neither can the last one.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class FileRoleService {

    static final int MAX_NAME = 100;

    private final FileRoleRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;

    public FileRoleService(final FileRoleRepository repository, final CallerService callerService, final AuditService auditService) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    static String name(final @Nullable String value) {
        if (!StringUtils.hasText(value) || value.strip().length() > MAX_NAME) {
            throw new BadRequestException("error.name.invalid", MAX_NAME);
        }
        return value.strip();
    }

    @Transactional(readOnly = true)
    public List<FileRoleDto> roles() {
        callerService.current().requireUser();
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(FileDtos::toDto).toList();
    }

    /**
     * How many files have each role.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> fileCounts(final Collection<UUID> ids) {
        // The archive-wide counts are for admins only; others, including share link visitors, get none
        if (!callerService.currentIfAny().map(Caller::isAdmin).orElse(false)) {
            return Map.of();
        }

        final Map<Long, UUID> uuids = repository.findAll().stream().collect(Collectors.toMap(FileRole::getId, FileRole::getUuid));
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Object[] row : repository.countFilesByRole()) {
            counts.put(uuids.get((Long) row[0]), ((Number) row[1]).intValue());
        }
        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> counts.getOrDefault(id, 0)));
    }

    @Transactional
    public FileRoleDto create(final FileRoleInput input) {
        callerService.current().requireAdmin();

        final FileRole role = new FileRole();

        role.setNameSv(name(input.nameSv()));
        role.setNameEn(name(input.nameEn()));
        role.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(FileRole::getPosition).max().orElse(0) + 1);
        repository.save(role);
        auditService.record("CREATE", "FILE_ROLE", role.getUuid(), "Skapade filrollen " + role.getNameSv());
        return FileDtos.toDto(role);
    }

    @Transactional
    public FileRoleDto update(final UUID id, final FileRoleInput input) {
        callerService.current().requireAdmin();

        final FileRole role = role(id);

        role.setNameSv(name(input.nameSv()));
        role.setNameEn(name(input.nameEn()));
        auditService.record("UPDATE", "FILE_ROLE", role.getUuid(), "Ändrade filrollen " + role.getNameSv());
        return FileDtos.toDto(role);
    }

    @Transactional
    public List<FileRoleDto> reorder(final List<UUID> ids) {
        callerService.current().requireAdmin();

        final List<FileRole> roles = repository.findAll();
        final Map<UUID, FileRole> byId = roles.stream().collect(Collectors.toMap(FileRole::getUuid, Function.identity()));

        if (ids.size() != roles.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.reorder.invalid");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "FILE_ROLE", null, "Ändrade ordningen på filrollerna");
        return ids.stream().map(byId::get).map(FileDtos::toDto).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final FileRole role = role(id);
        final long files = repository.countFiles(role);

        if (files > 0) {
            throw new ConflictException("error.fileRole.inUse", files);
        }
        if (repository.count() <= 1) {
            throw new BadRequestException("error.fileRole.last");
        }
        repository.delete(role);
        auditService.record("DELETE", "FILE_ROLE", role.getUuid(), "Raderade filrollen " + role.getNameSv());
    }

    private FileRole role(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.fileRole.notFound", id));
    }
}
