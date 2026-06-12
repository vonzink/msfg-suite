package com.msfg.los.aus.web;

import com.msfg.los.aus.service.AusRunService;
import com.msfg.los.aus.web.dto.AusRunRequest;
import com.msfg.los.aus.web.dto.AusRunResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** AUS runs: submit to DU/LPA (ONE_CLICK = both) + newest-first run history. */
@RestController
@RequestMapping("/api/loans/{loanId}/aus")
public class AusRunController {

    private final AusRunService service;

    public AusRunController(AusRunService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<List<AusRunResponse>>> run(@PathVariable UUID loanId,
                                                                 @Valid @RequestBody AusRunRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.run(loanId, req.vendor())));
    }

    @GetMapping("/history")
    public ApiResponse<List<AusRunResponse>> history(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.history(loanId));
    }
}
