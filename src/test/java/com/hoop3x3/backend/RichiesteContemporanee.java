package com.hoop3x3.backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Richieste che si intrecciano sul database vero, provate senza pause scelte a caso: il test sa quando una richiesta è ferma
 * ad aspettare un lock (lo chiede a PostgreSQL) e quando una transazione guarda il database come lo vedrebbe un'altra
 * richiesta. Le classi di integrazione girano una alla volta, quindi l'unica richiesta che può aspettare un lock è quella
 * del test in corso.
 */
public final class RichiesteContemporanee {

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transazioni;

    public RichiesteContemporanee(JdbcTemplate jdbc, PlatformTransactionManager transazioni) {
        this.jdbc = jdbc;
        this.transazioni = transazioni;
    }

    /**
     * Esegue `azione` in una transazione nuova, quindi su un'altra connessione, mentre quella del test resta sospesa.
     * Ogni transazione nuova parte da una lettura aggiornata, anche di pg_stat_activity (dentro una transazione resterebbe
     * quella della prima lettura).
     */
    public <T> T daUnAltraConnessione(Supplier<T> azione) {
        DefaultTransactionDefinition nuova = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transazioni, nuova).execute(transazione -> azione.get());
    }

    /** Se una richiesta al database è ferma ad aspettare un lock. Guarda tutto il database di prova */
    public boolean unaRichiestaAspettaUnLock() {
        int inAttesa = daUnAltraConnessione(() -> jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class));
        return inAttesa > 0;
    }

    /**
     * Aspetta, al massimo 10 secondi, che la richiesta sia finita o ferma su un lock: se non aspetta nessun lock finisce da
     * sola, e così il test non resta ad aspettare invano né dipende da una pausa scelta a caso.
     */
    public void aspettaFinitaOFermaSuUnLock(Future<?> richiesta) {
        long scadenza = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!richiesta.isDone() && !unaRichiestaAspettaUnLock() && System.nanoTime() < scadenza) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); // pausa di 10 ms tra una lettura e l'altra
        }
    }
}
