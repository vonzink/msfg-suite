package com.msfg.los.aus.web.dto;

import jakarta.validation.constraints.NotNull;

/** Body of POST /aus/run — which vendor(s) to submit to (ONE_CLICK = DU then LPA). */
public record AusRunRequest(@NotNull AusRunSelection vendor) {}
