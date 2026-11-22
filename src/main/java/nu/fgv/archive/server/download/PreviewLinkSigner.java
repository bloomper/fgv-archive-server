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
package nu.fgv.archive.server.download;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.archive.server.config.ArchiveProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs links to the first-page previews of files, which the API then serves without a token, for Spexipedia's spex
 * pages (spexipediaDocument):
 *
 * <pre>
 * {baseUrl}/media/{expires}/{signature}/files/{id}/preview
 * signature = base64url(HMAC-SHA256(key, "{expires}/files/{id}/preview")), without padding
 * </pre>
 *
 * <p>One link opens one preview until it expires, and no file. Without a key no links are made.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class PreviewLinkSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final String baseUrl;
    private final SecretKeySpec key;
    private final Duration lifetime;
    private final Clock clock;

    PreviewLinkSigner(final String baseUrl, final String signingKey, final Duration lifetime, final Clock clock) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.key = signingKey == null || signingKey.isBlank() ? null
                : new SecretKeySpec(signingKey.strip().getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.lifetime = lifetime == null ? Duration.ofHours(6) : lifetime;
        this.clock = clock;
        if (key == null) {
            log.warn("API_MEDIA_SIGNING_KEY is not set: Spexipedia gets no links to previews");
        }
    }

    @Autowired
    public PreviewLinkSigner(final ArchiveProperties properties, final Clock clock) {
        this(properties.baseUrl(), properties.media() == null ? null : properties.media().signingKey(),
                properties.media() == null ? null : properties.media().linkLifetime(), clock);
    }

    /**
     * A link to the file's preview; empty without a signing key.
     */
    public Optional<String> preview(final UUID fileId) {
        if (key == null) {
            return Optional.empty();
        }

        final long expires = clock.instant().plus(lifetime).getEpochSecond();
        final String scope = scope(fileId);

        return Optional.of("%s/media/%d/%s/%s".formatted(baseUrl, expires, signature(expires, scope), scope));
    }

    /**
     * Whether the signature is the file's preview's and the link has not expired.
     */
    public boolean valid(final long expires, final String signature, final UUID fileId) {
        if (key == null) {
            return false;
        }

        final byte[] expected = signature(expires, scope(fileId)).getBytes(StandardCharsets.US_ASCII);

        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))
                && clock.instant().getEpochSecond() <= expires;
    }

    private static String scope(final UUID fileId) {
        return "files/%s/preview".formatted(fileId);
    }

    private String signature(final long expires, final String scope) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);

            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((expires + "/" + scope).getBytes(StandardCharsets.UTF_8)));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
