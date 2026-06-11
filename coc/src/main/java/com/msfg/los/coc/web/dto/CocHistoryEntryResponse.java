package com.msfg.los.coc.web.dto;

import com.msfg.los.coc.domain.CocHistoryEntry;
import com.msfg.los.coc.domain.CocReason;
import com.msfg.los.coc.domain.CocStatus;
import com.msfg.los.coc.domain.FeeChange;
import com.msfg.los.coc.domain.StructureChange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CocHistoryEntryResponse(
        UUID id,
        LocalDate dateOfDiscovery,
        CocReason reason,
        List<StructureChange> structureChanges,
        List<FeeChange> feeChanges,
        CocStatus status,
        Instant submittedAt,
        String submittedBy,
        String decisionBy,
        Instant decisionDate
) {

    public static CocHistoryEntryResponse from(CocHistoryEntry entry) {
        return new CocHistoryEntryResponse(
                entry.getId(),
                entry.getDateOfDiscovery(),
                entry.getReason(),
                entry.getStructureChanges(),
                entry.getFeeChanges(),
                entry.getStatus(),
                entry.getSubmittedAt(),
                entry.getSubmittedBy(),
                entry.getDecisionBy(),
                entry.getDecisionDate()
        );
    }
}
