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

package nu.fgv.archive.server.admin;

import nu.fgv.archive.server.file.ArchiveFileRepository;
import nu.fgv.archive.server.file.FileStorage;
import nu.fgv.archive.server.user.CallerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * The space files and previews use, and what is left on the volume, for admins.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class StorageService {

    private final ArchiveFileRepository fileRepository;
    private final FileStorage storage;
    private final CallerService callerService;

    public StorageService(final ArchiveFileRepository fileRepository, final FileStorage storage, final CallerService callerService) {
        this.fileRepository = fileRepository;
        this.storage = storage;
        this.callerService = callerService;
    }

    @Transactional(readOnly = true)
    public StorageDto storage() {
        callerService.current().requireAdmin();
        try {
            return new StorageDto(fileRepository.totalSize(), storage.size(storage.previews()),
                    Files.getFileStore(storage.root()).getUsableSpace(), (int) fileRepository.count());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record StorageDto(double filesBytes, double previewsBytes, double freeBytes, int fileCount) {
    }
}
