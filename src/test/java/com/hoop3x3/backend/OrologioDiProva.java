package com.hoop3x3.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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

    /** Il secondo 20 di un minuto, a metà giornata: al minuto dopo mancano 40 secondi, a mezzanotte UTC 13 ore, 59 minuti e 40 */
    public static final Instant INIZIO = Instant.parse("2026-10-06T10:00:20Z");

    /**
     * Mette un orologio di prova al posto di quello vero nei test che caricano il contesto di Spring: basta
     * {@code @Import(OrologioDiProva.Configurazione.class)} sulla classe e {@code @Autowired OrologioDiProva orologio}. I
     * contatori dei limiti vivono nel contesto, che i test della classe condividono: ognuno chiama {@link #giornoNuovo()}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configurazione {
        @Bean
        @Primary
        OrologioDiProva orologioDiProva() {
            return new OrologioDiProva();
        }
    }

    private volatile Instant adesso = INIZIO;
    private final AtomicReference<Runnable> allaProssimaLettura = new AtomicReference<>();
    private int giorniUsati;

    /** Sposta l'orologio in avanti */
    public void avanza(Duration durata) {
        adesso = adesso.plus(durata);
    }

    /** Rimette l'orologio sull'istante dato, anche indietro: l'orologio di sistema può tornare indietro */
    public void imposta(Instant istante) {
        adesso = istante;
    }

    /**
     * Riparte da INIZIO di un giorno che questo orologio non ha ancora usato: il minuto e il giorno dei contatori sono nuovi,
     * e il test non trova i conteggi di quelli che l'hanno preceduto nello stesso contesto di Spring.
     */
    public void giornoNuovo() {
        imposta(INIZIO.plus(Duration.ofDays(++giorniUsati)));
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
