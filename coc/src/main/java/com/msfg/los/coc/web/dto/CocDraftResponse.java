package com.msfg.los.coc.web.dto;

import com.msfg.los.coc.domain.CocDraft;
import com.msfg.los.coc.domain.CocReason;
import com.msfg.los.coc.domain.FeeChange;
import com.msfg.los.coc.domain.StructureChange;

import java.time.LocalDate;
import java.util.List;

public record CocDraftResponse(
        LocalDate dateOfDiscovery,
        CocReason reason,
        List<StructureChange> structureChanges,
        List<FeeChange> feeChanges
) {

    public static CocDraftResponse from(CocDraft draft) {
        return new CocDraftResponse(
                draft.getDateOfDiscovery(),
                draft.getReason(),
                draft.getStructureChanges(),
                draft.getFeeChanges()
        );
    }

    public static CocDraftResponse empty() {
        return new CocDraftResponse(null, null, List.of(), List.of());
    }
}
