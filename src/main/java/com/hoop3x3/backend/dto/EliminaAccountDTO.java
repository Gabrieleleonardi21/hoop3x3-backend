package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Eliminazione dell'account con la password attuale. Sta nel corpo di una POST e non di una DELETE: un corpo su una DELETE non
 * è garantito attraverso i proxy (il rewrite di Render)
 */
public record EliminaAccountDTO(@NotBlank String password) {}
