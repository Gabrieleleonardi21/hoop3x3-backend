package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Tappa nello stesso formato del tipo `Tappa` del frontend, usata sia in ingresso
 * (POST/PUT) che in uscita. I blocchi di gioco viaggiano come JSON grezzo (JsonNode):
 * il server li valida come struttura (array/oggetto) e li salva in JSONB.
 */
public record TappaDTO(
        @NotNull UUID id,
        @NotBlank String nome,
        String luogo,
        String data,
        @Min(1) @Max(32) int nGironi,
        @NotNull @Valid RegoleDTO regole,
        @NotNull JsonNode squadre,
        JsonNode gironi,
        @NotNull JsonNode partite,
        JsonNode video,
        Boolean conclusa,
        JsonNode bracket
) {}
