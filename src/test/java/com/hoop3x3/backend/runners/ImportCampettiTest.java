package com.hoop3x3.backend.runners;

import ch.qos.logback.classic.Level;
import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.CampettoRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Il comando di import dei campetti di Pick-Roll, senza contesto Spring e senza database: repository simulati, il Validator
 * vero (le righe passano dalla stessa validazione degli endpoint). L'export finto di dieci righe sta in
 * src/test/resources/import/pick-roll-finto.json: sei valide (una con campi sconosciuti da ignorare, una con i soli campi
 * obbligatori), una con la latitudine fuori intervallo, una senza nome, una palestra (D10) e una senza fonteId. Con il
 * database vero lo prova ImportCampettiIT.
 */
class ImportCampettiTest {

    static final Path EXPORT_FINTO = Path.of("src/test/resources/import/pick-roll-finto.json");

    private final CampettoRepository campetti = mock(CampettoRepository.class);
    private final UtenteRepository utenti = mock(UtenteRepository.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final Utente admin = new Utente("admin@hoop3x3.it", "hash", "Admin", Ruolo.ADMIN);
    private ImportCampetti comando = nuovoComando("admin@hoop3x3.it");
    private LogCatturato log;

    @TempDir Path cartella;

    @BeforeEach
    void catturaIlLog() {
        log = new LogCatturato(ImportCampetti.class);
        when(utenti.findByEmail("admin@hoop3x3.it")).thenReturn(Optional.of(admin));
        when(campetti.findByFonteAndFonteIdIn(eq("pick-roll"), anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void rilasciaIlLog() {
        log.close();
    }

    /* ── Quando parte ── */

    @Test
    void senzaLArgomento_nonFaNienteENonScriveNelLog() {
        comando.run(new DefaultApplicationArguments("--altro=1"));

        verifyNoInteractions(campetti, utenti);
        assertThat(log.righe()).isEmpty();
        assertThat(comando.getExitCode()).isZero();
    }

    @Test
    void richiesto_riconosceLArgomentoTraQuelliDellaRigaDiComando() {
        assertThat(ImportCampetti.richiesto(new String[] {"--importa-campetti=/tmp/c.json"})).isTrue();
        assertThat(ImportCampetti.richiesto(new String[] {"--importa-campetti"})).isTrue();
        assertThat(ImportCampetti.richiesto(new String[] {"--seed.demo=true"})).isFalse();
        assertThat(ImportCampetti.richiesto(new String[0])).isFalse();
    }

    @Test
    void argomentoSenzaPercorso_erroreChiaroEUscita1() {
        comando.run(new DefaultApplicationArguments("--importa-campetti"));

        verifyNoInteractions(campetti);
        assertErrore("--importa-campetti=/percorso/campetti.json");
    }

    /* ── Il file ── */

    @Test
    void fileAssente_erroreChiaroSenzaModifiche() {
        comando.run(argomenti(cartella.resolve("non-esiste.json")));

        verifyNoInteractions(campetti);
        assertErrore("non-esiste.json");
        assertThat(log.righe()).singleElement().asString().contains("non trovato");
    }

    @Test
    void jsonMalformato_erroreChiaroSenzaModifiche() throws Exception {
        Path file = file("[{\"fonteId\": \"x\", \"nome\": ");

        comando.run(argomenti(file));

        verifyNoInteractions(campetti);
        assertErrore("JSON");
    }

    @Test
    void fileCheNonEUnArray_erroreChiaroSenzaModifiche() throws Exception {
        Path file = file("{\"campetti\": []}");

        comando.run(argomenti(file));

        verifyNoInteractions(campetti);
        assertErrore("array");
    }

    // Un campo con il tipo sbagliato (lat testo) è un errore di schema, non una riga da scartare: l'export non ha il formato
    // concordato, e si ferma prima di scrivere qualsiasi riga, anche quelle valide che vengono prima
    @Test
    void campoConIlTipoSbagliato_siFermaPrimaDiScrivereEDiceLaRiga() throws Exception {
        Path file = file("[{\"fonteId\": \"a\", \"nome\": \"Valido\", \"lat\": 41.9, \"lng\": 12.5, \"tipo\": \"campetto\"},"
                + "{\"fonteId\": \"b\", \"nome\": \"Rotto\", \"lat\": \"nord\", \"lng\": 12.5, \"tipo\": \"campetto\"}]");

        comando.run(argomenti(file));

        verify(campetti, never()).save(any());
        assertErrore("riga 2");
    }

    /* ── L'admin ── */

    @Test
    void senzaAdminEmail_erroreChiaroSenzaModifiche() {
        comando = nuovoComando(" ");

        comando.run(argomenti(EXPORT_FINTO));

        verifyNoInteractions(campetti);
        assertErrore("ADMIN_EMAIL");
    }

    @Test
    void adminNonTrovato_erroreChiaroSenzaModifiche() {
        when(utenti.findByEmail("admin@hoop3x3.it")).thenReturn(Optional.empty());

        comando.run(argomenti(EXPORT_FINTO));

        verifyNoInteractions(campetti);
        assertErrore("admin@hoop3x3.it");
    }

    @Test
    void emailDiUnUtenteNormale_erroreChiaroSenzaModifiche() {
        when(utenti.findByEmail("admin@hoop3x3.it"))
                .thenReturn(Optional.of(new Utente("admin@hoop3x3.it", "hash", "Mario", Ruolo.USER)));

        comando.run(argomenti(EXPORT_FINTO));

        verifyNoInteractions(campetti);
        assertErrore("USER");
    }

    /* ── Le righe ── */

    @Test
    void exportFinto_inserisceLeSeiValideConLaFonteEScartaLeQuattroConIlMotivo() {
        comando.run(argomenti(EXPORT_FINTO));

        ArgumentCaptor<Campetto> salvati = ArgumentCaptor.forClass(Campetto.class);
        verify(campetti, times(6)).save(salvati.capture());
        List<Campetto> inseriti = salvati.getAllValues();
        assertThat(inseriti).extracting(Campetto::getFonteId).containsExactly("pr-001", "pr-002", "pr-003", "pr-004",
                "pr-005", "pr-006");
        assertThat(inseriti).allSatisfy(c -> {
            assertThat(c.getFonte()).isEqualTo("pick-roll");
            assertThat(c.getAutore()).isSameAs(admin);
            assertThat(c.getTipo()).isEqualTo("campetto");
        });
        // La riga completa: i campi del file, i campi sconosciuti (foto, valutazione, recensioni, utente) ignorati
        Campetto testaccio = inseriti.getFirst();
        assertThat(testaccio.getNome()).isEqualTo("Campo Testaccio");
        assertThat(testaccio.getIndirizzo()).isEqualTo("Via Galvani");
        assertThat(testaccio.getCitta()).isEqualTo("Roma");
        assertThat(testaccio.getLat()).isEqualTo(41.8765);
        assertThat(testaccio.getLng()).isEqualTo(12.4775);
        assertThat(testaccio.getSuperficie()).isEqualTo("Asfalto");
        assertThat(testaccio.getCanestri()).isEqualTo((short) 2);
        assertThat(testaccio.isIlluminato()).isTrue();
        assertThat(testaccio.isCoperto()).isFalse();
        assertThat(testaccio.isGratuito()).isTrue();
        assertThat(testaccio.isRetine()).isTrue();
        assertThat(testaccio.isLinee()).isTrue();
        assertThat(testaccio.isFontanella()).isFalse();
        assertThat(testaccio.getStato()).isEqualTo("buono");
        assertThat(testaccio.getNote()).isEqualTo("Due canestri, uno storto");
        // La riga con i soli campi obbligatori: i valori predefiniti
        Campetto garbatella = inseriti.get(1);
        assertThat(garbatella.getIndirizzo()).isEmpty();
        assertThat(garbatella.getCitta()).isEmpty();
        assertThat(garbatella.getSuperficie()).isEqualTo("Altro");
        assertThat(garbatella.getCanestri()).isEqualTo((short) 2);
        assertThat(garbatella.isIlluminato()).isFalse();
        assertThat(garbatella.isGratuito()).isFalse();
        assertThat(garbatella.getStato()).isEqualTo("discreto");
        assertThat(garbatella.getNote()).isEmpty();

        // Ogni scarto ha la sua riga di avviso con il motivo, e il riepilogo finale conta tutto
        assertThat(log.livelli()).containsOnly(Level.WARN, Level.INFO);
        List<String> avvisi = log.righe().stream().filter(r -> r.contains("scartata")).toList();
        assertThat(avvisi).hasSize(4);
        assertThat(avvisi.get(0)).contains("riga 7", "pr-007", "lat");
        assertThat(avvisi.get(1)).contains("riga 8", "pr-008", "nome");
        assertThat(avvisi.get(2)).contains("riga 9", "pr-009", "tipo palestra");
        assertThat(avvisi.get(3)).contains("riga 10", "fonteId mancante");
        assertThat(log.righe().getLast())
                .startsWith("Import campetti completato: inseriti 6, aggiornati 0, scartati 4 (")
                .contains("tipo palestra: 1", "fonteId mancante: 1");
        assertThat(comando.getExitCode()).isZero();
    }

    // Un import ripetuto: le righe già nel database (chiave fonte + fonteId) non si inseriscono di nuovo; si aggiornano solo
    // quelle cambiate nell'export, le altre non si toccano
    @Test
    void importRipetuto_aggiornaSoloLeRigheCambiateENonCreaDoppioni() {
        Campetto testaccioVecchio = importato("pr-001", "Campo Testaccio (vecchio nome)");
        Campetto villaAda = importato("pr-003", "Campo Villa Ada"); // identico alla riga pr-003 dell'export
        villaAda.setLat(41.9321);
        villaAda.setLng(12.5010);
        villaAda.setIndirizzo("Via Salaria");
        villaAda.setSuperficie("Cemento");
        villaAda.setCanestri((short) 4);
        villaAda.setGratuito(true);
        villaAda.setStato("discreto");
        when(campetti.findByFonteAndFonteIdIn(eq("pick-roll"), anyCollection())).thenReturn(List.of(testaccioVecchio, villaAda));

        comando.run(argomenti(EXPORT_FINTO));

        ArgumentCaptor<Campetto> salvati = ArgumentCaptor.forClass(Campetto.class);
        verify(campetti, times(5)).save(salvati.capture());
        assertThat(salvati.getAllValues()).extracting(Campetto::getFonteId)
                .containsExactly("pr-001", "pr-002", "pr-004", "pr-005", "pr-006");
        assertThat(salvati.getAllValues().getFirst()).isSameAs(testaccioVecchio);
        assertThat(testaccioVecchio.getNome()).isEqualTo("Campo Testaccio");
        assertThat(testaccioVecchio.getNote()).isEqualTo("Due canestri, uno storto");
        assertThat(villaAda.getNome()).as("la riga uguale non si tocca").isEqualTo("Campo Villa Ada");
        assertThat(log.righe().getLast()).startsWith("Import campetti completato: inseriti 4, aggiornati 1, scartati 4 (");
    }

    // Un import che fallisce non deve passare da un'eccezione: run() non lancia, nei log c'è l'errore, il codice d'uscita è 1
    @Test
    void seLaScritturaFallisce_runNonLanciaEDiceIlMotivo() {
        when(campetti.save(any())).thenThrow(new IllegalStateException("colonna mancante"));

        assertThatCode(() -> comando.run(argomenti(EXPORT_FINTO))).doesNotThrowAnyException();

        assertErrore("colonna mancante");
    }

    /* ── Aiuti ── */

    private ImportCampetti nuovoComando(String adminEmail) {
        // Il gestore delle transazioni è simulato: le transazioni a blocchi le prova ImportCampettiIT
        return new ImportCampetti(campetti, utenti, validator, JsonMapper.builder().build(),
                mock(PlatformTransactionManager.class), new SeedProperties(false, new SeedProperties.Admin(adminEmail, "")));
    }

    private static DefaultApplicationArguments argomenti(Path file) {
        return new DefaultApplicationArguments("--importa-campetti=" + file);
    }

    private Path file(String contenuto) throws Exception {
        Path file = cartella.resolve("export.json");
        Files.writeString(file, contenuto);
        return file;
    }

    /** Un campetto come l'ha lasciato un import precedente: tutti i valori predefiniti e la fonte */
    private Campetto importato(String fonteId, String nome) {
        Campetto c = new Campetto();
        c.setFonte("pick-roll");
        c.setFonteId(fonteId);
        c.setNome(nome);
        c.setCitta("Roma");
        c.setLat(41.9);
        c.setLng(12.5);
        c.setSuperficie("Altro");
        c.setCanestri((short) 2);
        c.setStato("discreto");
        c.setAutore(admin);
        return c;
    }

    /** L'import si è fermato: l'ultima riga è l'ERROR con il motivo, nessun riepilogo INFO, e il codice d'uscita 1 */
    private void assertErrore(String motivo) {
        assertThat(log.livelli()).doesNotContain(Level.INFO).last().isEqualTo(Level.ERROR);
        assertThat(log.righe().getLast()).startsWith("Import campetti fallito").contains(motivo);
        assertThat(comando.getExitCode()).isEqualTo(1);
    }
}
