package com.msfg.los.declarations.web.dto;

import com.msfg.los.declarations.domain.ApplicationTakenMethod;
import com.msfg.los.declarations.domain.BorrowerDemographics;
import com.msfg.los.declarations.domain.Ethnicity;
import com.msfg.los.declarations.domain.Race;
import com.msfg.los.declarations.domain.Sex;

import java.util.LinkedHashSet;
import java.util.Set;

public record DemographicsResponse(
        String id,
        Set<Ethnicity> ethnicity,
        Set<Race> race,
        Sex sex,
        Boolean collectedByVisualObservationOrSurname,
        ApplicationTakenMethod applicationTakenMethod
) {
    /** Maps a persisted entity to the response. */
    public static DemographicsResponse from(BorrowerDemographics e) {
        if (e == null) {
            return new DemographicsResponse(null, new LinkedHashSet<>(), new LinkedHashSet<>(),
                    null, null, null);
        }
        return new DemographicsResponse(
                e.getId() != null ? e.getId().toString() : null,
                e.getEthnicity() != null ? e.getEthnicity() : new LinkedHashSet<>(),
                e.getRace() != null ? e.getRace() : new LinkedHashSet<>(),
                e.getSex(),
                e.getCollectedByVisualObservationOrSurname(),
                e.getApplicationTakenMethod()
        );
    }
}
