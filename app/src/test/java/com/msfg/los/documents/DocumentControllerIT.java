package com.msfg.los.documents;

import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentControllerIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    static final String LO = UUID.randomUUID().toString();

    private RequestPostProcessor lo() {
        return jwt().jwt(j -> j.subject(LO).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private String createLoan() throws Exception {
        var res = mvc.perform(post("/api/loans").with(lo())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loanPurpose\":\"PURCHASE\",\"loanOfficerId\":\"%s\"}".formatted(LO)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    private String uploadDoc(String loanId, String docType, String fileName, byte[] content, String mimeType) throws Exception {
        var mockFile = new MockMultipartFile("file", fileName, mimeType, content);
        var res = mvc.perform(multipart("/api/loans/{l}/documents", loanId)
                        .file(mockFile)
                        .param("documentType", docType)
                        .with(lo()))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.data.id");
    }

    // --- upload → 201 with correct metadata ---

    @Test
    void uploadReturns201WithCorrectMetadata() throws Exception {
        String loanId = createLoan();
        var mockFile = new MockMultipartFile("file", "inv.pdf", "application/pdf", "hello-pdf".getBytes());

        mvc.perform(multipart("/api/loans/{l}/documents", loanId)
                        .file(mockFile)
                        .param("documentType", "INVOICE")
                        .with(lo()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.documentType").value("INVOICE"))
                .andExpect(jsonPath("$.data.fileName").value("inv.pdf"))
                .andExpect(jsonPath("$.data.sizeBytes").value(9));
    }

    // --- list includes uploaded document ---

    @Test
    void listIncludesUploadedDocument() throws Exception {
        // The Phase-1 list returns {count, documents} (confirmed docs only). The legacy multipart
        // upload now lands UPLOADED, so it appears in the new list shape.
        String loanId = createLoan();
        uploadDoc(loanId, "INVOICE", "inv.pdf", "hello-pdf".getBytes(), "application/pdf");

        mvc.perform(get("/api/loans/{l}/documents", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.documents[?(@.documentType == 'INVOICE')].fileName",
                        org.hamcrest.Matchers.hasItem("inv.pdf")));
    }

    // --- binary download round-trip ---

    @Test
    void downloadReturnsExactBytes() throws Exception {
        String loanId = createLoan();
        byte[] payload = "hello-pdf".getBytes();
        var mockFile = new MockMultipartFile("file", "inv.pdf", "application/pdf", payload);

        var uploadRes = mvc.perform(multipart("/api/loans/{l}/documents", loanId)
                        .file(mockFile)
                        .param("documentType", "INVOICE")
                        .with(lo()))
                .andExpect(status().isCreated())
                .andReturn();
        String docId = com.jayway.jsonpath.JsonPath.read(uploadRes.getResponse().getContentAsString(), "$.data.id");

        var downloadRes = mvc.perform(get("/api/loans/{l}/documents/{d}/content", loanId, docId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("inv.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        assertThat(downloadRes.getResponse().getContentAsByteArray()).isEqualTo(payload);
    }

    // --- DELETE → 204, then GET content → 404 ---

    @Test
    void deleteReturns204AndSubsequentDownloadReturns404() throws Exception {
        String loanId = createLoan();
        String docId = uploadDoc(loanId, "INVOICE", "inv.pdf", "hello-pdf".getBytes(), "application/pdf");

        mvc.perform(delete("/api/loans/{l}/documents/{d}", loanId, docId).with(lo()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/loans/{l}/documents/{d}/content", loanId, docId).with(lo()))
                .andExpect(status().isNotFound());
    }

    // --- list shows both uploaded docs (the legacy ?type= list filter was superseded by /search) ---

    @Test
    void listShowsAllConfirmedDocuments() throws Exception {
        String loanId = createLoan();
        uploadDoc(loanId, "INVOICE", "inv.pdf", "hello-pdf".getBytes(), "application/pdf");
        uploadDoc(loanId, "APPRAISAL", "appraisal.pdf", "appraisal-bytes".getBytes(), "application/pdf");

        mvc.perform(get("/api/loans/{l}/documents", loanId).with(lo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.documents[*].fileName",
                        org.hamcrest.Matchers.hasItems("inv.pdf", "appraisal.pdf")));
    }

    // --- empty file → 400 ---

    @Test
    void emptyFileReturns400() throws Exception {
        String loanId = createLoan();
        var emptyFile = new MockMultipartFile("file", "x", "text/plain", new byte[0]);

        mvc.perform(multipart("/api/loans/{l}/documents", loanId)
                        .file(emptyFile)
                        .param("documentType", "INVOICE")
                        .with(lo()))
                .andExpect(status().isBadRequest());
    }

    // --- cross-org → 404 ---

    @Test
    void crossOrgUploadReturns404() throws Exception {
        String loanId = createLoan();
        var otherOrg = jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("org_id", UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));

        var mockFile = new MockMultipartFile("file", "x.pdf", "application/pdf", "bytes".getBytes());
        mvc.perform(multipart("/api/loans/{l}/documents", loanId)
                        .file(mockFile)
                        .param("documentType", "INVOICE")
                        .with(otherOrg))
                .andExpect(status().isNotFound());
    }

    // --- no token → 401 ---

    @Test
    void noTokenReturns401() throws Exception {
        mvc.perform(get("/api/loans/{l}/documents", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
