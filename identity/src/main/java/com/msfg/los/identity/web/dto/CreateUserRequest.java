package com.msfg.los.identity.web.dto;

/** Create-user request from the LO/Admin user-administration screen. */
public record CreateUserRequest(String email, String name, String role) {}
