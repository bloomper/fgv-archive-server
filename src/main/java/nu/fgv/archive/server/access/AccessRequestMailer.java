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

import lombok.extern.slf4j.Slf4j;
import nu.fgv.archive.server.config.ArchiveProperties;
import nu.fgv.archive.server.file.ArchiveFile;
import nu.fgv.archive.server.material.MaterialVersion;
import nu.fgv.archive.server.number.SpexNumberRepository;
import nu.fgv.archive.server.settings.SettingsService;
import nu.fgv.archive.server.util.Locales;
import nu.fgv.archive.server.util.error.DirectoryUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tells the editors by email about a request for access to restricted files: who asks, which files and why. The
 * recipients are the addresses in the settings, else everyone with {@code EDITOR} in Keycloak, each in their language.
 * One plain text message per recipient, sent after the request is committed; it never fails the request, and addresses
 * are never logged. Without a mail server nothing is sent.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class AccessRequestMailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessageSource messageSource;
    private final EditorDirectory directory;
    private final SettingsService settingsService;
    private final AccessRequestRepository repository;
    private final SpexNumberRepository numberRepository;
    private final TransactionTemplate transactionTemplate;
    private final String from;
    private final String webUrl;

    @SuppressWarnings("java:S107")
    public AccessRequestMailer(final ObjectProvider<JavaMailSender> mailSender, final MessageSource messageSource,
                               final EditorDirectory directory, final SettingsService settingsService,
                               final AccessRequestRepository repository, final SpexNumberRepository numberRepository,
                               final TransactionTemplate transactionTemplate, final ArchiveProperties properties) {
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.directory = directory;
        this.settingsService = settingsService;
        this.repository = repository;
        this.numberRepository = numberRepository;
        this.transactionTemplate = transactionTemplate;
        this.from = properties.mail().from();
        this.webUrl = properties.webUrl().endsWith("/") ? properties.webUrl().substring(0, properties.webUrl().length() - 1) : properties.webUrl();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void accessRequested(final AccessRequestService.AccessRequested event) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            log.info("No mail server is configured, so the editors are not told about access request {}", event.requestId());
            return;
        }
        try {
            final List<String> configured = settingsService.current().recipients();
            final List<EditorDirectory.Editor> recipients = configured.isEmpty() ? directory.editors()
                    : configured.stream().map(address -> new EditorDirectory.Editor(address, null)).toList();

            if (recipients.isEmpty()) {
                log.warn("No editor to tell about access request {}", event.requestId());
            }
            for (final EditorDirectory.Editor recipient : recipients) {
                final SimpleMailMessage message = Objects.requireNonNull(transactionTemplate.execute(_ ->
                        message(repository.findById(event.requestId()).orElseThrow(), recipient.email(), Locales.supported(recipient.locale()))));

                try {
                    sender.send(message);
                } catch (final MailException e) {
                    log.warn("The mail server refused a message about access request {}: {}", event.requestId(), e.getClass().getSimpleName());
                }
            }
        } catch (final DirectoryUnavailableException e) {
            log.warn("Could not read the editors from Keycloak, so they are not told about access request {}: {}", event.requestId(),
                    e.getCause() == null ? e.getClass().getSimpleName() : e.getCause().getClass().getSimpleName());
        } catch (final RuntimeException e) {
            log.warn("Could not tell the editors about access request {}: {}", event.requestId(), e.getClass().getSimpleName());
        }
    }

    SimpleMailMessage message(final AccessRequest request, final String to, final Locale locale) {
        final String who = request.getUserEmail() == null ? String.valueOf(request.getUserName())
                : "%s (%s)".formatted(request.getUserName(), request.getUserEmail());
        final List<String> lines = new ArrayList<>();

        lines.add(text("accessRequest.mail.intro", locale, who));
        lines.add("");
        for (final ArchiveFile file : request.getFiles()) {
            final MaterialVersion version = file.getVersion();

            lines.add("- %s, %s, %s".formatted(parentTitle(version), version.getLabel(), file.getTitle()));
            lines.add("  %s/versions/%s".formatted(webUrl, version.getUuid()));
        }
        lines.add("");
        lines.add(text("accessRequest.mail.reason", locale));
        lines.add(request.getReason());
        lines.add("");
        lines.add(text("accessRequest.mail.footer", locale));

        final SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        if (request.getUserEmail() != null) {
            message.setReplyTo(request.getUserEmail());
        }
        message.setSubject(text("accessRequest.mail.subject", locale, oneLine(request.getUserName())));
        message.setText(String.join("\n", lines));
        return message;
    }

    private static String oneLine(final @Nullable String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
    }

    private String parentTitle(final MaterialVersion version) {
        if (version.getManuscript() != null) {
            return version.getManuscript().getTitle();
        }
        return numberRepository.findByUuid(Objects.requireNonNull(version.getArrangement()).getNumberId())
                .map(number -> number.getTitle()).orElse("");
    }

    private String text(final String key, final Locale locale, final Object... arguments) {
        return Objects.requireNonNull(messageSource.getMessage(key, arguments, key, locale));
    }
}
