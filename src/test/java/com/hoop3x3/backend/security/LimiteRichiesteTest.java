package com.hoop3x3.backend.security;

import com.hoop3x3.backend.OrologioDiProva;
import com.hoop3x3.backend.security.LimiteRichieste.Esito;
import com.hoop3x3.backend.security.LimiteRichieste.Finestra;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hoop3x3.backend.security.LimiteRichieste.Finestra.GIORNO;
import static com.hoop3x3.backend.security.LimiteRichieste.Finestra.MINUTO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il contatore da solo, senza Spring e senza attese: l'orologio è finto e si sposta a comando. Parte dal secondo 20 di
 * un minuto, a metà giornata: al minuto dopo mancano 40 secondi, alla mezzanotte UTC 13 ore, 59 minuti e 40 secondi.
 */
class LimiteRichiesteTest {

    private static final String INDIRIZZO = "203.0.113.9";
    private static final long SECONDI_A_MEZZANOTTE = 13 * 3600 + 59 * 60 + 40;

    private final OrologioDiProva orologio = new OrologioDiProva("2026-10-06T10:00:20Z");

    private LimiteRichieste alMinuto(int massimo) {
        return new LimiteRichieste(massimo, MINUTO, orologio);
    }

    private LimiteRichieste alGiorno(int massimo) {
        return new LimiteRichieste(massimo, GIORNO, orologio);
    }

    /** `quante` richieste della chiave, ognuna delle quali deve passare */
    private static void faiPassare(LimiteRichieste limite, String chiave, int quante) {
        for (int i = 1; i <= quante; i++) {
            assertThat(limite.conta(chiave).consentita()).as("richiesta numero %s", i).isTrue();
        }
    }

    /** Esegue `compito` su `thread` thread fatti partire insieme da una barriera e aspetta che finiscano tutti */
    private static void contemporaneamente(int thread, Runnable compito) throws Exception {
        CyclicBarrier partenza = new CyclicBarrier(thread);
        Callable<Void> allaPartenza = () -> {
            partenza.await(10, TimeUnit.SECONDS);
            compito.run();
            return null;
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(thread)) {
            List<Future<Void>> esiti = new ArrayList<>();
            for (int i = 0; i < thread; i++) {
                esiti.add(pool.submit(allaPartenza));
            }
            for (Future<Void> esito : esiti) {
                esito.get(30, TimeUnit.SECONDS); // rilancia l'errore di un thread, invece di nasconderlo
            }
        }
    }

    /* ── Il limite ── */

    // Il caso del piano: 10 accessi al minuto per indirizzo, l'undicesimo no
    @Test
    void undicesimaRichiestaDelMinuto_nonPassa() {
        LimiteRichieste limite = alMinuto(10);
        faiPassare(limite, INDIRIZZO, 10);

        Esito undicesima = limite.conta(INDIRIZZO);

        assertThat(undicesima.consentita()).isFalse();
        assertThat(undicesima.secondiAttesa()).isEqualTo(40);
    }

    @Test
    void passatoIlMinuto_laChiaveTornaAPassare() {
        LimiteRichieste limite = alMinuto(10);
        faiPassare(limite, INDIRIZZO, 10);
        assertThat(limite.conta(INDIRIZZO).consentita()).isFalse();

        orologio.avanza(Duration.ofSeconds(40)); // le 10:01:00, l'inizio del minuto dopo

        faiPassare(limite, INDIRIZZO, 10); // un minuto nuovo, con tutte le sue 10 richieste
        assertThat(limite.conta(INDIRIZZO).consentita()).isFalse();
    }

    @Test
    void chiaviDiverse_hannoContatoriSeparati() {
        LimiteRichieste limite = alMinuto(3);
        faiPassare(limite, "203.0.113.1", 3);
        assertThat(limite.conta("203.0.113.1").consentita()).isFalse();

        // L'indirizzo esaurito non toglie niente agli altri
        faiPassare(limite, "203.0.113.2", 3);
    }

