package com.msfg.los.parties.web.dto;
import jakarta.validation.constraints.NotBlank;
public record RevealSsnRequest(@NotBlank String reason) {}
