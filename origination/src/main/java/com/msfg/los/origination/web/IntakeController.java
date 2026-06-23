package com.msfg.los.origination.web;

import com.msfg.los.origination.service.IntakeService;
import com.msfg.los.origination.web.dto.IntakeRequest;
import com.msfg.los.origination.web.dto.IntakeResult;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Borrower funnel hand-off (Phase A4): {@code POST /api/loans/intake} → 200 with the loan's
 * identifiers. <strong>Idempotent</strong> on {@code sourceLeadId}: a first call creates the loan +
 * primary borrower (linked to the caller's Cognito sub); a re-POST with the same lead id returns the
 * SAME loan and never duplicates — so 200 (not 201) on every call. The role gate
 * ({@code BORROWER} + staff) lives in {@code SecurityConfig}; the borrower→sub link is enforced in
 * {@link IntakeService#intake}.
 */
@RestController
@RequestMapping("/api/loans/intake")
public class IntakeController {

    private final IntakeService service;

    public IntakeController(IntakeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IntakeResult>> intake(@Valid @RequestBody IntakeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.intake(req)));
    }
}
