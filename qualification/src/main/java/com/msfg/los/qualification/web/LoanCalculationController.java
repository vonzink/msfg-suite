package com.msfg.los.qualification.web;

import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.qualification.service.LoanCalculationService;
import com.msfg.los.qualification.web.dto.LoanCalculationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}")
public class LoanCalculationController {

    private final LoanCalculationService service;

    public LoanCalculationController(LoanCalculationService service) {
        this.service = service;
    }

    @GetMapping("/calculations")
    public ApiResponse<LoanCalculationResponse> calculations(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.calculate(loanId));
    }
}
