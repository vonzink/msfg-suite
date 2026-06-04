package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.LoanStatus;
import jakarta.validation.constraints.NotNull;

public record TransitionRequest(@NotNull LoanStatus targetStatus, String reason) {}
