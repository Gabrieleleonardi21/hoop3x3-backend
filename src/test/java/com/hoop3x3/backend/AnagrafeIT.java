package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.GiocatoreDTO;
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

/** L'anagrafe con il database vero: il roster di una squadra letto e scritto dal servizio, e le due forme degli elenchi. */
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

    // L'anagrafe è condivisa: la squadra la modifica chi l'ha creata, ma nel roster può mettere i giocatori di chiunque (il controllo
    // di proprietà riguarda la squadra, non chi la compone)
    @Test
    void ilRosterPuoContenereGiocatoriDiAltriAutori() {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        UUID suo = giocatori.save(giocatore(mario, "Suo")).getId();
        UUID diLuigi = giocatori.save(giocatore(luigi, "Di Luigi")).getId();

        SquadraDTO creata = anagrafeService.creaSquadra(mario, richiesta(List.of(suo, diLuigi)));

        assertThat(creata.roster()).containsExactly(suo, diLuigi);
        assertThat(rosterSalvato(creata.id())).containsExactly(suo, diLuigi);
    }

    // Un roster vuoto toglie tutti i giocatori dalla squadra, e le righe del ponte spariscono con loro (non resta nessun buco)
    @Test
    void unRosterVuotoSvuotaLaSquadra_senzaEliminareIGiocatori() {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        UUID uno = giocatori.save(giocatore(mario, "Uno")).getId();
        UUID due = giocatori.save(giocatore(mario, "Due")).getId();
        SquadraDTO creata = anagrafeService.creaSquadra(mario, richiesta(List.of(uno, due)));

        SquadraDTO svuotata = anagrafeService.aggiornaSquadra(mario, creata.id(), richiesta(List.of()));

        assertThat(svuotata.roster()).isEmpty();
        assertThat(rosterSalvato(creata.id())).isEmpty();
        assertThat(giocatori.count()).isEqualTo(2);
    }

    /* ── Roster con buchi: una riga del ponte cancellata dalla cascata del database, senza passare da Hibernate ── */

    // Cancellare un utente in SQL (README, pulizia dei dati demo) elimina i suoi giocatori e, con ON DELETE CASCADE, le loro
    // righe nei roster delle squadre di altri: le posizioni restano con un buco e Hibernate lo legge come un null nella lista.
    // Gli elenchi (GET /api/anagrafe/squadre, per tutti) non devono cadere con un 500: il buco si salta
    @Test
    void unRosterConUnBuco_gliElenchiSaltanoIlBuco() {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        UUID diLuigi = giocatori.save(giocatore(luigi, "Di Luigi")).getId();
        UUID suo = giocatori.save(giocatore(mario, "Suo")).getId();
        anagrafeService.creaSquadra(mario, richiesta(List.of(diLuigi, suo)));

        jdbc.update("delete from utenti where id = ?", luigi.getId()); // il buco è in posizione 0

        assertThat(anagrafeService.tutteSquadre().getFirst().roster()).containsExactly(suo);
        assertThat(anagrafeService.tutteSquadrePubbliche().getFirst().roster()).containsExactly(suo);
    }

    // Eliminare un giocatore da una squadra con un buco non deve cadere sul null: il roster si ricompatta senza il buco
    @Test
    void eliminareUnGiocatoreDaUnRosterConUnBuco_ricompattaIlRoster() {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        UUID diLuigi = giocatori.save(giocatore(luigi, "Di Luigi")).getId();
        UUID suo = giocatori.save(giocatore(mario, "Suo")).getId();
        UUID altro = giocatori.save(giocatore(mario, "Altro")).getId();
        SquadraDTO creata = anagrafeService.creaSquadra(mario, richiesta(List.of(diLuigi, suo, altro)));
        jdbc.update("delete from utenti where id = ?", luigi.getId());

        anagrafeService.eliminaGiocatore(mario, suo);

        assertThat(rosterSalvato(creata.id())).containsExactly(altro);
        assertThat(jdbc.queryForList("select posizione from anagrafe_squadre_roster where squadra_id = ?", Integer.class,
                creata.id())).containsExactly(0);
    }

    // Le forme degli elenchi si costruiscono dentro la transazione di lettura (open-in-view è spento), con il roster e l'autore
    // caricati dalla stessa query: dal database vero la forma pubblica esce senza dati personali, e la completa li ha
    @Test
    void leFormePubblicheDelDatabaseNonHannoDatiPersonali_laCompletaSi() {
        Utente mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        AnagrafeGiocatore g = giocatore(mario, "Rossi");
        g.setNascita("1998-03-15");
        g.setCitta("Roma");
        g.setNote("Una nota");
        g.setSquadra("Roma 3x3");
        g = giocatori.save(g);
        anagrafeService.creaSquadra(mario, new SquadraRequestDTO("Roma 3x3", "Roma", null, null, "Luigi Bianchi", null, null,
                null, null, List.of(g.getId())));

        GiocatoreDTO giocatorePubblico = anagrafeService.tuttiGiocatoriPubblici().getFirst();
        SquadraDTO squadraPubblica = anagrafeService.tutteSquadrePubbliche().getFirst();
        GiocatoreDTO giocatoreCompleto = anagrafeService.tuttiGiocatori().getFirst();
        SquadraDTO squadraCompleta = anagrafeService.tutteSquadre().getFirst();

        assertThat(giocatorePubblico.cognome()).isEqualTo("Rossi");
        assertThat(giocatorePubblico.squadra()).isEqualTo("Roma 3x3");
        assertThat(giocatorePubblico.nascita()).isEmpty();
        assertThat(giocatorePubblico.citta()).isEmpty();
        assertThat(giocatorePubblico.note()).isEmpty();
        assertThat(giocatorePubblico.autore()).isEmpty();
        assertThat(giocatorePubblico.autoreId()).isNull();
        assertThat(squadraPubblica.nome()).isEqualTo("Roma 3x3");
        assertThat(squadraPubblica.citta()).isEqualTo("Roma");
        assertThat(squadraPubblica.roster()).containsExactly(g.getId());
        assertThat(squadraPubblica.referente()).isEmpty();
        assertThat(squadraPubblica.autore()).isEmpty();
        assertThat(squadraPubblica.autoreId()).isNull();

        assertThat(giocatoreCompleto.nascita()).isEqualTo("1998-03-15");
        assertThat(giocatoreCompleto.note()).isEqualTo("Una nota");
        assertThat(giocatoreCompleto.autore()).isEqualTo("Mario");
        assertThat(giocatoreCompleto.autoreId()).isEqualTo(mario.getId());
        assertThat(squadraCompleta.referente()).isEqualTo("Luigi Bianchi");
        assertThat(squadraCompleta.autoreId()).isEqualTo(mario.getId());
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
