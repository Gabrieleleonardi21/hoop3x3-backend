package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequestDTO(
        @NotBlank @Size(min = 2, max = 80) String name,
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 72, message = "la password deve avere almeno 8 caratteri") String password
) {}
