package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cambio della password dal profilo. L'attuale non ha limiti di lunghezza: si confronta con l'hash e non si salva. La nuova ha
 * le regole della registrazione (le costanti di RegisterRequestDTO), come quella del recupero; i 72 byte li controlla il servizio.
 */
public record CambioPasswordDTO(
        @NotBlank String passwordAttuale,
        @NotBlank
        @Size(min = RegisterRequestDTO.PASSWORD_MIN, message = RegisterRequestDTO.PASSWORD_CORTA)
        @Size(max = RegisterRequestDTO.PASSWORD_MAX, message = RegisterRequestDTO.PASSWORD_LUNGA) String nuovaPassword
) {}
