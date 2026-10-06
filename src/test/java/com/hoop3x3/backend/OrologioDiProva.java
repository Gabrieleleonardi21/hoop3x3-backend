package com.hoop3x3.backend;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orologio dei test sui limiti di frequenza: sta fermo sull'istante dato e si sposta solo quando glielo si dice, così le
 * finestre di un minuto o di un giorno si provano senza aspettare e un test non dipende da quando scocca il minuto vero.
 * Si può leggere da più thread: l'istante è un solo campo volatile.
 */
public final class OrologioDiProva extends Clock {

    private volatile Instant adesso;
    private final AtomicReference<Runnable> allaProssimaLettura = new AtomicReference<>();

    /** @param istante in formato ISO con il fuso, per esempio {@code 2026-10-06T10:00:20Z} */
    public OrologioDiProva(String istante) {
        this.adesso = Instant.parse(istante);
    }

    /** Sposta l'orologio in avanti */
    public void avanza(Duration durata) {
        adesso = adesso.plus(durata);
    }

    /**
     * Rimette l'orologio sull'istante dato. Serve ai test che condividono il contesto di Spring, e quindi i contatori dei
     * limiti: ognuno parte da un giorno diverso dagli altri, in un minuto e in un giorno che nessun altro ha ancora usato.
     */
    public void imposta(Instant istante) {
        adesso = istante;
    }

    /**
     * Alla prossima lettura dell'ora esegue `azione` prima di restituire l'istante letto: simula una richiesta che legge l'ora
     * e poi resta indietro, mentre ne passano altre, magari nel minuto dopo. Con i thread una gara così si vede solo per
     * fortuna; qui succede sempre allo stesso modo.
     */
    public void allaProssimaLettura(Runnable azione) {
        allaProssimaLettura.set(azione);
    }

    @Override
    public Instant instant() {
        Instant letto = adesso;
        Runnable azione = allaProssimaLettura.getAndSet(null); // una volta sola: l'azione stessa può leggere l'ora
        if (azione != null) azione.run();
        return letto;
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
