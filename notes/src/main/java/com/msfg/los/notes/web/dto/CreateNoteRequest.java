package com.msfg.los.notes.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Create body for a loan note. {@code content} is required (bean-validation + service-side guard). */
public record CreateNoteRequest(@NotBlank String content) {}
