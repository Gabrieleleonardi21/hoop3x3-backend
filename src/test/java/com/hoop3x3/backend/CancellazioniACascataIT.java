package com.hoop3x3.backend;

import com.hoop3x3.backend.MondoDiProva.Mondo;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le cancellazioni a cascata dello schema (ON DELETE CASCADE delle migrazioni V1 e V2) con il database vero: lega → tappe →
 * pubblicazioni, utente → tutto ciò che è suo, squadra e giocatore → le righe del roster. Due utenti hanno le stesse cose
 * (MondoDiProva): dopo ogni eliminazione devono restare quelle dell'altro, e non una riga di più o di meno. I test con una
 * DELETE in SQL saltano Hibernate e provano le chiavi esterne dello schema; gli altri passano dai servizi, come l'app.
 */
@TestDiIntegrazione
class CancellazioniACascataIT {

    /** Le tabelle dello schema, nell'ordine in cui compaiono i conteggi */
    private static final String[] TABELLE = {"utenti", "refresh_tokens", "leghe", "tappe", "archivio_tappe",
            "anagrafe_giocatori", "anagrafe_squadre", "anagrafe_squadre_roster"};

    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeGiocatoreRepository giocatori;
    @Autowired AnagrafeService anagrafeService;
    @Autowired LegaService legaService;
    @Autowired ArchivioService archivioService;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired JdbcTemplate jdbc;

    private Mondo mario;
    private Mondo luigi;

    @BeforeEach
    void creaLeCoseDiDueUtenti() {
        MondoDiProva mondi = new MondoDiProva(giocatori, anagrafeService, legaService, archivioService);
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
    void unaDeleteSqlDellUtente_eliminaLeghe_tappe_pubblicazioni_anagrafe_eSessioni() {
        jdbc.update("delete from utenti where id = ?", mario.utente().getId());

        // Resta una sola copia di ogni cosa, ed è quella di Luigi
        assertThat(righe()).isEqualTo(righeDi(1));
        assertThat(ids("utenti")).containsExactly(luigi.utente().getId());
        assertThat(ids("leghe")).containsExactly(luigi.lega());
        assertThat(ids("anagrafe_giocatori")).containsExactly(luigi.giocatore());
        assertThat(ids("anagrafe_squadre")).containsExactly(luigi.squadra());
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
