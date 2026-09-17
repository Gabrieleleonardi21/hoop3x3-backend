package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequestDTO(@Email @NotBlank String email, @NotBlank String password) {}
