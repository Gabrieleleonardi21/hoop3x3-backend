package com.hoop3x3.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Corpo della pubblicazione in archivio: la tappa completa e il nome della lega di provenienza */
public record PubblicaTappaDTO(@NotNull @Valid TappaDTO tappa, @NotBlank String lega) {}
