package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Nuova password con il token del link. Il token è di 43 caratteri: il tetto evita solo di calcolare l'hash di testi enormi.
 * La password ha le regole della registrazione (le costanti di RegisterRequestDTO); i 72 byte li controlla il servizio.
 */
public record ReimpostaPasswordDTO(
        @NotBlank @Size(max = 64) String token,
        @NotBlank
        @Size(min = RegisterRequestDTO.PASSWORD_MIN, message = RegisterRequestDTO.PASSWORD_CORTA)
        @Size(max = RegisterRequestDTO.PASSWORD_MAX, message = RegisterRequestDTO.PASSWORD_LUNGA) String nuovaPassword
) {}
