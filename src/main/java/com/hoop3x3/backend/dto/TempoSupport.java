package com.hoop3x3.backend.dto;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** Aiuto dei DTO per le date: il `ts` dell'API, scritto una volta sola. */
public final class TempoSupport {

    private TempoSupport() {}

    /**
     * I millisecondi epoch di una data del database: il `ts` dell'API. Le colonne sono senza fuso (LocalDateTime) e le
     * scrive il server con la sua ora locale, quindi si rileggono nello stesso fuso.
     */
    public static long inMillisecondi(LocalDateTime data) {
        return data.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
