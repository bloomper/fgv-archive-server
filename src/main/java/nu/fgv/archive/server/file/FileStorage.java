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

import nu.fgv.archive.server.config.ArchiveProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The storage folder. Files are named by id, never by name, so edits never move them.
 *
 * <pre>
 * files/      uploaded files, files/&lt;version uuid&gt;/&lt;file uuid&gt;.&lt;extension&gt;
 * previews/   first-page previews, previews/&lt;file uuid&gt;.png; can be made again
 * uploads/    unfinished uploads, kept 24 hours
 * </pre>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class FileStorage {

    private static final List<String> FOLDERS = List.of("files", "previews", "uploads");

    private final Path root;

    public FileStorage(final ArchiveProperties properties) {
        this.root = properties.storage().root().toAbsolutePath().normalize();
        for (final String folder : FOLDERS) {
            try {
                Files.createDirectories(root.resolve(folder));
            } catch (final IOException e) {
                throw new UncheckedIOException("Could not create " + root.resolve(folder), e);
            }
        }
    }

    public Path root() {
        return root;
    }

    public Path files() {
        return root.resolve("files");
    }

    public Path previews() {
        return root.resolve("previews");
    }

    public Path file(final ArchiveFile file) {
        return file(file.getVersion().getUuid(), file);
    }

    public Path file(final UUID versionId, final ArchiveFile file) {
        return files().resolve(versionId.toString()).resolve(file.fileName());
    }

    public Path preview(final ArchiveFile file) {
        return previews().resolve(file.getUuid() + ".png");
    }

    public Path uploadFile(final String uploadId) {
        return root.resolve("uploads").resolve(uploadId);
    }

    /**
     * Puts a file in place, replacing any earlier one.
     */
    public void store(final Path source, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Deletes a file and its preview.
     */
    public void delete(final ArchiveFile file) throws IOException {
        Files.deleteIfExists(file(file));
        Files.deleteIfExists(preview(file));
    }

    /**
     * Deletes a version's folder with whatever is left in it.
     */
    public void deleteVersion(final UUID versionId) throws IOException {
        FileSystemUtils.deleteRecursively(files().resolve(versionId.toString()));
    }

    /**
     * The bytes in a folder and its subfolders.
     */
    public long size(final Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (final IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    /**
     * Empties the folders, for the sample data import.
     */
    public void wipe() throws IOException {
        for (final String folder : FOLDERS) {
            final Path path = root.resolve(folder);

            Files.createDirectories(path);
            try (Stream<Path> children = Files.list(path)) {
                for (final Path child : children.toList()) {
                    FileSystemUtils.deleteRecursively(child);
                }
            }
        }
    }
}
