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

package nu.fgv.archive.server.spex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads Spexipedia's change feed at start when the archive has no spex yet, so that a new installation does not wait for
 * the next pull. When Spexipedia cannot be reached, the next pull or an admin tries again.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SpexCopyInitializer {

    private final SpexRepository repository;
    private final SpexipediaSync sync;

    public SpexCopyInitializer(final SpexRepository repository, final SpexipediaSync sync) {
        this.repository = repository;
        this.sync = sync;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onReady() {
        if (repository.count() > 0) {
            return;
        }
        try {
            sync.pull(false);
        } catch (final RuntimeException e) {
            log.warn("Could not read Spexipedia's change feed at start: {}", e.getMessage());
        }
    }
}
