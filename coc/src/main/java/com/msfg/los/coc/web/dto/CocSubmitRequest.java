package com.msfg.los.coc.web.dto;

import com.msfg.los.coc.domain.CocReason;
import com.msfg.los.coc.domain.FeeChange;
import com.msfg.los.coc.domain.StructureChange;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record CocSubmitRequest(
        @NotNull CocReason reason,
        LocalDate dateOfDiscovery,
        List<StructureChange> structureChanges,
        List<FeeChange> feeChanges
) {}
