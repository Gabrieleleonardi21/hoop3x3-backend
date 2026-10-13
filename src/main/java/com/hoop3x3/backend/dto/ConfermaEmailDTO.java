package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Conferma del cambio email con il token del link (43 caratteri): il tetto evita solo l'hash di testi enormi, come nel recupero */
public record ConfermaEmailDTO(@NotBlank @Size(max = 64) String token) {}
