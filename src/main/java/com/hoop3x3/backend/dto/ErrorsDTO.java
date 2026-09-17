package com.hoop3x3.backend.dto;

import java.time.LocalDateTime;

public record ErrorsDTO(String message, LocalDateTime timestamp) {}
