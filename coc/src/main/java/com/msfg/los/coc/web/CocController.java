package com.msfg.los.coc.web;

import com.msfg.los.coc.service.CocService;
import com.msfg.los.coc.web.dto.CocHistoryEntryResponse;
import com.msfg.los.coc.web.dto.CocSubmitRequest;
import com.msfg.los.coc.web.dto.DecisionRequest;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/coc")
public class CocController {

    private final CocService service;
    private final CurrentUser currentUser;

    public CocController(CocService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<CocHistoryEntryResponse>> submit(
            @PathVariable UUID loanId,
            @Valid @RequestBody CocSubmitRequest req) {
        var entry = service.submit(loanId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(CocHistoryEntryResponse.from(entry)));
    }

    @GetMapping("/history")
    public ApiResponse<List<CocHistoryEntryResponse>> history(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.historyList(loanId).stream()
                .map(CocHistoryEntryResponse::from)
                .toList());
    }

    @PostMapping("/history/{entryId}/decision")
    public ApiResponse<CocHistoryEntryResponse> decide(
            @PathVariable UUID loanId,
            @PathVariable UUID entryId,
            @Valid @RequestBody DecisionRequest req) {
        var entry = service.decide(loanId, entryId, req.decision(), currentUser.roles());
        return ApiResponse.ok(CocHistoryEntryResponse.from(entry));
    }
}
