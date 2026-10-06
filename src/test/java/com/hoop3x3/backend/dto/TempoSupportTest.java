package com.hoop3x3.backend.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/** Il `ts` dell'API: le date del database (senza fuso) diventano i millisecondi epoch letti nel fuso del server. */
class TempoSupportTest {

    // A Roma d'autunno (prima del 25 ottobre) l'ora legale è UTC+2: le 12:00:00.123 locali sono le 10:00:00.123 UTC
    @Test
    void laDataDelDatabaseSiLeggeNelFusoDelServer() {
        TimeZone prima = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"));
        try {
            long ts = TempoSupport.inMillisecondi(LocalDateTime.of(2026, 10, 6, 12, 0, 0, 123_000_000));

            assertThat(ts).isEqualTo(Instant.parse("2026-10-06T10:00:00.123Z").toEpochMilli());
        } finally {
            TimeZone.setDefault(prima);
        }
    }

    // Lo stesso istante locale in un altro fuso è un altro istante: la conversione dipende dal fuso del server e non è fissa
    @Test
    void conUnAltroFusoLoStessoIstanteLocaleDaUnTsDiverso() {
        TimeZone prima = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            long ts = TempoSupport.inMillisecondi(LocalDateTime.of(2026, 10, 6, 12, 0, 0));

            assertThat(ts).isEqualTo(Instant.parse("2026-10-06T12:00:00Z").toEpochMilli());
        } finally {
            TimeZone.setDefault(prima);
        }
    }
}
