package com.msfg.los.loan.web.dto;

import com.msfg.los.loan.domain.LoanStatus;
import java.util.List;

public record TransitionsResponse(LoanStatus currentStatus, List<LoanStatus> allowedTransitions) {}
