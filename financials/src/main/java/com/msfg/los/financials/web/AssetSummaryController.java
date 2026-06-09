package com.msfg.los.financials.web;

import com.msfg.los.financials.service.AssetSummaryService;
import com.msfg.los.financials.web.dto.AssetSummaryResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/assets")
public class AssetSummaryController {

    private final AssetSummaryService service;

    public AssetSummaryController(AssetSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<AssetSummaryResponse> summary(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.summarize(loanId));
    }
}
