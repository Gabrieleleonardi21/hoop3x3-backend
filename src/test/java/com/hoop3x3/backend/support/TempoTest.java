package com.hoop3x3.backend.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-16: il server scrive e legge le date in UTC, quindi il `ts` dell'API non dipende dal fuso della macchina */
class TempoTest {

    private final TimeZone fusoDiPrima = TimeZone.getDefault();

    @AfterEach
    void rimettiIlFuso() {
        TimeZone.setDefault(fusoDiPrima);
    }

    @Test
    void iMillisecondiNonDipendonoDalFusoDelServer() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"));
        long ts = Tempo.inMillisecondi(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(ts).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    }

    // Prima di BE-16 lo stesso istante locale dava un ts diverso secondo il fuso del server (a Roma d'autunno le 12:00 erano le
    // 10:00 UTC): ora la data del database è UTC in qualunque fuso, millisecondi compresi
    @Test
    void loStessoIstanteDaLoStessoTsInDueFusiDiversi() {
        LocalDateTime data = LocalDateTime.of(2026, 10, 6, 12, 0, 0, 123_000_000);
        long atteso = Instant.parse("2026-10-06T12:00:00.123Z").toEpochMilli();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"));
        assertThat(Tempo.inMillisecondi(data)).isEqualTo(atteso);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertThat(Tempo.inMillisecondi(data)).isEqualTo(atteso);
    }

    @Test
    void adessoEUtcAncheConUnAltroFuso() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"));
        Instant prima = Instant.now();
        Instant letto = Tempo.adesso().toInstant(ZoneOffset.UTC);
        assertThat(Duration.between(prima, letto).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
