package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.support.Tempo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le PUT dell'anagrafe (giocatori e squadre) con il database vero e la catena di sicurezza vera: la risposta è la scheda come
 * sta nel database dopo il salvataggio, a cominciare dal `ts`. La data di modifica la scrive Hibernate al flush, e senza
 * flush il DTO della risposta portava quella di prima: il client la rimandava e la cache restava indietro.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class AnagrafeAggiornamentoIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired AnagrafeService anagrafeService;
    @Autowired JdbcTemplate jdbc;

    private Utente mario; // autore delle schede di prova
    private UUID giocatore;
    private UUID squadra;

    @BeforeEach
    void unaSchedaPerTipo() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        giocatore = anagrafeService.creaGiocatore(mario, giocatore("Rossi")).id();
        squadra = anagrafeService.creaSquadra(mario, squadra("Roma 3x3")).id();
        // Le schede risalgono a ieri: un ts «di prima» si distingue da quello di adesso anche a occhio
        LocalDateTime ieri = Tempo.adesso().minusDays(1);
        jdbc.update("update anagrafe_giocatori set modificato_il = ?", ieri);
        jdbc.update("update anagrafe_squadre set modificato_il = ?", ieri);
    }

    /* ── ts: quello del salvataggio appena fatto, uguale a ciò che sta nel database ── */

    @Test
    void laPutDiUnGiocatore_rispondeConIlTsAggiornato() throws Exception {
        JsonNode risposta = json(salvaGiocatore(giocatore("Bianchi")).andExpect(status().isOk()));

        assertThat(risposta.get("cognome").asString()).isEqualTo("Bianchi");
        assertThat(risposta.get("ts").asLong())
                .isEqualTo(Tempo.inMillisecondi(modificatoIl("anagrafe_giocatori", giocatore)))
                .isGreaterThan(Tempo.inMillisecondi(Tempo.adesso().minusHours(1)));
    }

    @Test
    void laPutDiUnaSquadra_rispondeConIlTsAggiornato() throws Exception {
        JsonNode risposta = json(salvaSquadra(squadra("Roma 3x3 Elite")).andExpect(status().isOk()));

        assertThat(risposta.get("nome").asString()).isEqualTo("Roma 3x3 Elite");
        assertThat(risposta.get("ts").asLong())
                .isEqualTo(Tempo.inMillisecondi(modificatoIl("anagrafe_squadre", squadra)))
                .isGreaterThan(Tempo.inMillisecondi(Tempo.adesso().minusHours(1)));
    }

    /* ── Richieste e dati di prova ── */

    /** Un giocatore con quel cognome: nome e cognome sono gli unici campi obbligatori */
    private static GiocatoreRequestDTO giocatore(String cognome) {
        return new GiocatoreRequestDTO("Mario", cognome, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SquadraRequestDTO squadra(String nome) {
        return new SquadraRequestDTO(nome, null, null, null, null, null, null, null, null, List.of());
    }

    private ResultActions salvaGiocatore(GiocatoreRequestDTO corpo) throws Exception {
        return mvc.perform(put("/api/anagrafe/giocatori/" + giocatore).header(AUTHORIZATION, "Bearer " + jwt.generateToken(mario))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)));
    }

    private ResultActions salvaSquadra(SquadraRequestDTO corpo) throws Exception {
        return mvc.perform(put("/api/anagrafe/squadre/" + squadra).header(AUTHORIZATION, "Bearer " + jwt.generateToken(mario))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)));
    }

    /** La data di modifica di una scheda, come sta nel database */
    private LocalDateTime modificatoIl(String tabella, UUID id) {
        return jdbc.queryForObject("select modificato_il from " + tabella + " where id = ?", LocalDateTime.class, id);
    }

    private JsonNode json(ResultActions esito) throws Exception {
        return mapper.readTree(esito.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
