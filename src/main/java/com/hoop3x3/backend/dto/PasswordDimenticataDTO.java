package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Richiesta del link di recupero password: l'email ha le stesse regole della registrazione */
public record PasswordDimenticataDTO(@Email @NotBlank @Size(max = 255) String email) {}
