package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequestDTO(
        @NotBlank @Size(min = 2, max = 80) String name,
        @Email @NotBlank @Size(max = 255) String email,
        // Minimo e massimo hanno messaggi propri. 72 è il limite di BCrypt, che conta byte: lo controlla UtenteService.register
        @NotBlank
        @Size(min = RegisterRequestDTO.PASSWORD_MIN, message = RegisterRequestDTO.PASSWORD_CORTA)
        @Size(max = RegisterRequestDTO.PASSWORD_MAX, message = RegisterRequestDTO.PASSWORD_LUNGA) String password
) {
    // Le regole della password di un account: le usa anche la nuova password del recupero (ReimpostaPasswordDTO), così le
    // due non possono divergere
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 72;
    public static final String PASSWORD_CORTA = "la password deve avere almeno " + PASSWORD_MIN + " caratteri";
    public static final String PASSWORD_LUNGA = "la password può avere al massimo " + PASSWORD_MAX + " caratteri";
}
