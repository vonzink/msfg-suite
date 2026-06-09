package com.msfg.los.reo.web;

import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.reo.service.ReoService;
import com.msfg.los.reo.web.dto.AddReoRequest;
import com.msfg.los.reo.web.dto.ReoResponse;
import com.msfg.los.reo.web.dto.UpdateReoRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/reo")
public class ReoController {

    private final ReoService service;

    public ReoController(ReoService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReoResponse>> add(
            @PathVariable UUID loanId,
            @Valid @RequestBody AddReoRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ReoResponse.from(service.add(loanId, req))));
    }

    @GetMapping
    public ApiResponse<List<ReoResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(ReoResponse::from).toList());
    }

    @PatchMapping("/{reoId}")
    public ApiResponse<ReoResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID reoId,
            @Valid @RequestBody UpdateReoRequest req) {
        return ApiResponse.ok(ReoResponse.from(service.update(loanId, reoId, req)));
    }

    @DeleteMapping("/{reoId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID reoId) {
        service.delete(loanId, reoId);
        return ResponseEntity.noContent().build();
    }
}
