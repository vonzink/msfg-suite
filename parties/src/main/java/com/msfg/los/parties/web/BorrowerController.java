package com.msfg.los.parties.web;

import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.parties.web.dto.AddBorrowerRequest;
import com.msfg.los.parties.web.dto.BorrowerResponse;
import com.msfg.los.parties.web.dto.RevealSsnRequest;
import com.msfg.los.parties.web.dto.RevealSsnResponse;
import com.msfg.los.parties.web.dto.UpdateBorrowerRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers")
public class BorrowerController {

    private final BorrowerService service;

    public BorrowerController(BorrowerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BorrowerResponse>> add(
            @PathVariable UUID loanId,
            @Valid @RequestBody AddBorrowerRequest req) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(BorrowerResponse.from(service.add(loanId, req))));
    }

    @GetMapping
    public ApiResponse<List<BorrowerResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(BorrowerResponse::from).toList());
    }

    @PatchMapping("/{borrowerId}")
    public ApiResponse<BorrowerResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody UpdateBorrowerRequest req) {
        return ApiResponse.ok(BorrowerResponse.from(service.update(loanId, borrowerId, req)));
    }

    @DeleteMapping("/{borrowerId}")
    public ResponseEntity<Void> delete(@PathVariable UUID loanId, @PathVariable UUID borrowerId) {
        service.delete(loanId, borrowerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{borrowerId}/reveal-ssn")
    public ApiResponse<RevealSsnResponse> revealSsn(
            @PathVariable UUID loanId, @PathVariable UUID borrowerId,
            @Valid @RequestBody RevealSsnRequest req) {
        return ApiResponse.ok(new RevealSsnResponse(service.revealSsn(loanId, borrowerId, req.reason())));
    }
}
