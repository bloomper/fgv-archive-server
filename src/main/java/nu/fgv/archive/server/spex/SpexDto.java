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

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A spex as the GraphQL API shows it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record SpexDto(UUID id, @Nullable String title, @Nullable Integer year, boolean revival, @Nullable UUID parentId,
                      @Nullable UUID categoryId, @Nullable String categoryName) {

    public static SpexDto of(final Spex spex) {
        return new SpexDto(spex.getUuid(), spex.getTitle(), spex.getYear(), spex.isRevival(), spex.getParentId(),
                spex.getCategoryId(), spex.getCategoryName());
    }

    /**
     * The original: the parent of a revival, else the spex itself.
     */
    public UUID familyId() {
        return parentId != null ? parentId : id;
    }
}
