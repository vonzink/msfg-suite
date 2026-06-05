package com.msfg.los.platform.pii;
import com.msfg.los.platform.error.ValidationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SsnSupportTest {
    @Test void normalizesDashedAndPlain() {
        assertThat(SsnSupport.normalize("123-45-6789")).isEqualTo("123456789");
        assertThat(SsnSupport.normalize(" 123 45 6789 ")).isEqualTo("123456789");
    }
    @Test void rejectsBadFormatAndInvalidAreas() {
        assertThatThrownBy(() -> SsnSupport.normalize("12-345-6789")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SsnSupport.normalize("000-12-3456")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SsnSupport.normalize("666-12-3456")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SsnSupport.normalize("900-12-3456")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SsnSupport.normalize("123-00-6789")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SsnSupport.normalize("123-45-0000")).isInstanceOf(ValidationException.class);
    }
    @Test void last4AndMask() {
        assertThat(SsnSupport.last4("123456789")).isEqualTo("6789");
        assertThat(SsnSupport.maskedDisplay("123456789")).isEqualTo("•••-••-6789");
        assertThat(SsnSupport.last4(null)).isNull();
        assertThat(SsnSupport.maskedDisplay(null)).isNull();
    }
}
