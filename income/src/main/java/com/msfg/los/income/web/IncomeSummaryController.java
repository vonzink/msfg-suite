package com.msfg.los.income.web;

import com.msfg.los.income.service.IncomeSummaryService;
import com.msfg.los.income.web.dto.IncomeSummaryResponse;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/income")
public class IncomeSummaryController {
    private final IncomeSummaryService service;

    public IncomeSummaryController(IncomeSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<IncomeSummaryResponse> summary(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.summarize(loanId));
    }
}
