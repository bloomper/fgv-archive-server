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

import nu.fgv.archive.server.file.FileService;
import nu.fgv.archive.server.file.FileService.StoredFile;
import nu.fgv.archive.server.util.AbstractApiTest;
import nu.fgv.archive.server.util.error.ResourceNotFoundException;
import nu.fgv.archive.server.util.error.ShareLinkGoneException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.request.PathParametersSnippet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static nu.fgv.archive.server.util.TestUuids.uuid;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.restdocs.request.RequestDocumentation.queryParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents the file, preview and zip endpoints, for users and through share links.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest({FileController.class, ShareController.class})
class FileHttpApiTest extends AbstractApiTest {

    private static final String TOKEN = "exempel-giltig-delningslank-0000000000000001";

    private final PathParametersSnippet fileId = pathParameters(parameterWithName("id").description("The id of the file"));

    @MockitoBean
    private FileService fileService;
    @MockitoBean
    private DownloadService downloadService;
    @TempDir
    private Path folder;
    private StoredFile file;

    @BeforeEach
    void setUpFile() throws IOException {
        final Path content = Files.write(folder.resolve("file.pdf"), "%PDF-1.7 0123456789".getBytes());
        final Path preview = Files.write(folder.resolve("preview.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        file = new StoredFile(1L, uuid(1), content, preview, "Trumpet 1.pdf", "application/pdf", true, true);
    }

    @Test
    void servesAFileWithByteRanges() throws Exception {
        when(fileService.open(uuid(1))).thenReturn(file);

        mockMvc
                .perform(get("/api/files/{id}", uuid(1)).header(HttpHeaders.AUTHORIZATION, BEARER).header(HttpHeaders.RANGE, "bytes=8-15"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 8-15/19"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andDo(document(
                        "file-get",
                        fileId,
                        queryParameters(parameterWithName("download")
                                .description("`true` for an attachment; else a PDF or an image is shown inline (other types are always attachments). Either way, a fetch of the whole file or of a range from byte 0 is counted, and for restricted files logged")
                                .optional()),
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("The authorization header"),
                                headerWithName(HttpHeaders.RANGE).description("The bytes wanted, e.g. by pdf.js; optional").optional()
                        ),
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("The file's type, detected from its content; `application/octet-stream` for XML, text and notation formats"),
                                headerWithName(HttpHeaders.CONTENT_RANGE).description("The bytes sent, for a range request"),
                                headerWithName(HttpHeaders.ACCEPT_RANGES).description("`bytes`"),
                                headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("`inline` for a PDF or an image, else, or for a download, `attachment`, with the file's title as its name"),
                                headerWithName("Content-Security-Policy").description("`sandbox; default-src 'none'`: nothing in a file runs"),
                                headerWithName("X-Content-Type-Options").description("`nosniff`"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("Private, for ten minutes")
                        ),
                        security(List.of("USER", "EDITOR for restricted files"))
                ));
        // A range further in, as pdf.js asks while reading, is not a download
        verify(downloadService, never()).record(anyList(), isNull());

        mockMvc.perform(get("/api/files/{id}?download=true", uuid(1)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment; filename=\"Trumpet 1.pdf\"")));
        verify(downloadService).record(List.of(file), null);
    }

    @Test
    void readingAFileInThePageIsCountedOnceFromItsStart() throws Exception {
        when(fileService.open(uuid(1))).thenReturn(file);

        mockMvc.perform(get("/api/files/{id}", uuid(1)).header(HttpHeaders.RANGE, "bytes=0-7"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("inline")));
        verify(downloadService).record(List.of(file), null);
    }

    @Test
    void xmlIsNeverShownInline() throws Exception {
        final Path xml = Files.writeString(folder.resolve("score.musicxml"),
                "<score xmlns:h=\"http://www.w3.org/1999/xhtml\"><h:script>alert(1)</h:script></score>");
        final StoredFile stored = new StoredFile(2L, uuid(2), xml, xml, "Partitur.musicxml", "application/vnd.recordare.musicxml+xml", false, false);

        when(fileService.open(uuid(2))).thenReturn(stored);

        mockMvc.perform(get("/api/files/{id}", uuid(2)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"));
    }

    @Test
    void servesAPreview() throws Exception {
        when(fileService.open(uuid(1))).thenReturn(file);

        mockMvc
                .perform(get("/api/files/{id}/preview", uuid(1)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andDo(document("file-preview-get", fileId, secureRequestHeaders, security(List.of("USER", "EDITOR for restricted files"))));
    }

    @Test
    void streamsAVersionAsAZip() throws Exception {
        when(downloadService.versionZip(eq(uuid(2)), any())).thenReturn(new DownloadService.ZipPlan("Ouvertyr - Original - stämmor.zip",
                List.of(new DownloadService.ZipItem("Trumpet 1.pdf", file)), null));

        mockMvc
                .perform(get("/api/versions/{id}/parts/zip", uuid(2)).queryParam("instrumentIds", uuid(11).toString()).queryParam("transpositions", "B_FLAT")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andDo(document("version-parts-zip-get",
                        pathParameters(parameterWithName("id").description("The id of the arrangement version")),
                        queryParameters(
                                parameterWithName("instrumentIds").description("Only parts carrying any of these instruments, comma-separated; optional").optional(),
                                parameterWithName("transpositions").description("Only parts written in any of these pitches, e.g. `B_FLAT,E_FLAT`; optional").optional()),
                        secureRequestHeaders,
                        responseHeaders(headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("`attachment`, named by the title and version")),
                        security(List.of("USER", "EDITOR for restricted versions"))));
        verify(downloadService).record(List.of(file), null);
    }

    @Test
    void servesAFileThroughAShareLink() throws Exception {
        when(downloadService.sharedFile(TOKEN, uuid(1))).thenReturn(new DownloadService.SharedFile(file, 7L));

        mockMvc
                .perform(get("/api/share/{token}/files/{id}?download=true", TOKEN, uuid(1)))
                .andExpect(status().isOk())
                .andDo(document("share-file-get",
                        pathParameters(parameterWithName("token").description("The share link's token"),
                                parameterWithName("id").description("The id of a file the link opens")),
                        noSecurity()));
        verify(downloadService).record(eq(List.of(file)), eq(7L));
    }

    @Test
    void anExpiredShareLinkSaysWhoMadeIt() throws Exception {
        when(downloadService.sharedFile(TOKEN, uuid(1))).thenThrow(new ShareLinkGoneException(false, "Erik"));

        mockMvc
                .perform(get("/api/share/{token}/files/{id}", TOKEN, uuid(1)).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isGone())
                .andDo(document("share-gone",
                        preprocessResponse(prettyPrint()),
                        responseFields(
                                fieldWithPath("code").description("`SHARE_LINK_EXPIRED` or `SHARE_LINK_REVOKED`"),
                                fieldWithPath("message").description("A human-readable explanation, in the request's language"),
                                fieldWithPath("createdByName").description("Who made the link, to ask for a new one").optional()
                        )));
    }

    @Test
    void anUnknownFileIsNotFound() throws Exception {
        when(fileService.open(uuid(9))).thenThrow(new ResourceNotFoundException("error.file.notFound", uuid(9)));

        mockMvc
                .perform(get("/api/files/{id}", uuid(9)).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andDo(document("error", preprocessResponse(prettyPrint()), errorResponseFields));
    }
}
