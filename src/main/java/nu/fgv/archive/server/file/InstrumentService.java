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
import nu.fgv.archive.server.file.FileDtos.InstrumentDto;
import nu.fgv.archive.server.file.FileDtos.InstrumentInput;
import nu.fgv.archive.server.material.InstrumentationEntry;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.material.MaterialVersionRepository;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import nu.fgv.archive.server.util.error.ConflictException;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The instruments: everyone reads them, admins add, rename, reorder, merge and delete them. An instrument in use, by a
 * file or an instrumentation, cannot be deleted, only merged into another.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class InstrumentService {

    static final int MAX_ABBREVIATIONS = 500;

    private final InstrumentRepository repository;
    private final MaterialVersionRepository versionRepository;
    private final ArchiveFileRepository fileRepository;
    private final CallerService callerService;
    private final AuditService auditService;

    public InstrumentService(final InstrumentRepository repository, final MaterialVersionRepository versionRepository,
                             final ArchiveFileRepository fileRepository,
                             final CallerService callerService, final AuditService auditService) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.fileRepository = fileRepository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<InstrumentDto> instruments() {
        callerService.current().requireUser();
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(FileDtos::toDto).toList();
    }

    /**
     * Instruments by id, without checking the caller, for other services.
     */
    @Transactional(readOnly = true)
    public Map<UUID, InstrumentDto> byIds(final Collection<UUID> ids) {
        return repository.findAll().stream().filter(instrument -> ids.contains(instrument.getUuid()))
                .collect(Collectors.toMap(Instrument::getUuid, FileDtos::toDto));
    }

    /**
     * How many files and instrumentation entries use each instrument.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> useCounts(final Collection<UUID> ids) {
        final Map<Long, UUID> uuids = repository.findAll().stream().collect(Collectors.toMap(Instrument::getId, Instrument::getUuid));
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Object[] row : repository.countUsesByInstrument()) {
            counts.put(uuids.get(((Number) row[0]).longValue()), ((Number) row[1]).intValue());
        }
        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> counts.getOrDefault(id, 0)));
    }

    @Transactional
    public InstrumentDto create(final InstrumentInput input) {
        callerService.current().requireAdmin();

        final Instrument instrument = new Instrument();

        apply(instrument, input);
        instrument.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream()
                .filter(other -> other.getGroup().ordinal() <= instrument.getGroup().ordinal())
                .mapToInt(Instrument::getPosition).max().orElse(0) + 1);
        repository.findAll().stream().filter(other -> other.getPosition() >= instrument.getPosition())
                .forEach(other -> other.setPosition(other.getPosition() + 1));
        repository.save(instrument);
        auditService.record("CREATE", "INSTRUMENT", instrument.getUuid(), "Skapade instrumentet " + instrument.getNameSv());
        return FileDtos.toDto(instrument);
    }

    @Transactional
    public InstrumentDto update(final UUID id, final InstrumentInput input) {
        callerService.current().requireAdmin();

        final Instrument instrument = instrument(id);

        apply(instrument, input);
        auditService.record("UPDATE", "INSTRUMENT", instrument.getUuid(), "Ändrade instrumentet " + instrument.getNameSv());
        return FileDtos.toDto(instrument);
    }

    @Transactional
    public List<InstrumentDto> reorder(final List<UUID> ids) {
        callerService.current().requireAdmin();

        final List<Instrument> instruments = repository.findAll();
        final Map<UUID, Instrument> byId = instruments.stream().collect(Collectors.toMap(Instrument::getUuid, Function.identity()));

        if (ids.size() != instruments.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.reorder.invalid");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "INSTRUMENT", null, "Ändrade ordningen på instrumenten");
        return ids.stream().map(byId::get).map(FileDtos::toDto).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final Instrument instrument = instrument(id);
        final long uses = repository.countUses(instrument.getId());

        if (uses > 0) {
            throw new ConflictException("error.instrument.inUse", uses);
        }
        repository.delete(instrument);
        auditService.record("DELETE", "INSTRUMENT", instrument.getUuid(), "Raderade instrumentet " + instrument.getNameSv());
    }

    /**
     * Moves an instrument's files and instrumentation entries to another instrument, adding up the counts where a
     * version has both and keeping a file's other instruments, and deletes it.
     */
    @Transactional
    public InstrumentDto merge(final UUID id, final UUID intoId) {
        callerService.current().requireAdmin();

        final Instrument instrument = instrument(id);
        final Instrument into = instrument(intoId);

        if (instrument.getId().equals(into.getId())) {
            throw new BadRequestException("error.instrument.merge");
        }
        for (final ArchiveFile file : fileRepository.findByInstrument(instrument)) {
            final int at = file.getInstruments().indexOf(instrument);

            if (file.getInstruments().contains(into)) {
                file.getInstruments().remove(at);
            } else {
                file.getInstruments().set(at, into);
            }
        }
        for (final MaterialVersion version : versionRepository.findAll()) {
            final Optional<InstrumentationEntry> from = version.getInstrumentation().stream()
                    .filter(entry -> entry.getInstrument().getId().equals(instrument.getId())).findFirst();

            if (from.isPresent()) {
                final Optional<InstrumentationEntry> existing = version.getInstrumentation().stream()
                        .filter(entry -> entry.getInstrument().getId().equals(into.getId())).findFirst();

                if (existing.isPresent()) {
                    existing.get().setCount(existing.get().getCount() + from.get().getCount());
                    version.getInstrumentation().remove(from.get());
                } else {
                    from.get().setInstrument(into);
                }
            }
        }
        versionRepository.flush();
        repository.delete(instrument);
        auditService.record("UPDATE", "INSTRUMENT", into.getUuid(), "Slog ihop instrumentet %s med %s".formatted(instrument.getNameSv(), into.getNameSv()));
        return FileDtos.toDto(into);
    }

    private void apply(final Instrument instrument, final InstrumentInput input) {
        instrument.setNameSv(FileRoleService.name(input.nameSv()));
        instrument.setNameEn(FileRoleService.name(input.nameEn()));
        if (input.group() == null) {
            throw new BadRequestException("error.instrument.group");
        }
        instrument.setGroup(input.group());
        if (input.defaultTransposition() != null) {
            if (Math.abs(input.defaultTransposition().octave()) > 1) {
                throw new BadRequestException("error.file.octave");
            }
            instrument.setDefaultPitch(input.defaultTransposition().pitch());
            instrument.setDefaultOctave(input.defaultTransposition().octave());
        }

        final String abbreviations = input.abbreviations() == null ? "" : input.abbreviations().stream()
                .map(String::strip).filter(StringUtils::hasText).map(value -> value.replace(",", " ")).distinct()
                .collect(Collectors.joining(","));

        if (abbreviations.length() > MAX_ABBREVIATIONS) {
            throw new BadRequestException("error.instrument.abbreviations", MAX_ABBREVIATIONS);
        }
        instrument.setAbbreviations(abbreviations.isEmpty() ? null : abbreviations);
    }

    private Instrument instrument(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.instrument.notFound", id));
    }
}
