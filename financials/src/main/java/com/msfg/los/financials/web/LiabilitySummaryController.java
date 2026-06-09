package com.msfg.los.financials.web;

import com.msfg.los.financials.service.LiabilitySummaryService;
import com.msfg.los.financials.web.dto.LiabilitySummaryResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/liabilities")
public class LiabilitySummaryController {

    private final LiabilitySummaryService service;

    public LiabilitySummaryController(LiabilitySummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<LiabilitySummaryResponse> summary(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.summarize(loanId));
    }
}
