package com.msfg.los.tenancy.web.dto;
import com.msfg.los.tenancy.domain.OrgStatus;
import java.util.Map;
public record UpdateOrgRequest(String name, OrgStatus status, Map<String,Object> settings) {}
