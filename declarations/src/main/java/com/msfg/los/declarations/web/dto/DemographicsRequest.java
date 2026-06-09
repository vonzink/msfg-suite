package com.msfg.los.declarations.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.msfg.los.declarations.domain.ApplicationTakenMethod;
import com.msfg.los.declarations.domain.Ethnicity;
import com.msfg.los.declarations.domain.Race;
import com.msfg.los.declarations.domain.Sex;

import java.util.LinkedHashSet;
import java.util.Set;

public record DemographicsRequest(
        @JsonDeserialize(as = LinkedHashSet.class) Set<Ethnicity> ethnicity,
        @JsonDeserialize(as = LinkedHashSet.class) Set<Race> race,
        Sex sex,
        Boolean collectedByVisualObservationOrSurname,
        ApplicationTakenMethod applicationTakenMethod
) {}
