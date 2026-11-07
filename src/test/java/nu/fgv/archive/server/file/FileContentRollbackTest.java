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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content of a file that is added or replaced in a transaction that rolls back goes back to its upload, whose session
 * the rollback keeps, so that nothing is left in {@code files/} and the upload can be used again.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class FileContentRollbackTest {

    @TempDir
    private Path folder;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static void complete(final int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCompletion(status));
    }

    @Test
    void anAddedFilesContentReturnsToItsUploadOnARollback() throws IOException {
        final Path upload = folder.resolve("upload");
        final Path stored = Files.writeString(folder.resolve("stored.pdf"), "content");

        FileService.returnOnRollback(stored, upload);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(stored).doesNotExist();
        assertThat(upload).hasContent("content");
    }

    @Test
    void anAddedFilesContentStaysOnACommit() throws IOException {
        final Path upload = folder.resolve("upload");
        final Path stored = Files.writeString(folder.resolve("stored.pdf"), "content");

        FileService.returnOnRollback(stored, upload);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(stored).hasContent("content");
        assertThat(upload).doesNotExist();
    }

    @Test
    void replacingContentReturnsToItsUploadOnARollback() throws IOException {
        final Path upload = folder.resolve("upload");
        final Path target = Files.writeString(folder.resolve("file.pdf"), "old");
        final Path staged = Files.writeString(folder.resolve("file.pdf.replacing-upload"), "new");
        final Path preview = Files.writeString(folder.resolve("preview.png"), "preview");

        FileService.swapAfterCommit(staged, target, target, preview, upload);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(target).hasContent("old");
        assertThat(preview).exists();
        assertThat(staged).doesNotExist();
        assertThat(upload).hasContent("new");
    }

    @Test
    void replacedContentTakesItsPlaceOnACommit() throws IOException {
        final Path upload = folder.resolve("upload");
        final Path old = Files.writeString(folder.resolve("file.docx"), "old");
        final Path target = folder.resolve("file.pdf");
        final Path staged = Files.writeString(folder.resolve("file.pdf.replacing-upload"), "new");
        final Path preview = Files.writeString(folder.resolve("preview.png"), "preview");

        FileService.swapAfterCommit(staged, target, old, preview, upload);
        complete(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(target).hasContent("new");
        assertThat(old).doesNotExist();
        assertThat(preview).doesNotExist();
        assertThat(upload).doesNotExist();
    }
}
