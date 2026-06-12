package com.msfg.los.aus.domain;

// DU's six values + LPA's risk classes (ACCEPT/CAUTION) — raw vendor strings are stored alongside, this enum is the normalized view.
public enum AusRecommendation {
    APPROVE_ELIGIBLE,
    APPROVE_INELIGIBLE,
    REFER_WITH_CAUTION,
    REFER_INELIGIBLE,
    OUT_OF_SCOPE,
    ACCEPT,
    CAUTION,
    ERROR
}
