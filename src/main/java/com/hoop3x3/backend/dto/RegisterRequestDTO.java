package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequestDTO(
        @NotBlank @Size(min = 2, max = 80) String name,
        @Email @NotBlank @Size(max = 255) String email,
        // Minimo e massimo hanno messaggi propri. 72 è il limite di BCrypt, che conta byte: lo controlla UtenteService.register
        @NotBlank
        @Size(min = 8, message = "la password deve avere almeno 8 caratteri")
        @Size(max = 72, message = "la password può avere al massimo 72 caratteri") String password
) {}