    /* ── Le finestre: allineate all'orologio ── */

    // La finestra è il minuto dell'orologio e non «60 secondi dalla prima richiesta»: chi comincia a fine minuto ha il
    // contatore a zero pochi secondi dopo. È il comportamento delle finestre fisse, e va detto nel README
    @Test
    void laFinestraSiAllineaAlMinutoDellOrologio_nonAllaPrimaRichiesta() {
        LimiteRichieste limite = alMinuto(1);
        orologio.avanza(Duration.ofSeconds(39)); // 10:00:59
        assertThat(limite.conta(INDIRIZZO).consentita()).isTrue();
        assertThat(limite.conta(INDIRIZZO).consentita()).isFalse();

        orologio.avanza(Duration.ofSeconds(2)); // 10:01:01: sono passati 2 secondi, ma il minuto è un altro

        assertThat(limite.conta(INDIRIZZO).consentita()).isTrue();
    }

    // Retry-After: i secondi che mancano all'inizio della finestra dopo, mai 0 (a 0 la finestra è già quella nuova)
    @Test
    void iSecondiDiAttesa_arrivanoFinoAlloScoccareDelMinuto() {
        LimiteRichieste limite = alMinuto(1);
        faiPassare(limite, INDIRIZZO, 1);
        assertThat(limite.conta(INDIRIZZO).secondiAttesa()).isEqualTo(40); // 10:00:20

        orologio.avanza(Duration.ofSeconds(39)); // 10:00:59
        Esito ultimoSecondo = limite.conta(INDIRIZZO);
        assertThat(ultimoSecondo.consentita()).isFalse();
        assertThat(ultimoSecondo.secondiAttesa()).isEqualTo(1);

        orologio.avanza(Duration.ofSeconds(1)); // 10:01:00: il minuto è scoccato
        assertThat(limite.conta(INDIRIZZO).consentita()).isTrue();
    }

