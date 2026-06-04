package com.msfg.los.platform.security;
public enum Role { LO, PROCESSOR, UNDERWRITER, CLOSER, ADMIN;
    public String authority() { return "ROLE_" + name(); }
}
