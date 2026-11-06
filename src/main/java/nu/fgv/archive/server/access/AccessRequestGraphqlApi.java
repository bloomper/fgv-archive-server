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

import nu.fgv.archive.server.access.AccessRequestService.AccessRequestDto;
import nu.fgv.archive.server.access.AccessRequestService.AccessRequestInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL for access requests. A thin adapter: {@link AccessRequestService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AccessRequestGraphqlApi {

    private final AccessRequestService service;

    public AccessRequestGraphqlApi(final AccessRequestService service) {
        this.service = service;
    }

    @QueryMapping
    public List<AccessRequestDto> accessRequests(@Argument final @Nullable Boolean handled) {
        return service.list(handled);
    }

    @MutationMapping
    public AccessRequestDto accessRequestHandle(@Argument final UUID id, @Argument final @Nullable Boolean handled) {
        return service.handle(id, !Boolean.FALSE.equals(handled));
    }

    @MutationMapping
    public @Nullable Void accessRequestCreate(@Argument final AccessRequestInput input) {
        service.create(input);
        return null;
    }
}
