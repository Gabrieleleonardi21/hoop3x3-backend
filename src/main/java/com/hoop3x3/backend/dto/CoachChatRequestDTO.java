package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** Richiesta del Coach AI: messaggi e tool nel formato OpenAI, inoltrati a Groq così come sono */
public record CoachChatRequestDTO(@NotNull JsonNode messages, JsonNode tools) {}
