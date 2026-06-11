package com.msfg.los.platform.web;

import com.msfg.los.platform.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsDomainExceptionToStatusAndCode() {
        ResponseEntity<ApiError> resp = handler.handleDomain(new NotFoundException("Loan", "abc"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().code()).isEqualTo("NOT_FOUND");
        assertThat(resp.getBody().message()).contains("abc");
    }

    @Test
    void mapsDataIntegrityViolationTo409Conflict() {
        ResponseEntity<ApiError> resp = handler.handleDataIntegrity(
                new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().code()).isEqualTo("CONFLICT");
        assertThat(resp.getBody().fields()).isEmpty();
    }

    @Test
    void mapsHttpMessageNotReadableTo400ValidationError() {
        ResponseEntity<ApiError> resp = handler.handleNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error", new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(resp.getBody().message()).isEqualTo("Malformed request body");
        assertThat(resp.getBody().fields()).isEmpty();
    }
}
