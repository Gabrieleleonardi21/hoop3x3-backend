package com.hoop3x3.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Richiesta di lettura di un foglio: la foto come data URL (data:image/jpeg;base64,...). Niente @Size: il tetto (1.500.000
 * caratteri di base64) e il formato li controlla FoglioService, che dà i messaggi del contratto. Con un @Size il campo
 * entrerebbe nella tabella CampiDiTesto, che prova a mandare "x" ripetuto fino al tetto: non è un'immagine
 */
public record FoglioRequestDTO(@NotBlank String immagine) {}
