package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JWTtools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-9, modifiche concorrenti alle tappe, con il database vero e la catena di sicurezza vera (JWT compreso). La PUT
 * sostituisce la tappa intera, partite comprese: due dispositivi (o due schede) aperti sulla stessa tappa si sovrascrivevano
 * in silenzio. Ora ogni tappa ha una versione, che il server aumenta a ogni salvataggio e che il client rimanda con la PUT.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class VersioneTappeIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JWTtools jwt;
    @Autowired UtenteRepository utenti;

    private Utente mario; // proprietario della lega di prova

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
    }

    /* ── La versione di una tappa nuova: 0, qualunque cosa scriva il client ── */

    @Test
    void unaTappaAppenaCreataHaVersioneZero() throws Exception {
        UUID lega = nuovaLega();

        aggiungi(lega, TappaDiProva.tappa().build(), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versione").value(0));
    }

    // La versione la decide il server: quella di una POST, se c'è, non conta
    @Test
    void laVersioneNelCorpoDiUnaPostSiIgnora() throws Exception {
        UUID lega = nuovaLega();

        aggiungi(lega, TappaDiProva.tappa().build(), 7L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versione").value(0));
    }

    // L'import di una lega da file porta le tappe con la versione che avevano: tutte ripartono da 0, come tappe nuove
    @Test
    void leTappeImportateDaFileHannoTutteVersioneZero() throws Exception {
        ObjectNode prima = mapper.valueToTree(TappaDiProva.tappa().nome("Prima").build());
        ObjectNode seconda = mapper.valueToTree(TappaDiProva.tappa().nome("Seconda").build());
        prima.put("versione", 3);
        seconda.put("versione", 5);
        ObjectNode file = mapper.createObjectNode().put("nome", "Importata");
        file.putArray("tappe").add(prima).add(seconda);

        JsonNode creata = json(mvc.perform(post("/api/leghe").header(AUTHORIZATION, bearer(mario))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(file)))
                .andExpect(status().isCreated()));

        leggi(UUID.fromString(creata.get("id").asString())).andExpect(jsonPath("$.tappe[*].versione", contains(0, 0)));
    }

    /* ── La versione sale a ogni salvataggio, e la risposta porta quella nuova ── */

    @Test
    void laPutRispondeConLaVersioneSuccessiva_eLaGetLaConferma() throws Exception {
        UUID lega = nuovaLega();
        TappaDTO tappa = TappaDiProva.tappa().build();
        aggiungi(lega, tappa, null).andExpect(status().isCreated());

        salva(tappa.id(), TappaDiProva.tappa().id(tappa.id()).nome("Prima modifica").build(), 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Prima modifica"))
                .andExpect(jsonPath("$.versione").value(1));
        // Il client rimanda la versione ricevuta: così salva una dopo l'altra, come fa la sua coda
        salva(tappa.id(), TappaDiProva.tappa().id(tappa.id()).nome("Seconda modifica").build(), 1L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versione").value(2));

        leggi(lega).andExpect(jsonPath("$.tappe[0].nome").value("Seconda modifica"))
                .andExpect(jsonPath("$.tappe[0].versione").value(2));
    }

    /* ── Richieste e dati di prova ── */

    private String bearer(Utente utente) {
        return "Bearer " + jwt.generateToken(utente);
    }

    /** Una lega vuota di Mario, creata dall'API: l'id con cui aggiungerle tappe */
    private UUID nuovaLega() throws Exception {
        JsonNode creata = json(mvc.perform(post("/api/leghe").header(AUTHORIZATION, bearer(mario))
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\": \"Circuito 2026\"}"))
                .andExpect(status().isCreated()));
        return UUID.fromString(creata.get("id").asString());
    }

    /** POST di una tappa nuova di Mario, con la versione che il corpo porta (null: il campo non c'è) */
    private ResultActions aggiungi(UUID lega, TappaDTO tappa, Long versione) throws Exception {
        return mvc.perform(post("/api/leghe/" + lega + "/tappe").header(AUTHORIZATION, bearer(mario))
                .contentType(MediaType.APPLICATION_JSON).content(corpo(tappa, versione)));
    }

    /** PUT della tappa di Mario con la versione che il client ha letto (null: il campo non c'è) */
    private ResultActions salva(UUID tappaId, TappaDTO tappa, Long versione) throws Exception {
        return mvc.perform(put("/api/tappe/" + tappaId).header(AUTHORIZATION, bearer(mario))
                .contentType(MediaType.APPLICATION_JSON).content(corpo(tappa, versione)));
    }

    /** GET della lega di Mario con le sue tappe, come le legge il client */
    private ResultActions leggi(UUID lega) throws Exception {
        return mvc.perform(get("/api/leghe/" + lega).header(AUTHORIZATION, bearer(mario))).andExpect(status().isOk());
    }

    /**
     * Il corpo di una richiesta: la tappa con la versione indicata. Il campo si scrive a mano e non dal DTO, così un test può
     * mandare anche una tappa senza versione, come un client che non la conosce
     */
    private String corpo(TappaDTO tappa, Long versione) {
        ObjectNode corpo = mapper.valueToTree(tappa);
        corpo.remove("versione");
        if (versione != null) corpo.put("versione", versione);
        return mapper.writeValueAsString(corpo);
    }

    /** Il corpo della risposta, letto come JSON */
    private JsonNode json(ResultActions esito) throws Exception {
        return mapper.readTree(esito.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
