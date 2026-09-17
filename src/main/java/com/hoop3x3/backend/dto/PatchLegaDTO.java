package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PatchLegaDTO(@NotBlank @Size(max = 120) String nome) {}
