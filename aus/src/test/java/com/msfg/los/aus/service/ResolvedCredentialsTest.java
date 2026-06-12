package com.msfg.los.aus.service;

import com.msfg.los.aus.domain.CredentialSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedCredentialsTest {

    @Test
    void toStringNeverPrintsSecrets() {
        var creds = new ResolvedCredentials(CredentialSource.LOAN, "INST-1", "SS-9", "TPO-7", "BR-3",
                "user-name-1", "topsecret-pw-1", "CPC", "AFF", "credit-user-2", "credit-pw-2");

        String s = creds.toString();

        // Secrets (and full usernames — the cardinal rule treats them as sensitive) must never ride out.
        assertThat(s)
                .contains("REDACTED")
                .doesNotContain("topsecret-pw-1")
                .doesNotContain("user-name-1")
                .doesNotContain("credit-pw-2")
                .doesNotContain("credit-user-2");

        // Non-secret identity fields stay visible for debuggability.
        assertThat(s).contains("LOAN").contains("INST-1").contains("SS-9");
    }
}
