package com.hoop3x3.backend.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contatore di richieste in memoria, a finestra fissa: conta quante richieste fa ogni chiave (un indirizzo IP, un utente)
 * nella finestra in corso e dice di no oltre il massimo.
 * <p>
 * Le finestre sono allineate all'orologio: il minuto finisce al secondo 0 e il giorno a mezzanotte UTC, qualunque sia il
 * momento della prima richiesta. Così per ogni chiave basta un numero e la fine della finestra è la stessa per tutti: il
 * Retry-After si calcola senza altro stato. Il prezzo è che a cavallo di due finestre una chiave può fare fino al doppio
 * del massimo in pochi secondi.
 * <p>
 * Regge le richieste contemporanee (ConcurrentHashMap e operazioni atomiche, nessun blocco). Vive nella memoria di questo
 * server: si azzera al riavvio e, con più istanze del server, ognuna conta per sé (vedi il README).
 */
public class LimiteRichieste {

    /** Lunghezza della finestra: i secondi dell'epoca (UTC) divisi per la durata danno il numero della finestra */
    public enum Finestra {
        MINUTO(60, "al minuto"),
        GIORNO(86_400, "al giorno");

        private final long secondi;
        private final String descrizione;

        Finestra(long secondi, String descrizione) {
            this.secondi = secondi;
            this.descrizione = descrizione;
        }

        long secondi() {
            return secondi;
        }
    }

    /**
     * Esito di una richiesta contata: se passa; se no, fra quanti secondi la finestra riparte (il Retry-After); e se è la
     * prima respinta di quella chiave in quella finestra, perché nei log vada una riga sola e non una per richiesta.
     */
    public record Esito(boolean consentita, long secondiAttesa, boolean primoSuperamento) {}

    /** Le richieste di una chiave nella finestra con quel numero. Long: chi sfora il massimo continua a contare */
    private record Conteggio(long finestra, long richieste) {}

    private static final Esito CONSENTITA = new Esito(true, 0, false);

    private final int massimo;
    private final Finestra finestra;
    private final Clock orologio;
    private final Map<String, Conteggio> conteggi = new ConcurrentHashMap<>();
    /** Numero dell'ultima finestra in cui si sono tolte le voci scadute: la pulizia è una per finestra */
    private final AtomicLong ultimaPulizia = new AtomicLong();

    public LimiteRichieste(int massimo, Finestra finestra, Clock orologio) {
        this.massimo = massimo;
        this.finestra = finestra;
        this.orologio = orologio;
    }

    /** Conta una richiesta della chiave e dice se può passare */
    public Esito conta(String chiave) {
        long adesso = orologio.instant().getEpochSecond();
        long corrente = adesso / finestra.secondi;
        togliLeVociScadute(corrente);
        // merge è atomico per chiave: due richieste contemporanee non si perdono e non contano due volte lo stesso posto
        Conteggio conteggio = conteggi.merge(chiave, new Conteggio(corrente, 1), (attuale, nuovo) -> {
            if (attuale.finestra() != corrente) return nuovo; // voce di una finestra passata: si riparte da 1
            return new Conteggio(corrente, attuale.richieste() + 1);
        });
        if (conteggio.richieste() <= massimo) return CONSENTITA;
        // Conta anche chi sfora: la richiesta che porta il conteggio a massimo + 1 è la prima respinta, una per finestra
        long attesa = (corrente + 1) * finestra.secondi - adesso;
        return new Esito(false, attesa, conteggio.richieste() == massimo + 1L);
    }

    /**
     * Toglie dalla mappa le voci delle finestre passate, una volta per finestra, alla prima richiesta di quella nuova:
     * senza, con indirizzi sempre nuovi la mappa crescerebbe senza fine. removeIf toglie una voce solo se è ancora quella
     * giudicata scaduta: se nel frattempo un'altra richiesta l'ha riscritta per la finestra nuova, quella resta.
     */
    private void togliLeVociScadute(long corrente) {
        long ultima = ultimaPulizia.get();
        if (corrente > ultima && ultimaPulizia.compareAndSet(ultima, corrente)) {
            conteggi.values().removeIf(conteggio -> conteggio.finestra() < corrente);
        }
    }

    /** Quante voci ci sono in memoria: lo guardano i test della pulizia */
    int dimensione() {
        return conteggi.size();
    }

    /** «10 al minuto», «300 al giorno»: il limite come si scrive nei log */
    @Override
    public String toString() {
        return massimo + " " + finestra.descrizione;
    }
}
