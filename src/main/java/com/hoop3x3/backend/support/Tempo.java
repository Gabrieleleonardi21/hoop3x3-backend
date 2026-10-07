package com.hoop3x3.backend.support;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * L'ora del server, in un posto solo e sempre in UTC (BE-16). Le colonne delle date sono senza fuso (TIMESTAMP, LocalDateTime):
 * il server le scrive e le rilegge in UTC, così il `ts` mandato al client non dipende dal fuso della macchina su cui gira.
 * Su Render il fuso è già UTC; un database scritto prima da un server con un altro fuso ha le date di allora spostate di quella
 * differenza (vedi README).
 */
public final class Tempo {

    private Tempo() {}

    /** Adesso, in UTC: per tutte le date che il server scrive (creazione, modifica, scadenze, timestamp degli errori) */
    public static LocalDateTime adesso() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** I millisecondi epoch di una data del database: il `ts` dell'API */
    public static long inMillisecondi(LocalDateTime data) {
        return data.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
