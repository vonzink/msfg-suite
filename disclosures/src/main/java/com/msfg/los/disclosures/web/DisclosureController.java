package com.msfg.los.disclosures.web;

import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.service.DisclosureIssuanceService;
import com.msfg.los.disclosures.web.dto.DisclosureResponse;
import com.msfg.los.disclosures.web.dto.IssueDisclosureRequest;
import com.msfg.los.disclosures.web.dto.RecordReceiptRequest;
import com.msfg.los.disclosures.web.dto.TimingResponse;
import com.msfg.los.disclosures.web.dto.ToleranceResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Issuance endpoints for TRID disclosures (Loan Estimate now; Closing Disclosure in Task 10). */
@RestController
public class DisclosureController {

    private final DisclosureIssuanceService service;

    public DisclosureController(DisclosureIssuanceService service) {
        this.service = service;
    }

    @PostMapping("/api/loans/{loanId}/disclosures/loan-estimate")
    public ResponseEntity<ApiResponse<DisclosureResponse>> issueLoanEstimate(
            @PathVariable UUID loanId,
            @RequestBody(required = false) IssueDisclosureRequest req) {
        DisclosureResponse body = DisclosureResponse.from(
                service.issue(loanId, DisclosureKind.LOAN_ESTIMATE, req));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PostMapping("/api/loans/{loanId}/disclosures/closing-disclosure")
    public ResponseEntity<ApiResponse<DisclosureResponse>> issueClosingDisclosure(
            @PathVariable UUID loanId,
            @RequestBody(required = false) IssueDisclosureRequest req) {
        DisclosureResponse body = DisclosureResponse.from(
                service.issue(loanId, DisclosureKind.CLOSING_DISCLOSURE, req));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @PostMapping("/api/loans/{loanId}/disclosures/{disclosureId}/receipt")
    public ApiResponse<DisclosureResponse> recordReceipt(
            @PathVariable UUID loanId,
            @PathVariable UUID disclosureId,
            @RequestBody RecordReceiptRequest req) {
        return ApiResponse.ok(DisclosureResponse.from(
                service.recordReceipt(loanId, disclosureId, req.receivedAt())));
    }

    @GetMapping("/api/loans/{loanId}/disclosures/timing")
    public ApiResponse<TimingResponse> timing(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.timing(loanId));
    }

    @GetMapping("/api/loans/{loanId}/disclosures/tolerance")
    public ApiResponse<ToleranceResponse> tolerance(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.tolerance(loanId));
    }

    @GetMapping("/api/loans/{loanId}/disclosures")
    public ApiResponse<List<DisclosureResponse>> history(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.history(loanId).stream().map(DisclosureResponse::from).toList());
    }

    @GetMapping("/api/loans/{loanId}/disclosures/{disclosureId}")
    public ApiResponse<DisclosureResponse> get(
            @PathVariable UUID loanId, @PathVariable UUID disclosureId) {
        return ApiResponse.ok(DisclosureResponse.from(service.get(loanId, disclosureId)));
    }
}
