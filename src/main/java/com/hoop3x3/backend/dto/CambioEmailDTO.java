package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Richiesta del cambio email dal profilo: la nuova ha le regole dell'email della registrazione, con la password attuale */
public record CambioEmailDTO(@Email @NotBlank @Size(max = 255) String nuovaEmail, @NotBlank String password) {}
