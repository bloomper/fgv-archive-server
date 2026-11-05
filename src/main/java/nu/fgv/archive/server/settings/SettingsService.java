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

package nu.fgv.archive.server.settings;

import nu.fgv.archive.server.audit.AuditService;
import nu.fgv.archive.server.user.Caller;
import nu.fgv.archive.server.user.CallerService;
import nu.fgv.archive.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads and changes the settings.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SettingsService {

    static final int MAX_UPLOAD_MB = 10_000;
    static final int MAX_SHARE_LINK_MONTHS = 120;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s,]+@[^@\\s,]+\\.[^@\\s,]+");

    private final SettingsRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;
    private final Clock clock;

    public SettingsService(final SettingsRepository repository, final CallerService callerService,
                           final AuditService auditService, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    private static @Nullable String text(final @Nullable String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static SettingsDto toDto(final Settings settings) {
        return new SettingsDto(settings.getWelcomeSv(), settings.getWelcomeEn(), settings.getMaxUploadMb(),
                settings.isManuscriptRestricted(), settings.isArrangementRestricted(), settings.getMaxShareLinkMonths(),
                settings.recipients());
    }

    /**
     * The settings, without checking the caller, for other services.
     */
    @Transactional(readOnly = true)
    public Settings current() {
        return repository.findById(Settings.ID).orElseGet(Settings::new);
    }

    @Transactional(readOnly = true)
    public SettingsDto read() {
        callerService.current().requireUser();
        return toDto(current());
    }

    @Transactional
    public SettingsDto update(final SettingsDto input) {
        final Caller caller = callerService.current();

        caller.requireAdmin();
        if (input.maxUploadMb() < 1 || input.maxUploadMb() > MAX_UPLOAD_MB
                || input.maxShareLinkMonths() < 1 || input.maxShareLinkMonths() > MAX_SHARE_LINK_MONTHS) {
            throw new BadRequestException("error.settings.invalid");
        }

        final List<String> recipients = input.accessRequestRecipients() == null ? List.of()
                : input.accessRequestRecipients().stream().map(String::strip).filter(StringUtils::hasText).distinct().toList();

        if (recipients.stream().anyMatch(address -> !EMAIL.matcher(address).matches())
                || String.join(",", recipients).length() > 1000) {
            throw new BadRequestException("error.settings.recipients");
        }

        final Settings settings = repository.findById(Settings.ID).orElseGet(Settings::new);

        settings.setWelcomeSv(text(input.welcomeSv()));
        settings.setWelcomeEn(text(input.welcomeEn()));
        settings.setMaxUploadMb(input.maxUploadMb());
        settings.setManuscriptRestricted(input.manuscriptRestricted());
        settings.setArrangementRestricted(input.arrangementRestricted());
        settings.setMaxShareLinkMonths(input.maxShareLinkMonths());
        settings.setAccessRequestRecipients(recipients.isEmpty() ? null : String.join(",", recipients));
        settings.setLastModifiedBy(caller.subject());
        settings.setLastModifiedAt(clock.instant());
        repository.save(settings);
        auditService.record("UPDATE", "SETTINGS", null, "Ändrade inställningarna");
        return toDto(settings);
    }

    /**
     * The settings as the GraphQL API shows and takes them.
     *
     * @param manuscriptRestricted    whether new manuscript versions start restricted
     * @param arrangementRestricted   whether new arrangement versions start restricted
     * @param maxShareLinkMonths      the longest a share link may last
     * @param accessRequestRecipients who is told about access requests; empty for everyone with EDITOR
     */
    public record SettingsDto(@Nullable String welcomeSv, @Nullable String welcomeEn, int maxUploadMb,
                              boolean manuscriptRestricted, boolean arrangementRestricted, int maxShareLinkMonths,
                              @Nullable List<String> accessRequestRecipients) {
    }
}
