package com.msfg.los.tenancy.web.dto;
import com.msfg.los.tenancy.domain.Organization;
import com.msfg.los.tenancy.domain.OrgStatus;
import java.util.HashMap; import java.util.Map; import java.util.UUID;
public record OrgResponse(UUID id, String name, String slug, OrgStatus status, Map<String,Object> settings) {
    public static OrgResponse from(Organization o) {
        // Defensive copy — don't alias the entity's live settings map into the response.
        return new OrgResponse(o.getId(), o.getName(), o.getSlug(), o.getStatus(),
            new HashMap<>(o.getSettings()));
    }
}
