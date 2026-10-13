package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Il nome nuovo dal profilo: gli stessi limiti del nome della registrazione (RegisterRequestDTO.name) */
public record CambioNomeDTO(@NotBlank @Size(min = 2, max = 80) String name) {}
