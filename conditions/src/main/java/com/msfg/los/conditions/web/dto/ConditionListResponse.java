package com.msfg.los.conditions.web.dto;

import java.util.List;

/** Conditions list payload: total count + the ordered condition rows. */
public record ConditionListResponse(int count, List<ConditionResponse> conditions) {

    public static ConditionListResponse of(List<ConditionResponse> rows) {
        return new ConditionListResponse(rows.size(), rows);
    }
}
