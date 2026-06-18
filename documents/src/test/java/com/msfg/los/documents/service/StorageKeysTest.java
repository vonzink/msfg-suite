package com.msfg.los.documents.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit matrix for {@link StorageKeys} — pure filename sanitization + key layout. No Spring context.
 */
class StorageKeysTest {

    @Test
    void sanitizeKeepsSafeChars() {
        assertThat(StorageKeys.sanitizeFilename("W-2_2024.pdf")).isEqualTo("W-2_2024.pdf");
    }

    @Test
    void sanitizeCollapsesUnsafeRunsToSingleUnderscore() {
        assertThat(StorageKeys.sanitizeFilename("my file (final)!!.pdf")).isEqualTo("my_file_final_.pdf");
    }

    @Test
    void sanitizeTakesBasenameOnly() {
        assertThat(StorageKeys.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(StorageKeys.sanitizeFilename("C:\\Users\\me\\doc.pdf")).isEqualTo("doc.pdf");
    }

    @Test
    void sanitizeStripsLeadingDots() {
        assertThat(StorageKeys.sanitizeFilename("...hidden.txt")).isEqualTo("hidden.txt");
        assertThat(StorageKeys.sanitizeFilename("..")).isEqualTo("file");
    }

    @Test
    void sanitizeDefaultsToFileWhenNothingSafe() {
        assertThat(StorageKeys.sanitizeFilename("///")).isEqualTo("file");
        assertThat(StorageKeys.sanitizeFilename(null)).isEqualTo("file");
        assertThat(StorageKeys.sanitizeFilename("   ")).isEqualTo("file");
    }

    @Test
    void sanitizeCapsAt200PreservingShortExtension() {
        String longStem = "a".repeat(500) + ".pdf";
        String out = StorageKeys.sanitizeFilename(longStem);
        assertThat(out).hasSizeLessThanOrEqualTo(200);
        assertThat(out).endsWith(".pdf");
    }

    @Test
    void buildProducesApplicationsLayout() {
        UUID loanId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID docId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String key = StorageKeys.build(loanId, "lo", "W-2", docId, "pay stub.pdf");
        assertThat(key).isEqualTo(
                "applications/11111111-1111-1111-1111-111111111111/lo/W-2/"
                        + "22222222-2222-2222-2222-222222222222-pay_stub.pdf");
    }

    @Test
    void buildDefaultsTypeToOtherWhenNull() {
        UUID loanId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        String key = StorageKeys.build(loanId, "borrower", null, docId, "x.pdf");
        assertThat(key).contains("/borrower/Other/");
    }
}
