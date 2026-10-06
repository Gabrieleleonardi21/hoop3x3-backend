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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private final OrologioDiProva orologio = new OrologioDiProva();

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

    /**
     * Esegue `compito` su `thread` thread e aspetta che finiscano tutti. Partono insieme: aspettano in attesa attiva che il
     * test li liberi e riprendono nello stesso istante, così le gare di pochi nanosecondi si vedono davvero.
     */
    private static void contemporaneamente(int thread, Runnable compito) throws Exception {
        CountDownLatch pronti = new CountDownLatch(thread);
        AtomicBoolean via = new AtomicBoolean();
        Callable<Void> allaPartenza = () -> {
            pronti.countDown();
            while (!via.get()) Thread.onSpinWait();
            compito.run();
            return null;
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(thread)) {
            List<Future<Void>> esiti = new ArrayList<>();
            for (int i = 0; i < thread; i++) {
                esiti.add(pool.submit(allaPartenza));
            }
            boolean partiti = pronti.await(10, TimeUnit.SECONDS);
            via.set(true); // sempre, anche se qualcuno non è partito: i thread in attesa attiva devono poter finire
            assertThat(partiti).as("i thread sono partiti").isTrue();
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

    /* ── La finestra non torna mai indietro ── */

    // Una richiesta legge l'ora (10:00:59) e finisce di contare quando un'altra ha già aperto il minuto dopo e ci ha consumato
    // tutti i posti di «b». Se la richiesta in ritardo rimpiazzasse la finestra nuova con una vecchia vuota, i conteggi del
    // minuto nuovo sparirebbero per tutte le chiavi e «b» ripartirebbe da zero. L'intreccio è fisso: l'orologio, alla prima
    // lettura, fa scoccare il minuto e fa contare «b» prima di restituire l'ora vecchia
    @Test
    void unaRichiestaInRitardo_nonRimpiazzaLaFinestraDelMinutoNuovo() {
        LimiteRichieste limite = alMinuto(3);
        orologio.avanza(Duration.ofSeconds(39)); // 10:00:59
        orologio.allaProssimaLettura(() -> {
            orologio.avanza(Duration.ofSeconds(1)); // 10:01:00: il minuto scocca
            faiPassare(limite, "b", 3); // un'altra richiesta apre il minuto nuovo e ci consuma i posti di «b»
        });

        assertThat(limite.conta("a").consentita()).as("la richiesta in ritardo, di «a»").isTrue();

        assertThat(limite.conta("b").consentita()).as("i posti di «b» nel minuto nuovo non sono spariti").isFalse();
        // La richiesta in ritardo ha contato nel minuto nuovo, non in una finestra buttata: era la prima di «a» su 3
        faiPassare(limite, "a", 2);
        assertThat(limite.conta("a").consentita()).isFalse();
    }

    // Lo stesso accade con l'orologio di sistema che torna indietro (NTP): le richieste contano nella finestra più nuova,
    // invece di riaprirne una vecchia vuota con i conteggi azzerati. L'attesa è fino alla fine della finestra in cui hanno
    // contato, secondo l'orologio com'è ora
    @Test
    void unOrologioCheTornaIndietro_nonFaSparireIConteggiDelMinuto() {
        LimiteRichieste limite = alMinuto(3);
        orologio.avanza(Duration.ofSeconds(70)); // 10:01:30
        faiPassare(limite, INDIRIZZO, 3);

        orologio.imposta(Instant.parse("2026-10-06T10:00:40Z")); // l'orologio del server torna indietro di 50 secondi
        Esito esito = limite.conta(INDIRIZZO);

        assertThat(esito.consentita()).as("i posti del minuto 10:01 non sono spariti").isFalse();
        assertThat(esito.secondiAttesa()).as("il minuto 10:01 finisce alle 10:02:00, 80 secondi da qui").isEqualTo(80);

        orologio.imposta(Instant.parse("2026-10-06T10:02:00Z")); // il minuto dopo, per l'orologio com'era e com'è
        faiPassare(limite, INDIRIZZO, 3);
    }

    // Se l'orologio torna indietro di ore (una macchina virtuale ripristinata, la data cambiata a mano) i conteggi non restano
    // nella finestra più avanti: darebbero a ogni chiave il solo massimo per tutta la durata del salto, con un Retry-After di
    // ore (per un indirizzo nuovo, 10 login e poi 2 h 40 di rifiuti). Si riparte da una finestra vuota e l'attesa resta quella
    // di un minuto
    @Test
    void unOrologioCheTornaIndietroDiOre_ripartiDaUnaFinestraVuotaEIlRetryAfterRestaQuelloDiUnMinuto() {
        LimiteRichieste limite = alMinuto(3);
        orologio.avanza(Duration.ofHours(3)); // 13:00:20
        faiPassare(limite, INDIRIZZO, 3);
        assertThat(limite.conta(INDIRIZZO).consentita()).isFalse();

        orologio.imposta(Instant.parse("2026-10-06T10:00:20Z")); // l'orologio del server torna indietro di 3 ore

        Esito primoDopoIlSalto = limite.conta(INDIRIZZO);
        assertThat(primoDopoIlSalto.secondiAttesa()).as("Retry-After: la finestra più 60 secondi al massimo").isLessThanOrEqualTo(60 + 60);
        assertThat(primoDopoIlSalto.consentita()).as("il minuto 10:00 è nuovo, con tutti i suoi posti").isTrue();
        faiPassare(limite, INDIRIZZO, 2);
        Esito quarto = limite.conta(INDIRIZZO);
        assertThat(quarto.consentita()).isFalse();
        assertThat(quarto.secondiAttesa()).as("il minuto 10:00 finisce alle 10:01:00, 40 secondi da qui").isEqualTo(40);
    }

    // Il confine: fino a 60 secondi prima dell'inizio della finestra in corso l'ora è ancora una richiesta in ritardo o una
    // piccola correzione, e conta nella finestra più avanti: il Retry-After arriva alla finestra più 60 secondi, non oltre.
    // Un secondo più indietro l'orologio è tornato indietro di molto e si riparte da una finestra vuota
    @Test
    void ilSaltoIndietroSiTollera_finoASessantaSecondiPrimaDellInizioDellaFinestra() {
        LimiteRichieste limite = alMinuto(3);
        orologio.avanza(Duration.ofSeconds(70)); // 10:01:30, nel minuto che comincia alle 10:01:00
        faiPassare(limite, INDIRIZZO, 3);

        orologio.imposta(Instant.parse("2026-10-06T10:00:00Z")); // 60 secondi prima dell'inizio: ancora la stessa finestra
        Esito alLimite = limite.conta(INDIRIZZO);
        assertThat(alLimite.consentita()).isFalse();
        assertThat(alLimite.secondiAttesa()).as("la finestra più 60 secondi: il massimo").isEqualTo(120);

        orologio.imposta(Instant.parse("2026-10-06T09:59:59Z")); // 61 secondi prima: l'orologio è tornato indietro di molto
        assertThat(limite.conta(INDIRIZZO).consentita()).as("si riparte da una finestra vuota").isTrue();
    }

    // Vale anche per il giorno, e la tolleranza è misurata dall'inizio della finestra e non da un minuto fisso: tornare
    // indietro oltre la mezzanotte che ha aperto il giorno in corso riparte da zero
    @Test
    void unOrologioCheTornaIndietroOltreLInizioDelGiorno_ripartiDaUnaFinestraVuota() {
        LimiteRichieste limite = alGiorno(3);
        faiPassare(limite, "utente", 3); // 10:00:20 del 6 ottobre
        assertThat(limite.conta("utente").consentita()).isFalse();

        orologio.imposta(Instant.parse("2026-10-05T22:00:00Z")); // due ore prima della mezzanotte: è il giorno prima

        faiPassare(limite, "utente", 3);
        Esito esito = limite.conta("utente");
        assertThat(esito.consentita()).isFalse();
        assertThat(esito.secondiAttesa()).as("il giorno del 5 ottobre finisce a mezzanotte, 2 ore da qui").isEqualTo(2 * 3600);
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

    // La finestra nuova si apre alla prima richiesta del minuto, mentre altre contano già: se ognuna aprisse la sua, o se la
    // finestra si aprisse due volte, i conteggi andrebbero persi e chi insiste passerebbe oltre il limite. La gara dura
    // pochi nanosecondi: per vederla si ripete per 150 minuti di fila, ognuno con tanti thread quanti i core, che partono insieme
    @Test
    void ilPassaggioAlMinutoNuovoConRichiesteContemporanee_nonFaPerdereIConteggi() throws Exception {
        LimiteRichieste limite = alMinuto(25);
        List<String> chiavi = List.of("a", "b", "c", "d");
        int thread = Math.max(2, Runtime.getRuntime().availableProcessors()); // più thread dei core non partono davvero insieme
        for (int minuto = 1; minuto <= 150; minuto++) {
            orologio.avanza(Duration.ofMinutes(1));
            Map<String, AtomicInteger> consentite = new ConcurrentHashMap<>();
            chiavi.forEach(chiave -> consentite.put(chiave, new AtomicInteger()));

            contemporaneamente(thread, () -> {
                for (int i = 0; i < 100; i++) {
                    String chiave = chiavi.get(i % 4);
                    if (limite.conta(chiave).consentita()) consentite.get(chiave).incrementAndGet();
                }
            });

            assertThat(consentite.values()).as("minuto %s", minuto).allSatisfy(passate -> assertThat(passate).hasValue(25));
            assertThat(limite.dimensione()).as("restano le 4 chiavi del minuto, non quelle dei minuti passati").isEqualTo(4);
        }
    }
}
