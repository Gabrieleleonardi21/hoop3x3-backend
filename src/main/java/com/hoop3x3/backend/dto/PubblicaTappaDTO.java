package com.hoop3x3.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpo della pubblicazione in archivio: la tappa completa e il nome della lega di provenienza (colonna lega_nome da 120) */
public record PubblicaTappaDTO(@NotNull @Valid TappaDTO tappa, @NotBlank @Size(max = 120) String lega) {}
