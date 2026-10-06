package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.SquadraDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.AnagrafeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** L'anagrafe con il database vero: il roster di una squadra letto e scritto dal servizio. */
@TestDiIntegrazione
class AnagrafeIT {

    @Autowired AnagrafeService anagrafeService;
    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeGiocatoreRepository giocatori;
    @Autowired JdbcTemplate jdbc;

    // I giocatori si leggono con una query sola (findAllById) e il database li restituisce nel suo ordine, non in quello del
    // client: il roster salvato deve avere comunque l'ordine richiesto. Qui è l'inverso di quello di creazione, che è
    // l'ordine in cui una lettura sequenziale restituirebbe le righe
    @Test
    void ilRosterSiSalvaNellOrdineRichiesto_ancheQuandoIlDatabaseLiRestituisceInUnAltro() {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        List<UUID> creati = new ArrayList<>();
        for (int i = 0; i < 8; i++) creati.add(giocatori.save(giocatore(mario, "Cognome " + i)).getId());
        List<UUID> inversi = new ArrayList<>(creati);
        Collections.reverse(inversi);

        SquadraDTO creata = anagrafeService.creaSquadra(mario, richiesta(inversi));

        assertThat(creata.roster()).containsExactlyElementsOf(inversi);
        assertThat(rosterSalvato(creata.id())).containsExactlyElementsOf(inversi);

        // Anche aggiornando: un altro ordine, un id sconosciuto e un doppione che si ignorano
        List<UUID> richiesti = List.of(creati.get(5), creati.get(1), UUID.randomUUID(), creati.get(5), creati.get(7));
        SquadraDTO aggiornata = anagrafeService.aggiornaSquadra(mario, creata.id(), richiesta(richiesti));

        List<UUID> attesi = List.of(creati.get(5), creati.get(1), creati.get(7));
        assertThat(aggiornata.roster()).containsExactlyElementsOf(attesi);
        assertThat(rosterSalvato(creata.id())).containsExactlyElementsOf(attesi);
    }

    private static AnagrafeGiocatore giocatore(Utente autore, String cognome) {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        g.setNome("Nome");
        g.setCognome(cognome);
        g.setAutore(autore);
        return g;
    }

    private static SquadraRequestDTO richiesta(List<UUID> roster) {
        return new SquadraRequestDTO("Squadra", null, null, null, null, null, null, null, null, roster);
    }

    /** Gli id del roster come sono nella tabella ponte, per posizione */
    private List<UUID> rosterSalvato(UUID squadraId) {
        return jdbc.queryForList("select giocatore_id from anagrafe_squadre_roster where squadra_id = ? order by posizione",
                UUID.class, squadraId);
    }
}
