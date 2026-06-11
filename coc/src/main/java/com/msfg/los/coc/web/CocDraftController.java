package com.msfg.los.coc.web;

import com.msfg.los.coc.service.CocDraftService;
import com.msfg.los.coc.web.dto.CocDraftRequest;
import com.msfg.los.coc.web.dto.CocDraftResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/coc")
public class CocDraftController {

    private final CocDraftService service;

    public CocDraftController(CocDraftService service) {
        this.service = service;
    }

    @GetMapping("/draft")
    public ApiResponse<CocDraftResponse> get(@PathVariable UUID loanId) {
        var draft = service.get(loanId);
        return ApiResponse.ok(draft == null ? CocDraftResponse.empty() : CocDraftResponse.from(draft));
    }

    @PutMapping("/draft")
    public ApiResponse<CocDraftResponse> save(
            @PathVariable UUID loanId,
            @Valid @RequestBody CocDraftRequest req) {
        return ApiResponse.ok(CocDraftResponse.from(service.save(loanId, req)));
    }
}
