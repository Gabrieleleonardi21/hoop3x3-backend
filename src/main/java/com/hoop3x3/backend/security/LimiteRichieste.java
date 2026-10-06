package com.hoop3x3.backend.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contatore di richieste in memoria, a finestra fissa: conta quante richieste fa ogni chiave (un indirizzo IP, un utente)
 * nella finestra in corso e dice di no oltre il massimo.
 * <p>
 * Le finestre sono allineate all'orologio: il minuto finisce al secondo 0 e il giorno a mezzanotte UTC, qualunque sia il
 * momento della prima richiesta. Così per ogni chiave basta un numero e la fine della finestra è la stessa per tutti: il
 * Retry-After si calcola senza altro stato. Il prezzo è che a cavallo di due finestre una chiave può fare fino al doppio
 * del massimo in pochi secondi.
 * <p>
 * Ogni finestra ha la sua mappa dei conteggi. Alla prima richiesta di una finestra nuova se ne apre una vuota e la
 * precedente, con tutte le sue voci, si butta: la memoria non cresce con indirizzi sempre nuovi, e non serve nessun giro di
 * pulizia voce per voce, che dovrebbe convivere con le richieste che contano nello stesso momento.
 * <p>
 * Regge le richieste contemporanee (ConcurrentHashMap e contatori atomici, nessun blocco). Vive nella memoria di questo
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

    /** I conteggi della finestra con quel numero. Long: chi sfora il massimo continua a contare */
    private record FinestraInCorso(long numero, ConcurrentHashMap<String, AtomicLong> conteggi) {}

    private static final Esito CONSENTITA = new Esito(true, 0, false);

    private final int massimo;
    private final Finestra finestra;
    private final Clock orologio;
    private final AtomicReference<FinestraInCorso> inCorso = new AtomicReference<>(new FinestraInCorso(-1, new ConcurrentHashMap<>()));

    public LimiteRichieste(int massimo, Finestra finestra, Clock orologio) {
        this.massimo = massimo;
        this.finestra = finestra;
        this.orologio = orologio;
    }

    /** Conta una richiesta della chiave e dice se può passare */
    public Esito conta(String chiave) {
        long adesso = orologio.instant().getEpochSecond();
        long numero = adesso / finestra.secondi;
        // Il contatore è atomico: due richieste contemporanee non si perdono e non contano due volte lo stesso posto
        long richieste = finestraInCorso(numero).conteggi().computeIfAbsent(chiave, _ -> new AtomicLong()).incrementAndGet();
        if (richieste <= massimo) return CONSENTITA;
        // Conta anche chi sfora: la richiesta che porta il conteggio a massimo + 1 è la prima respinta, una per finestra
        long attesa = (numero + 1) * finestra.secondi - adesso;
        return new Esito(false, attesa, richieste == massimo + 1L);
    }

    /** La finestra con quel numero: se non è ancora quella in corso la apre vuota e butta la precedente con le sue voci */
    private FinestraInCorso finestraInCorso(long numero) {
        FinestraInCorso attuale = inCorso.get();
        if (attuale.numero() == numero) return attuale; // il caso di quasi tutte le richieste: una sola lettura
        // Se più richieste aprono la finestra nuova insieme ne resta una sola: le altre trovano già la sua mappa
        return inCorso.updateAndGet(corrente -> {
            if (corrente.numero() == numero) return corrente;
            return new FinestraInCorso(numero, new ConcurrentHashMap<>());
        });
    }

    /** Quante voci ci sono in memoria: lo guardano i test della pulizia */
    int dimensione() {
        return inCorso.get().conteggi().size();
    }

    /** «10 al minuto», «300 al giorno»: il limite come si scrive nei log */
    @Override
    public String toString() {
        return massimo + " " + finestra.descrizione;
    }
}
