package com.msfg.los.coc.web.dto;

import com.msfg.los.coc.domain.CocReason;
import com.msfg.los.coc.domain.FeeChange;
import com.msfg.los.coc.domain.StructureChange;

import java.time.LocalDate;
import java.util.List;

public record CocDraftRequest(
        LocalDate dateOfDiscovery,
        CocReason reason,
        List<StructureChange> structureChanges,
        List<FeeChange> feeChanges
) {}
