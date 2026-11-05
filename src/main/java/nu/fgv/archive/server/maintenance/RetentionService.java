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

package nu.fgv.archive.server.maintenance;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.archive.server.access.AccessRequestRepository;
import nu.fgv.archive.server.download.DownloadLogRepository;
import nu.fgv.archive.server.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Deletes what is no longer needed (product spec 7): download logs and access requests after 24 months, and the users
 * not seen for 36 months.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class RetentionService {

    static final int LOG_MONTHS = 24;
    static final int USER_MONTHS = 36;

    private final DownloadLogRepository downloadLogRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final AppUserRepository userRepository;
    private final Clock clock;

    public RetentionService(final DownloadLogRepository downloadLogRepository, final AccessRequestRepository accessRequestRepository,
                            final AppUserRepository userRepository, final Clock clock) {
        this.downloadLogRepository = downloadLogRepository;
        this.accessRequestRepository = accessRequestRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    private Instant monthsAgo(final int months) {
        return clock.instant().atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
    }

    @SchedulerLock(name = "retention", lockAtMostFor = "PT30M")
    @Transactional
    public void run() {
        final int logs = downloadLogRepository.deleteOlderThan(monthsAgo(LOG_MONTHS));
        final var requests = accessRequestRepository.findByCreatedAtBefore(monthsAgo(LOG_MONTHS));

        accessRequestRepository.deleteAll(requests);

        final long users = userRepository.deleteByLastSeenAtBefore(monthsAgo(USER_MONTHS));

        log.info("Deleted {} download logs, {} access requests and {} users past their retention", logs, requests.size(), users);
    }
}
