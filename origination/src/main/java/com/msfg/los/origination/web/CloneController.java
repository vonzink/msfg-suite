package com.msfg.los.origination.web;

import com.msfg.los.origination.service.CloneService;
import com.msfg.los.origination.web.dto.CloneResult;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "Copy to new" clone (Phase 2 T7). {@code POST /api/loans/{id}/clone} → 201 with the new loan's
 * identifiers. The role gate ({@code LO/PROCESSOR/MANAGER/ADMIN}) lives in {@code SecurityConfig}; the
 * owning-LO access check is enforced in {@link CloneService#clone} via {@code LoanAccessGuard}.
 */
@RestController
@RequestMapping("/api/loans/{id}/clone")
public class CloneController {

    private final CloneService service;

    public CloneController(CloneService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CloneResult>> clone(@PathVariable UUID id) {
        CloneResult result = service.clone(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }
}
