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

import nu.fgv.archive.server.config.ArchiveProperties;
import nu.fgv.archive.server.util.error.DirectoryUnavailableException;
import nu.fgv.archive.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The editors in Keycloak: everyone with {@code EDITOR} on {@code fgv-archive}, with their email address and language,
 * read with the service account {@code fgv-archive-service}, which needs {@code realm-management}'s
 * {@code view-users} and {@code view-clients}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class EditorDirectory {

    private static final int MAX_EDITORS = 500;

    private final Keycloak keycloak;
    private final String realm;

    public EditorDirectory(final Keycloak keycloak, final ArchiveProperties properties) {
        this.keycloak = keycloak;
        this.realm = properties.keycloak().realm();
    }

    private static @Nullable Locale locale(final UserRepresentation user) {
        final Map<String, List<String>> attributes = user.getAttributes();
        final List<String> locales = attributes == null ? null : attributes.get("locale");

        return locales == null || locales.isEmpty() ? null : Locale.forLanguageTag(locales.getFirst());
    }

    /**
     * The enabled editors with an email address.
     *
     * @throws DirectoryUnavailableException when Keycloak cannot be asked
     */
    public List<Editor> editors() {
        try {
            final RealmResource realmResource = keycloak.realm(realm);
            final String clientUuid = realmResource.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId();

            return realmResource.clients().get(clientUuid).roles().get("EDITOR").getUserMembers(0, MAX_EDITORS).stream()
                    .filter(user -> Boolean.TRUE.equals(user.isEnabled()) && StringUtils.hasText(user.getEmail()))
                    .map(user -> new Editor(user.getEmail(), locale(user)))
                    .toList();
        } catch (final RuntimeException e) {
            throw new DirectoryUnavailableException(e);
        }
    }

    /**
     * An editor to tell about access requests.
     */
    public record Editor(String email, @Nullable Locale locale) {
    }
}