    // La quota giornaliera del Coach ricomincia a mezzanotte UTC, qualunque sia il fuso del server
    @Test
    void ilGiornoFinisceAMezzanotteUtc() {
        LimiteRichieste limite = alGiorno(3);
        faiPassare(limite, "utente", 3);
        Esito oltre = limite.conta("utente");
        assertThat(oltre.consentita()).isFalse();
        assertThat(oltre.secondiAttesa()).isEqualTo(SECONDI_A_MEZZANOTTE);

        orologio.avanza(Duration.ofSeconds(SECONDI_A_MEZZANOTTE - 1)); // 23:59:59
        Esito ultimoSecondo = limite.conta("utente");
        assertThat(ultimoSecondo.consentita()).isFalse();
        assertThat(ultimoSecondo.secondiAttesa()).isEqualTo(1);

        orologio.avanza(Duration.ofSeconds(1));
        assertThat(orologio.instant()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        faiPassare(limite, "utente", 3); // un giorno nuovo, con tutte le sue richieste
    }

    /* ── La riga di log: una sola per chiave e per finestra ── */

    @Test
    void soloLaPrimaRichiestaRespintaDiOgniFinestraSiSegnala() {
        LimiteRichieste limite = alMinuto(2);
        faiPassare(limite, INDIRIZZO, 2);

        assertThat(limite.conta(INDIRIZZO).primoSuperamento()).isTrue();
        assertThat(limite.conta(INDIRIZZO).primoSuperamento()).isFalse();
        assertThat(limite.conta(INDIRIZZO).primoSuperamento()).isFalse();
        assertThat(limite.conta("203.0.113.1").primoSuperamento()).as("un'altra chiave dentro il limite").isFalse();

        orologio.avanza(Duration.ofMinutes(1)); // nel minuto dopo chi insiste si segnala di nuovo, una volta
        faiPassare(limite, INDIRIZZO, 2);
        assertThat(limite.conta(INDIRIZZO).primoSuperamento()).isTrue();
        assertThat(limite.conta(INDIRIZZO).primoSuperamento()).isFalse();
    }

    /* ── La memoria: le voci scadute si tolgono ── */

    // Con indirizzi sempre nuovi la mappa crescerebbe a ogni minuto: la prima richiesta di una finestra nuova toglie le voci
    // delle finestre passate. Vale per il minuto e per il giorno
    @ParameterizedTest
    @EnumSource(Finestra.class)
    void leVociDelleFinestrePassate_siTolgonoDallaMappa(Finestra finestra) {
        LimiteRichieste limite = new LimiteRichieste(10, finestra, orologio);
        for (int i = 0; i < 1000; i++) {
            limite.conta("10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(limite.dimensione()).isEqualTo(1000);

        orologio.avanza(Duration.ofSeconds(finestra.secondi()));
        limite.conta(INDIRIZZO);

        assertThat(limite.dimensione()).isEqualTo(1);
    }

    @Test
    void nellaStessaFinestra_nonSiTolgonoLeVoci() {
        LimiteRichieste limite = alMinuto(3);
        faiPassare(limite, "203.0.113.1", 3);

        orologio.avanza(Duration.ofSeconds(30)); // 10:00:50, sempre lo stesso minuto
        limite.conta("203.0.113.2");

        // L'indirizzo che aveva esaurito il minuto non si è liberato per colpa di una pulizia
        assertThat(limite.conta("203.0.113.1").consentita()).isFalse();
        assertThat(limite.dimensione()).isEqualTo(2);
    }

    /* ── Richieste contemporanee ── */

    @Test
    void richiesteContemporaneeDellaStessaChiave_nonSiPerdonoENonSiContanoDueVolte() throws Exception {
        LimiteRichieste limite = alMinuto(100);
        AtomicInteger consentite = new AtomicInteger();
        AtomicInteger primeRespinte = new AtomicInteger();

        // 16 thread da 50 richieste: 800 richieste per 100 posti
        contemporaneamente(16, () -> {
            for (int i = 0; i < 50; i++) {
                Esito esito = limite.conta(INDIRIZZO);
                if (esito.consentita()) consentite.incrementAndGet();
                if (esito.primoSuperamento()) primeRespinte.incrementAndGet();
            }
        });

        assertThat(consentite).hasValue(100);
        assertThat(primeRespinte).as("la riga di log di quel minuto è una sola").hasValue(1);
    }

    // La pulizia parte dalla prima richiesta del minuto nuovo, mentre gli altri thread contano: non deve portarsi via il
    // conteggio che stanno scrivendo, altrimenti chi insiste passerebbe oltre il limite
    @Test
    void laPuliziaChePartePerCaso_nonFaPerdereIConteggiDelMinutoNuovo() throws Exception {
        LimiteRichieste limite = alMinuto(25);
        List<String> chiavi = List.of("a", "b", "c", "d");
        // Nel minuto che finisce: 1000 voci, tra cui le 4 chiavi che poi insistono
        for (int i = 0; i < 1000; i++) {
            limite.conta("vecchia-" + i);
        }
        chiavi.forEach(limite::conta);
        Map<String, AtomicInteger> consentite = new ConcurrentHashMap<>();
        chiavi.forEach(chiave -> consentite.put(chiave, new AtomicInteger()));
        orologio.avanza(Duration.ofMinutes(1));

        contemporaneamente(16, () -> {
            for (int i = 0; i < 100; i++) {
                String chiave = chiavi.get(i % 4);
                if (limite.conta(chiave).consentita()) consentite.get(chiave).incrementAndGet();
            }
        });

        assertThat(consentite.values()).allSatisfy(passate -> assertThat(passate).hasValue(25));
        assertThat(limite.dimensione()).as("restano le 4 chiavi del minuto nuovo").isEqualTo(4);
    }
}
