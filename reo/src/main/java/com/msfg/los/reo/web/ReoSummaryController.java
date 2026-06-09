package com.msfg.los.reo.web;

import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.reo.service.ReoSummaryService;
import com.msfg.los.reo.web.dto.ReoSummaryResponse;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/reo")
public class ReoSummaryController {

    private final ReoSummaryService service;

    public ReoSummaryController(ReoSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<ReoSummaryResponse> summary(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.summarize(loanId));
    }
}
