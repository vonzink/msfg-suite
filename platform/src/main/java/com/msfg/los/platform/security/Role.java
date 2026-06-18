package com.msfg.los.platform.security;
public enum Role { LO, PROCESSOR, UNDERWRITER, CLOSER, MANAGER, ADMIN, PLATFORM_ADMIN;
    public String authority() { return "ROLE_" + name(); }
}
