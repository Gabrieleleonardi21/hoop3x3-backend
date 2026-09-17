package com.hoop3x3.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** `tappe` è opzionale: valorizzato solo dall'import di una lega da file JSON */
public record NuovaLegaDTO(@NotBlank @Size(max = 120) String nome, List<@Valid TappaDTO> tappe) {}
