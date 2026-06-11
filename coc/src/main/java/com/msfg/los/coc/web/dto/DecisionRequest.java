package com.msfg.los.coc.web.dto;

import com.msfg.los.coc.domain.CocDecision;
import jakarta.validation.constraints.NotNull;

public record DecisionRequest(
        @NotNull CocDecision decision
) {}
