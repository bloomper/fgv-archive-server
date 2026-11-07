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

package nu.fgv.archive.server.processing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Processes waiting files every few seconds, only where {@code archive.worker.enabled} is set (the worker container, or
 * locally the API), so that API instances neither read files nor take the lock.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
@ConditionalOnBooleanProperty("archive.worker.enabled")
public class FileProcessingSchedule {

    private final FileProcessor processor;

    public FileProcessingSchedule(final FileProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelay = 5, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    @SchedulerLock(name = "file-processing", lockAtMostFor = "PT30M")
    public void tick() {
        processor.processPending();
    }
}
