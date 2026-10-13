package com.hoop3x3.backend;

import com.hoop3x3.backend.MondoDiProva.Mondo;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.CampettoService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.ProfiloService;
import com.hoop3x3.backend.services.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le cancellazioni a cascata dello schema (ON DELETE CASCADE delle migrazioni V1, V2, V9 e V10, SET NULL della V8 e della
 * V11) con il database vero: lega → tappe → pubblicazioni, utente → tutto ciò che è suo (non i campetti e le schede
 * dell'anagrafe, che restano senza autore), squadra e giocatore → le righe del roster. Due utenti hanno le stesse cose
 * (MondoDiProva): dopo ogni eliminazione devono restare quelle dell'altro, e non una riga di più o di meno. I test con una
 * DELETE in SQL saltano Hibernate e provano le chiavi esterne dello schema; gli altri passano dai servizi, come l'app.
 */
@TestDiIntegrazione
class CancellazioniACascataIT {

    /** Le tabelle dello schema, nell'ordine in cui compaiono i conteggi */
    private static final String[] TABELLE = {"utenti", "refresh_tokens", "leghe", "tappe", "archivio_tappe",
            "anagrafe_giocatori", "anagrafe_squadre", "anagrafe_squadre_roster"};

    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeService anagrafeService;
    @Autowired LegaService legaService;
    @Autowired ArchivioService archivioService;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired ProfiloService profiloService;
    @Autowired CampettoService campettoService;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private Mondo mario;
    private Mondo luigi;

    @BeforeEach
    void creaLeCoseDiDueUtenti() {
        MondoDiProva mondi = new MondoDiProva(anagrafeService, legaService, archivioService);
        mario = mondiDi("Mario", mondi);
        luigi = mondiDi("Luigi", mondi);
        // La partenza: ognuno ha tutto, quindi il test non passa perché la fixture è vuota
        assertThat(righe()).isEqualTo(righeDi(2));
    }

    /* ── Una lega porta con sé le sue tappe, e le tappe le loro pubblicazioni ── */

    @Test
    void unaDeleteSqlDellaLega_eliminaLeTappeELePubblicazioni() {
        jdbc.update("delete from leghe where id = ?", mario.lega());

        assertLegaDiMarioEliminata();
    }

    @Test
    void eliminareLaLegaConIlServizio_eliminaLeTappeELePubblicazioni() {
        legaService.elimina(mario.utente(), mario.lega());

        assertLegaDiMarioEliminata();
    }

    private void assertLegaDiMarioEliminata() {
        Map<String, Integer> attese = righeDi(2);
        attese.put("leghe", 1);
        attese.put("tappe", 2);
        attese.put("archivio_tappe", 2);
        assertThat(righe()).isEqualTo(attese);
        assertThat(ids("leghe")).containsExactly(luigi.lega());
        assertThat(ids("tappe")).containsExactlyInAnyOrderElementsOf(luigi.tappe());
        assertThat(ids("archivio_tappe")).containsExactlyInAnyOrderElementsOf(luigi.tappe());
    }

    /* ── Un utente porta con sé tutto ciò che è suo ── */

    @Test
    void unaDeleteSqlDellUtente_eliminaLeghe_tappe_pubblicazioni_eSessioni_maLAnagrafeRestaSenzaAutore() {
        giocatoreDiMarioAncheNellaSquadraDiLuigi();

        jdbc.update("delete from utenti where id = ?", mario.utente().getId());

        assertDiMarioRestaSoloLAnagrafe_senzaAutore();
    }

    // Il servizio del profilo fa la DELETE della riga e il database il resto, nella stessa transazione: via sessioni, token del
    // recupero e del cambio email, leghe con tappe e pubblicazioni; il campetto e le schede dell'anagrafe restano, senza
    // autore (V8, V11)
    @Test
    void eliminareLAccountConIlServizio_eliminaLeghe_tappe_pubblicazioni_sessioni_eToken_maNonICampettiNeLAnagrafe() {
        giocatoreDiMarioAncheNellaSquadraDiLuigi();
        UUID id = mario.utente().getId();
        UUID campetto = campettoService.crea(mario.utente(), mapper.convertValue(Map.of("nome", "Parco Dora", "lat", 45.08972,
                "lng", 7.66669, "superficie", "Sintetico", "canestri", 4, "stato", "buono"), CampettoRequestDTO.class)).id();
        tokenDelRecupero(mario.utente());
        tokenDelRecupero(luigi.utente());
        tokenDelCambioEmail(mario.utente(), "nuova@test.it");
        tokenDelCambioEmail(luigi.utente(), "luigi.nuovo@test.it");
        // La password vera di Mario, per il servizio che la verifica: la fixture salva solo «hash»
        jdbc.update("update utenti set password = ? where id = ?", encoder.encode("password-di-mario"), id);

        profiloService.elimina(utenti.findById(id).orElseThrow(), "password-di-mario");

        assertDiMarioRestaSoloLAnagrafe_senzaAutore();
        assertThat(ids("tappe")).containsExactlyInAnyOrderElementsOf(luigi.tappe());
        assertThat(ids("archivio_tappe")).containsExactlyInAnyOrderElementsOf(luigi.tappe());
        assertThat(jdbc.queryForList("select utente_id from refresh_tokens", UUID.class)).containsExactly(luigi.utente().getId());
        assertThat(jdbc.queryForList("select utente_id from token_recupero_password", UUID.class))
                .containsExactly(luigi.utente().getId());
        assertThat(jdbc.queryForList("select utente_id from token_cambio_email", UUID.class)).containsExactly(luigi.utente().getId());
        assertThat(jdbc.queryForList("select id::text || ' ' || coalesce(autore_id::text, 'senza autore') from campetti",
                String.class)).containsExactly(campetto + " senza autore");
    }

    /** Il giocatore di Mario entra anche nella squadra di Luigi, dopo il suo: dopo l'eliminazione di Mario deve restarci */
    private void giocatoreDiMarioAncheNellaSquadraDiLuigi() {
        anagrafeService.aggiornaSquadra(luigi.utente(), luigi.squadra(), new SquadraRequestDTO("Squadra di Luigi", null, null,
                null, null, null, null, null, null, List.of(luigi.giocatore(), mario.giocatore()), null));
    }

    /**
     * Di Mario restano solo le schede dell'anagrafe, senza autore (V11): le schede sono dati del circuito su persone terze e
     * stanno nei roster degli altri. Tutto il resto è di Luigi, e il roster della sua squadra non ha perso il giocatore di Mario
     */
    private void assertDiMarioRestaSoloLAnagrafe_senzaAutore() {
        Map<String, Integer> attese = righeDi(1);
        attese.put("anagrafe_giocatori", 2);
        attese.put("anagrafe_squadre", 2);
        attese.put("anagrafe_squadre_roster", 3); // la squadra di Mario con il suo giocatore, quella di Luigi con due
        assertThat(righe()).isEqualTo(attese);
        assertThat(ids("utenti")).containsExactly(luigi.utente().getId());
        assertThat(ids("leghe")).containsExactly(luigi.lega());
        assertThat(jdbc.queryForList("select id from anagrafe_giocatori where autore_id is null", UUID.class))
                .containsExactly(mario.giocatore());
        assertThat(jdbc.queryForList("select id from anagrafe_squadre where autore_id is null", UUID.class))
                .containsExactly(mario.squadra());
        assertThat(jdbc.queryForList("select giocatore_id from anagrafe_squadre_roster where squadra_id = ? order by posizione",
                UUID.class, luigi.squadra())).containsExactly(luigi.giocatore(), mario.giocatore());
    }

    /** Un link di recupero password dell'utente, ancora valido, scritto direttamente nel database */
    private void tokenDelRecupero(Utente utente) {
        jdbc.update("insert into token_recupero_password (id, utente_id, token_hash, scadenza, creato_il) "
                + "values (?, ?, ?, now() + interval '30 minutes', now())", UUID.randomUUID(), utente.getId(), hashFinto());
    }

    /** Un link di conferma del cambio email dell'utente, ancora valido, scritto direttamente nel database */
    private void tokenDelCambioEmail(Utente utente, String emailNuova) {
        jdbc.update("insert into token_cambio_email (id, utente_id, email_nuova, token_hash, scadenza, creato_il) "
                + "values (?, ?, ?, ?, now() + interval '30 minutes', now())", UUID.randomUUID(), utente.getId(), emailNuova,
                hashFinto());
    }

    /** 64 caratteri esadecimali diversi a ogni chiamata, come lo SHA-256 di un token */
    private static String hashFinto() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    /* ── Una squadra porta con sé le righe del suo roster, non i giocatori ── */

    @Test
    void unaDeleteSqlDellaSquadra_eliminaIlRosterMaNonIGiocatori() {
        jdbc.update("delete from anagrafe_squadre where id = ?", mario.squadra());

        assertSquadraDiMarioEliminataIGiocatoriRestano();
    }

    @Test
    void eliminareLaSquadraConIlServizio_eliminaIlRosterMaNonIGiocatori() {
        anagrafeService.eliminaSquadra(mario.utente(), mario.squadra());

        assertSquadraDiMarioEliminataIGiocatoriRestano();
    }

    private void assertSquadraDiMarioEliminataIGiocatoriRestano() {
        Map<String, Integer> attese = righeDi(2);
        attese.put("anagrafe_squadre", 1);
        attese.put("anagrafe_squadre_roster", 1);
        assertThat(righe()).isEqualTo(attese);
        assertThat(ids("anagrafe_giocatori")).containsExactlyInAnyOrder(mario.giocatore(), luigi.giocatore());
    }

    /* ── Un giocatore esce dai roster, e le squadre restano ── */

    @Test
    void unaDeleteSqlDelGiocatore_eliminaLaSuaRigaDelRosterMaNonLaSquadra() {
        jdbc.update("delete from anagrafe_giocatori where id = ?", mario.giocatore());

        assertGiocatoreDiMarioEliminatoLeSquadreRestano();
    }

    @Test
    void eliminareIlGiocatoreConIlServizio_eliminaLaSuaRigaDelRosterMaNonLaSquadra() {
        anagrafeService.eliminaGiocatore(mario.utente(), mario.giocatore());

        assertGiocatoreDiMarioEliminatoLeSquadreRestano();
    }

    private void assertGiocatoreDiMarioEliminatoLeSquadreRestano() {
        Map<String, Integer> attese = righeDi(2);
        attese.put("anagrafe_giocatori", 1);
        attese.put("anagrafe_squadre_roster", 1);
        assertThat(righe()).isEqualTo(attese);
        assertThat(ids("anagrafe_squadre")).containsExactlyInAnyOrder(mario.squadra(), luigi.squadra());
    }

    /* ── Dati di prova e letture ── */

    /** Un utente con le sue cose e una sessione (un refresh token) */
    private Mondo mondiDi(String nome, MondoDiProva mondi) {
        Utente utente = utenti.save(new Utente(nome.toLowerCase() + "@test.it", "hash", nome, Ruolo.USER));
        refreshTokenService.emetti(utente);
        return mondi.crea(utente);
    }

    /**
     * Le righe di `mondi` utenti con le loro cose: ognuno ha una sessione, una lega con due tappe pubblicate, un giocatore e una
     * squadra con quel giocatore nel roster
     */
    private static Map<String, Integer> righeDi(int mondi) {
        Map<String, Integer> righe = new LinkedHashMap<>();
        righe.put("utenti", mondi);
        righe.put("refresh_tokens", mondi);
        righe.put("leghe", mondi);
        righe.put("tappe", 2 * mondi);
        righe.put("archivio_tappe", 2 * mondi);
        righe.put("anagrafe_giocatori", mondi);
        righe.put("anagrafe_squadre", mondi);
        righe.put("anagrafe_squadre_roster", mondi);
        return righe;
    }

    /** Quante righe ha ogni tabella */
    private Map<String, Integer> righe() {
        Map<String, Integer> righe = new LinkedHashMap<>();
        for (String tabella : TABELLE) {
            righe.put(tabella, jdbc.queryForObject("select count(*) from " + tabella, Integer.class));
        }
        return righe;
    }

    /** Le chiavi delle righe di una tabella (la colonna `id`, o `tappa_id` per l'archivio) */
    private List<UUID> ids(String tabella) {
        String colonna = "id";
        if (tabella.equals("archivio_tappe")) colonna = "tappa_id";
        return jdbc.queryForList("select " + colonna + " from " + tabella, UUID.class);
    }
}
