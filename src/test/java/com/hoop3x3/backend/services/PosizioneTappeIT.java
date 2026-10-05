package com.hoop3x3.backend.services;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.RegoleDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ForbiddenException;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-10, posizione delle tappe con il database vero. La tappa nuova prendeva come posizione il numero di tappe della
 * lega: dopo un'eliminazione quel numero è la posizione di una tappa che c'è ancora, e l'ordine tra le due era
 * indefinito. Ora prende una più della massima. I database già in uso hanno ancora posizioni doppie, lasciate dal
 * difetto: a parità di posizione le tappe escono sempre nello stesso ordine, la più vecchia prima. Il frontend può
 * mandare insieme le POST di più tappe nuove della stessa lega: aggiungiTappa blocca la riga della lega, così la
 * seconda richiesta aspetta la prima e legge la posizione massima già aggiornata.
 */
@TestDiIntegrazione
class PosizioneTappeIT {

    /** SQLSTATE di PostgreSQL per «lock_not_available»: lo dà `for update nowait` su una riga bloccata da un'altra transazione */
    private static final String LOCK_NON_DISPONIBILE = "55P03";

    @Autowired LegaService legaService;
    @Autowired UtenteRepository utenti;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transazioni;
    @Autowired ObjectMapper mapper;

    private Utente mario; // proprietario delle leghe di prova

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
    }

    /* ── La tappa nuova va una posizione dopo la massima ── */

    // Il caso del difetto: tre tappe, la prima eliminata, una nuova. Restano le posizioni 1 e 2: con il numero delle tappe
    // la nuova prendeva la 2, già della terza
    @Test
    void dopoUnaEliminazioneLaTappaNuovaNonRiusaUnaPosizione_tutteDiverseEOrdineStabile() {
        UUID lega = legaVuota();
        TappaDTO prima = aggiungi(lega, "Prima");
        TappaDTO seconda = aggiungi(lega, "Seconda");
        TappaDTO terza = aggiungi(lega, "Terza");

        legaService.eliminaTappa(mario, prima.id());
        TappaDTO nuova = aggiungi(lega, "Nuova");

        assertThat(posizioni(lega)).as("posizioni delle tappe").doesNotHaveDuplicates().containsExactly(1, 2, 3);
        assertThat(ordineDelleTappe(lega)).containsExactly(seconda.id(), terza.id(), nuova.id());
    }

    // Senza tappe non c'è una massima: la prima va in posizione 0, come prima (non in 1)
    @Test
    void laPrimaTappaDiUnaLegaVuotaStaInPosizioneZero() {
        UUID lega = legaVuota();

        aggiungi(lega, "Prima");
        aggiungi(lega, "Seconda");

        assertThat(posizioni(lega)).containsExactly(0, 1);
    }

    // L'import da file numera le tappe nell'ordine del file, come prima: i nomi non sono in ordine alfabetico e gli id
    // sono casuali, quindi solo la posizione può dare quell'ordine. Una tappa aggiunta dopo va in coda
    @Test
    void leTappeImportateSiNumeranoNellOrdineDelFile_quellaAggiuntaDopoVaInCoda() {
        List<TappaDTO> file = List.of(tappa("Zeta"), tappa("Alfa"), tappa("Mu"));
        UUID lega = legaService.crea(mario, new NuovaLegaDTO("Importata", file)).id();

        TappaDTO aggiunta = aggiungi(lega, "Aggiunta");

        assertThat(ordineDelleTappe(lega)).containsExactly(file.get(0).id(), file.get(1).id(), file.get(2).id(), aggiunta.id());
        assertThat(posizioni(lega)).containsExactly(0, 1, 2, 3);
    }

    // La massima si cerca solo tra le tappe della lega: quelle di un'altra lega non spostano la prima posizione
    @Test
    void lePosizioniDiUnAltraLegaNonContano() {
        UUID altra = legaVuota();
        aggiungi(altra, "Una");
        aggiungi(altra, "Due");
        aggiungi(altra, "Tre");
        UUID lega = legaVuota();

        aggiungi(lega, "Prima");
        aggiungi(lega, "Seconda");

        assertThat(posizioni(lega)).containsExactly(0, 1);
    }

    /* ── Posizioni doppie dei database già in uso: a parità di posizione l'ordine è sempre lo stesso ── */

    // Le righe si scrivono con JDBC, come le avrebbe lasciate il difetto, e nell'ordine opposto a quello atteso: senza
    // uno spareggio PostgreSQL le restituirebbe nell'ordine in cui sono state scritte, con uno spareggio per id o per
    // data decrescente le metterebbe nell'altro ordine
    @Test
    void aParitaDiPosizioneVieneLaTappaPiuVecchia_ancheSeScrittaPerUltimaEConIdMaggiore() {
        UUID lega = legaVuota();
        UUID recente = id(2);
        UUID vecchia = id(9);
        UUID inCoda = id(1);
        scriviTappa(lega, recente, 0, LocalDateTime.of(2026, 3, 2, 10, 0)); // più recente, id minore, scritta per prima
        scriviTappa(lega, vecchia, 0, LocalDateTime.of(2026, 3, 1, 10, 0)); // più vecchia, id maggiore, scritta dopo
        // La più vecchia di tutte, ma in un'altra posizione: la posizione conta più dell'età
        scriviTappa(lega, inCoda, 1, LocalDateTime.of(2026, 2, 1, 10, 0));

        assertThat(ordineDelleTappe(lega)).containsExactly(vecchia, recente, inCoda);
    }

    // Se anche la data è uguale decide l'id: scritta per prima la tappa con l'id maggiore, deve uscire dopo
    @Test
    void aParitaDiPosizioneEDiDataVieneLIdMinore() {
        UUID lega = legaVuota();
        LocalDateTime stessoMomento = LocalDateTime.of(2026, 3, 1, 10, 0);
        scriviTappa(lega, id(7), 0, stessoMomento);
        scriviTappa(lega, id(5), 0, stessoMomento);

        assertThat(ordineDelleTappe(lega)).containsExactly(id(5), id(7));
    }

    /* ── Richieste insieme nella stessa lega: aggiungiTappa blocca la riga della lega ── */

    // Il lock si vede solo se la transazione resta aperta dopo la chiamata: la tiene aperta il test, e aggiungiTappa vi
    // partecipa. Da un'altra connessione `for update nowait` non aspetta: se la riga è bloccata fallisce subito
    @Test
    void aggiungereUnaTappaBloccaLaRigaDellaLegaFinoAllaFineDellaTransazione() {
        UUID lega = legaVuota();

        new TransactionTemplate(transazioni).executeWithoutResult(transazione -> {
            aggiungi(lega, "Nuova");
            assertThat(rigaDellaLegaBloccata(lega)).as("riga della lega bloccata da aggiungiTappa").isTrue();
        });

        assertThat(rigaDellaLegaBloccata(lega)).as("riga della lega dopo la fine della transazione").isFalse();
    }

    // Il frontend può mandare insieme le POST di tappe diverse della stessa lega (svuota() della coda, chiusura della
    // pagina): senza il lock due richieste leggono la stessa posizione massima. L'intreccio è forzato, non lasciato al
    // caso: la prima richiesta resta aperta, con il suo lock, finché la seconda non è ferma ad aspettarlo; poi conferma, e
    // la seconda deve vedere la tappa della prima
    @Test
    void dueTappeNuoveInParalleloNellaStessaLegaPrendonoPosizioniDiverse() throws Exception {
        UUID lega = legaVuota();
        ExecutorService altroThread = Executors.newSingleThreadExecutor();
        try {
            Future<TappaDTO> seconda = new TransactionTemplate(transazioni).execute(transazione -> {
                aggiungi(lega, "Prima");
                Future<TappaDTO> richiesta = altroThread.submit(() -> aggiungi(lega, "Seconda"));
                aspettaFinitaOFermaSuUnLock(richiesta);
                assertThat(unaRichiestaAspettaUnLock()).as("la seconda richiesta aspetta che la prima confermi").isTrue();
                return richiesta;
            });
            seconda.get(30, TimeUnit.SECONDS);
        } finally {
            altroThread.shutdownNow();
        }

        assertThat(posizioni(lega)).containsExactly(0, 1);
    }

    /* ── Chi può aggiungere tappe: la regola di AccessGuard (404, 403, ADMIN) non cambia con il modo di leggere la lega ── */

    // Il lock si prende prima del controllo di proprietà: il rifiuto annulla la transazione e lo rilascia
    @Test
    void chiNonEProprietarioNonPuoAggiungereTappe() {
        UUID lega = legaVuota();
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));

        assertThatThrownBy(() -> legaService.aggiungiTappa(luigi, lega, tappa("Intrusa")))
                .isInstanceOf(ForbiddenException.class);

        assertThat(posizioni(lega)).isEmpty();
        assertThat(rigaDellaLegaBloccata(lega)).as("riga della lega dopo il rifiuto").isFalse();
    }

    @Test
    void unAdminPuoAggiungereTappeAllaLegaDiUnAltro() {
        UUID lega = legaVuota();
        Utente admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));

        legaService.aggiungiTappa(admin, lega, tappa("Dell'admin"));

        assertThat(posizioni(lega)).containsExactly(0);
    }

    @Test
    void aggiungereUnaTappaAUnaLegaCheNonEsisteDaNotFound() {
        assertThatThrownBy(() -> legaService.aggiungiTappa(mario, UUID.randomUUID(), tappa("Orfana")))
                .isInstanceOf(NotFoundException.class);
    }

    /* ── Dati di prova ── */

    /** Una lega di Mario senza tappe: l'id con cui aggiungerne */
    private UUID legaVuota() {
        return legaService.crea(mario, new NuovaLegaDTO("Circuito", null)).id();
    }

    /** Aggiunge una tappa con il servizio, come fa POST /api/leghe/{id}/tappe */
    private TappaDTO aggiungi(UUID legaId, String nome) {
        return legaService.aggiungiTappa(mario, legaId, tappa(nome));
    }

    /**
     * Scrive con JDBC una riga di `tappe` con la posizione e la data di creazione indicate: le altre colonne hanno un
     * valore predefinito. Il servizio non produce più posizioni doppie, ma i database già in uso le hanno.
     */
    private void scriviTappa(UUID legaId, UUID id, int posizione, LocalDateTime creatoIl) {
        jdbc.update("insert into tappe (id, lega_id, posizione, nome, creato_il, modificato_il) values (?, ?, ?, ?, ?, ?)",
                id, legaId, posizione, "Tappa", creatoIl, creatoIl);
    }

    /** Un id con un ordine prevedibile: 00000000-0000-0000-0000-00000000000n */
    private static UUID id(int n) {
        return new UUID(0, n);
    }

    /** Una tappa qualsiasi, con un id nuovo */
    private TappaDTO tappa(String nome) {
        return new TappaDTO(UUID.randomUUID(), nome, "Roma", "2026-06-14", 1, new RegoleDTO(21, 10, 2, 12),
                mapper.readTree("[]"), null, mapper.readTree("[]"), mapper.readTree("[]"), false, null);
    }

    /* ── Un'altra connessione: il database come lo vedrebbe un'altra richiesta ── */

    /**
     * Esegue `azione` in una transazione nuova, quindi su un'altra connessione, mentre quella del test resta sospesa.
     * Ogni transazione nuova parte da una lettura aggiornata, anche di pg_stat_activity (dentro una transazione resterebbe
     * quella della prima lettura).
     */
    private <T> T daUnAltraConnessione(Supplier<T> azione) {
        DefaultTransactionDefinition nuova = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new TransactionTemplate(transazioni, nuova).execute(transazione -> azione.get());
    }

    /** Se un'altra transazione tiene bloccata la riga della lega: `for update nowait` non aspetta, se è bloccata fallisce subito */
    private boolean rigaDellaLegaBloccata(UUID legaId) {
        try {
            daUnAltraConnessione(() -> jdbc.queryForObject("select id from leghe where id = ? for update nowait", UUID.class, legaId));
            return false;
        } catch (DataAccessException e) {
            // Spring non dà a questo errore di PostgreSQL un'eccezione sua: si riconosce dallo SQLSTATE
            if (e.getRootCause() instanceof SQLException sql && LOCK_NON_DISPONIBILE.equals(sql.getSQLState())) return true;
            throw e;
        }
    }

    /**
     * Se una richiesta al database è ferma ad aspettare un lock. Guarda tutto il database di prova, ma le classi di
     * integrazione girano una alla volta: l'unica che può aspettare è la seconda richiesta del test.
     */
    private boolean unaRichiestaAspettaUnLock() {
        int inAttesa = daUnAltraConnessione(() -> jdbc.queryForObject(
                "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'", Integer.class));
        return inAttesa > 0;
    }

    /**
     * Aspetta, al massimo 10 secondi, che la richiesta sia finita o ferma su un lock: se non aspetta nessun lock finisce da
     * sola, e così il test non resta ad aspettare invano né dipende da una pausa scelta a caso.
     */
    private void aspettaFinitaOFermaSuUnLock(Future<?> richiesta) {
        long scadenza = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!richiesta.isDone() && !unaRichiestaAspettaUnLock() && System.nanoTime() < scadenza) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); // pausa di 10 ms tra una lettura e l'altra
        }
    }

    /* ── Letture ── */

    /** Gli id delle tappe come li restituisce la lega, cioè nell'ordine che le dà il servizio */
    private List<UUID> ordineDelleTappe(UUID legaId) {
        return legaService.dettaglio(mario, legaId).tappe().stream().map(TappaDTO::id).toList();
    }

    /** Le posizioni salvate nelle tappe della lega, dalla più piccola */
    private List<Integer> posizioni(UUID legaId) {
        return jdbc.queryForList("select posizione from tappe where lega_id = ? order by posizione", Integer.class, legaId);
    }
}
