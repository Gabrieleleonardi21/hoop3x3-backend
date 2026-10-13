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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le PUT dell'anagrafe (giocatori e squadre) con il database vero e la catena di sicurezza vera.
 * <ul>
 *   <li>La risposta è la scheda come sta nel database dopo il salvataggio, a cominciare dal `ts`: la data di modifica la
 *   scrive Hibernate al flush, e senza flush il DTO portava quella di prima, il client la rimandava e la cache restava indietro.</li>
 *   <li>La versione (migrazione V5): due dispositivi dello stesso autore, o l'autore e un ADMIN, aperti sulla stessa scheda
 *   si sovrascrivevano in silenzio. Ora ogni scheda porta una `versione` e il client può rimandarla con la PUT: se non è più
 *   quella del database, 409 e niente salvato. Senza versione nel corpo la PUT passa come prima: i client di prima non cambiano.</li>
 *   <li>Le schede senza autore (V11): le modifica e le elimina solo un ADMIN, e gli elenchi le mostrano senza autore.</li>
 * </ul>
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

    /* ── Versione: la scheda nuova parte da 0, sale a ogni salvataggio che la cambia, e la PUT può pretenderla ── */

    // Il contratto con il frontend: la risposta porta la versione nuova, che il client rimanda con la PUT successiva
    @Test
    void laPutConLaVersioneGiusta_salvaERispondeConLaVersioneSuccessiva() throws Exception {
        assertThat(versioneNelDatabase("anagrafe_giocatori", giocatore)).as("una scheda nuova parte da 0").isZero();

        JsonNode prima = json(salvaGiocatore(giocatore("Bianchi", 0L)).andExpect(status().isOk()));
        JsonNode seconda = json(salvaGiocatore(giocatore("Verdi", prima.get("versione").asLong())).andExpect(status().isOk()));

        assertThat(prima.get("versione").asLong()).isEqualTo(1);
        assertThat(seconda.get("versione").asLong()).isEqualTo(2);
        assertThat(versioneNelDatabase("anagrafe_giocatori", giocatore)).isEqualTo(2);
        // L'elenco porta la stessa versione: è da lì che il client la legge quando apre la scheda
        assertThat(anagrafeService.tuttiGiocatori().getFirst().versione()).isEqualTo(2);
    }

    // Il caso del brief: due dispositivi leggono la scheda alla versione 0, il primo salva, il secondo salva la sua copia vecchia
    @Test
    void laPutConUnaVersioneVecchia_risponde409ConIlCorpoDeiConflittiENonSalvaNulla() throws Exception {
        salvaGiocatore(giocatore("Dal primo dispositivo", 0L)).andExpect(status().isOk());

        JsonNode errore = json(salvaGiocatore(giocatore("Dal secondo dispositivo", 0L)).andExpect(status().isConflict()));

        // Lo stesso corpo {message, timestamp} degli altri conflitti tra richieste (ExceptionsHandler)
        assertThat(errore.propertyNames()).containsExactlyInAnyOrder("message", "timestamp");
        assertThat(errore.get("message").asString()).isEqualTo("I dati sono stati modificati o eliminati da un'altra richiesta: ricarica");
        assertThat(jdbc.queryForObject("select cognome from anagrafe_giocatori where id = ?", String.class, giocatore))
                .isEqualTo("Dal primo dispositivo");
        assertThat(versioneNelDatabase("anagrafe_giocatori", giocatore)).isEqualTo(1);
    }

    @Test
    void laPutDiUnaSquadraConUnaVersioneVecchia_risponde409() throws Exception {
        salvaSquadra(squadra("Roma 3x3 Elite", 0L)).andExpect(status().isOk());

        salvaSquadra(squadra("Roma 3x3 Pro", 0L)).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select nome from anagrafe_squadre where id = ?", String.class, squadra))
                .isEqualTo("Roma 3x3 Elite");
    }

    // Compatibilità: il frontend in produzione non manda la versione, e deve continuare a salvare come prima
    @Test
    void laPutSenzaVersione_salvaComePrima() throws Exception {
        salvaGiocatore(giocatore("Bianchi", 0L)).andExpect(status().isOk());

        JsonNode risposta = json(salvaGiocatore(giocatore("Senza versione", null)).andExpect(status().isOk()));

        assertThat(risposta.get("cognome").asString()).isEqualTo("Senza versione");
        assertThat(risposta.get("versione").asLong()).isEqualTo(2);
    }

    /* ── Schede senza autore (V11): l'autore eliminato le lascia con autore_id NULL ── */

    // Nessun utente è il proprietario di un autore nullo, nemmeno chi l'aveva creata: la modifica e la elimina solo un ADMIN.
    // Gli elenchi, con e senza token, la mostrano senza autore (il frontend non scrive «Registrato da» e non offre «Modifica»)
    @Test
    void unaSchedaSenzaAutore_laModificaSoloUnAdmin_eGliElenchiLaMostranoSenzaAutore() throws Exception {
        jdbc.update("update anagrafe_giocatori set autore_id = null where id = ?", giocatore);
        jdbc.update("update anagrafe_squadre set autore_id = null where id = ?", squadra);
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        Utente admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));

        for (Utente utente : List.of(luigi, mario)) {
            invia(put("/api/anagrafe/giocatori/" + giocatore), utente, giocatore("Bianchi")).andExpect(status().isForbidden());
            invia(put("/api/anagrafe/squadre/" + squadra), utente, squadra("Roma 3x3 Elite")).andExpect(status().isForbidden());
            invia(delete("/api/anagrafe/giocatori/" + giocatore), utente, null).andExpect(status().isForbidden());
        }

        invia(get("/api/anagrafe/giocatori"), luigi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].autore").value("")).andExpect(jsonPath("$[0].autoreId").value(nullValue()));
        invia(get("/api/anagrafe/squadre"), luigi, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].autore").value("")).andExpect(jsonPath("$[0].autoreId").value(nullValue()));
        invia(get("/api/anagrafe/giocatori"), null, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].autore").value(""));
        invia(get("/api/anagrafe/squadre"), null, null).andExpect(status().isOk()).andExpect(jsonPath("$[0].autore").value(""));

        invia(put("/api/anagrafe/giocatori/" + giocatore), admin, giocatore("Bianchi")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cognome").value("Bianchi")).andExpect(jsonPath("$.autoreId").value(nullValue()));
        invia(put("/api/anagrafe/squadre/" + squadra), admin, squadra("Roma 3x3 Elite")).andExpect(status().isOk());
        invia(delete("/api/anagrafe/squadre/" + squadra), admin, null).andExpect(status().isNoContent());
        invia(delete("/api/anagrafe/giocatori/" + giocatore), admin, null).andExpect(status().isNoContent());
    }

    /* ── Richieste e dati di prova ── */

    /** La richiesta di `chi` (null: senza token), con il corpo se c'è */
    private ResultActions invia(MockHttpServletRequestBuilder richiesta, Utente chi, Object corpo) throws Exception {
        if (chi != null) richiesta.header(AUTHORIZATION, "Bearer " + jwt.generateToken(chi));
        if (corpo != null) richiesta.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo));
        return mvc.perform(richiesta);
    }

    /** Un giocatore con quel cognome, senza versione: nome e cognome sono gli unici campi obbligatori */
    private static GiocatoreRequestDTO giocatore(String cognome) {
        return giocatore(cognome, null);
    }

    /** Lo stesso giocatore con la versione che il client ha letto (null: il client non la manda) */
    private static GiocatoreRequestDTO giocatore(String cognome, Long versione) {
        return new GiocatoreRequestDTO("Mario", cognome, null, null, null, null, null, null, null, null, null, null, null, versione);
    }

    private static SquadraRequestDTO squadra(String nome) {
        return squadra(nome, null);
    }

    private static SquadraRequestDTO squadra(String nome, Long versione) {
        return new SquadraRequestDTO(nome, null, null, null, null, null, null, null, null, List.of(), versione);
    }

    private long versioneNelDatabase(String tabella, UUID id) {
        return jdbc.queryForObject("select versione from " + tabella + " where id = ?", Long.class, id);
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
