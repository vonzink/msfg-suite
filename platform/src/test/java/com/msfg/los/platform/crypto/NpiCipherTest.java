package com.msfg.los.platform.crypto;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpiCipherTest {
    private final NpiCipher cipher = new NpiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    @Test void roundTrips() {
        String ct = cipher.encrypt("123-45-6789");
        assertThat(ct).isNotEqualTo("123-45-6789");
        assertThat(cipher.decrypt(ct)).isEqualTo("123-45-6789");
    }
    @Test void producesDifferentCiphertextEachTime() {
        assertThat(cipher.encrypt("secret")).isNotEqualTo(cipher.encrypt("secret"));
    }
    @Test void rejectsTamperedCiphertext() {
        String ct = cipher.encrypt("secret");
        String tampered = ct.substring(0, ct.length() - 2) + (ct.endsWith("A") ? "B" : "A") + "=";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }
}
