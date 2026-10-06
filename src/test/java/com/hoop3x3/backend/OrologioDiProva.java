package com.hoop3x3.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Orologio dei test sui limiti di frequenza: sta fermo sull'istante dato e si sposta solo quando glielo si dice, così le
 * finestre di un minuto o di un giorno si provano senza aspettare e un test non dipende da quando scocca il minuto vero.
 * Si può leggere da più thread: l'istante è un solo campo volatile.
 */
public final class OrologioDiProva extends Clock {

    private volatile Instant adesso;

    /** @param istante in formato ISO con il fuso, per esempio {@code 2026-10-06T10:00:20Z} */
    public OrologioDiProva(String istante) {
        this.adesso = Instant.parse(istante);
    }

    /** Sposta l'orologio in avanti */
    public void avanza(Duration durata) {
        adesso = adesso.plus(durata);
    }

    @Override
    public Instant instant() {
        return adesso;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zona) {
        throw new UnsupportedOperationException("I limiti contano sui secondi dell'epoca: il fuso non serve");
    }
}
