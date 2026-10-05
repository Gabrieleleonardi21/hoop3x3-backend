package com.hoop3x3.backend.services;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.RegoleDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-10, posizione delle tappe con il database vero. La tappa nuova prendeva come posizione il numero di tappe della
 * lega: dopo un'eliminazione quel numero è la posizione di una tappa che c'è ancora, e l'ordine tra le due era
 * indefinito. Ora prende una più della massima.
 */
@TestDiIntegrazione
class PosizioneTappeIT {

    @Autowired LegaService legaService;
    @Autowired UtenteRepository utenti;
    @Autowired JdbcTemplate jdbc;
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

    /* ── Dati di prova ── */

    /** Una lega di Mario senza tappe: l'id con cui aggiungerne */
    private UUID legaVuota() {
        return legaService.crea(mario, new NuovaLegaDTO("Circuito", null)).id();
    }

    /** Aggiunge una tappa con il servizio, come fa POST /api/leghe/{id}/tappe */
    private TappaDTO aggiungi(UUID legaId, String nome) {
        return legaService.aggiungiTappa(mario, legaId, tappa(nome));
    }

    /** Una tappa qualsiasi, con un id nuovo */
    private TappaDTO tappa(String nome) {
        return new TappaDTO(UUID.randomUUID(), nome, "Roma", "2026-06-14", 1, new RegoleDTO(21, 10, 2, 12),
                mapper.readTree("[]"), null, mapper.readTree("[]"), mapper.readTree("[]"), false, null);
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
