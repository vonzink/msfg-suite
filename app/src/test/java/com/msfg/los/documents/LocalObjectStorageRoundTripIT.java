package com.msfg.los.documents;

import com.msfg.los.platform.storage.BlobStoragePort;
import com.msfg.los.platform.storage.ObjectStoragePort;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the local (db-driver) presigned round-trip: presignUpload → authenticated PUT to the
 * returned /api/_local-blob URL → bytes are stored, readable via headSize/sha256 and the GET
 * endpoint. Also proves exactly-one-bean wiring (ObjectStoragePort + BlobStoragePort resolve and
 * are the same LocalObjectStorageAdapter in db mode) by autowiring both without ambiguity.
 */
class LocalObjectStorageRoundTripIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectStoragePort objectStorage;

    @Autowired
    BlobStoragePort blobStorage;

    static final String USER = UUID.randomUUID().toString();

    private RequestPostProcessor staff() {
        return jwt().jwt(j -> j.subject(USER).claim("org_id", DEFAULT_ORG))
                .authorities(new SimpleGrantedAuthority("ROLE_LO"));
    }

    private static String path(String url) {
        return URI.create(url).getRawPath() + (URI.create(url).getRawQuery() == null ? "" : "?" + URI.create(url).getRawQuery());
    }

    @Test
    void presignedPutThenReadBackRoundTrips() throws Exception {
        String key = "applications/" + UUID.randomUUID() + "/lo/W-2/" + UUID.randomUUID() + "-w2.pdf";
        byte[] payload = "hello-presigned-bytes".getBytes(StandardCharsets.UTF_8);

        // 1) presign upload — local adapter returns an absolute /api/_local-blob/{token} URL.
        String uploadUrl;
        try {
            TenantContextHolder.set(UUID.fromString(DEFAULT_ORG));
            uploadUrl = objectStorage.presignUpload(key, "application/pdf", java.time.Duration.ofMinutes(15));
        } finally {
            TenantContextHolder.clear();
        }
        assertThat(uploadUrl).contains("/api/_local-blob/");

        // 2) authenticated PUT of raw bytes to that URL.
        mvc.perform(put(path(uploadUrl)).with(staff())
                        .contentType("application/pdf")
                        .content(payload))
                .andExpect(status().isOk());

        // 3) read back: headSize + sha256 (server-side metadata) within tenant context.
        try {
            TenantContextHolder.set(UUID.fromString(DEFAULT_ORG));
            assertThat(objectStorage.headSize(key)).isEqualTo(payload.length);

            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder expected = new StringBuilder();
            for (byte b : digest) expected.append(String.format("%02x", b));
            assertThat(objectStorage.sha256(key)).isEqualTo(expected.toString());

            // BlobStoragePort and ObjectStoragePort are the same backing store in db mode.
            assertThat(blobStorage.load(key)).isEqualTo(payload);
        } finally {
            TenantContextHolder.clear();
        }

        // 4) download via the presigned-download URL (GET endpoint).
        String downloadUrl;
        try {
            TenantContextHolder.set(UUID.fromString(DEFAULT_ORG));
            downloadUrl = objectStorage.presignDownload(key, "w2.pdf", java.time.Duration.ofMinutes(15));
        } finally {
            TenantContextHolder.clear();
        }
        var res = mvc.perform(get(path(downloadUrl)).with(staff()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(payload);
    }

    @Test
    void headSizeMinusOneForAbsentKey() {
        try {
            TenantContextHolder.set(UUID.fromString(DEFAULT_ORG));
            assertThat(objectStorage.headSize("nope-" + UUID.randomUUID())).isEqualTo(-1L);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Test
    void localBlobPutRequiresAuth() throws Exception {
        String token = com.msfg.los.documents.storage.LocalObjectStorageAdapter.encodeToken("k/" + UUID.randomUUID());
        mvc.perform(put("/api/_local-blob/" + token)
                        .contentType("application/pdf")
                        .content("x".getBytes()))
                .andExpect(status().isUnauthorized());
    }
}
