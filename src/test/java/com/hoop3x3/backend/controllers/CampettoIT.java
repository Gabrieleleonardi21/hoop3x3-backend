package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.CampettoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * I campetti con il database vero e la catena di sicurezza vera: il giro completo (POST, le due ricerche pubbliche, PUT,
 * DELETE), il campetto che sopravvive al suo autore (autore_id ON DELETE SET NULL) e che da allora modifica solo un ADMIN, il
 * 409 tra due client con la stessa versione, il 403 che lascia la riga intatta e il CHECK della migrazione sulle coordinate.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class CampettoIT {

    private static final String CAMPETTI = "/api/campetti";
    /** Il centro di Torino: i campetti del seed e di questi test stanno entro 10 km */
    private static final String LAT_TORINO = "45.07";
    private static final String LNG_TORINO = "7.68";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired CampettoService campettoService;
    @Autowired JdbcTemplate jdbc;

    private Utente mario;
    private Utente luigi;
    private Utente admin;

    @BeforeEach
    void treUtenti() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
    }

    /* ── Il giro completo ── */

    @Test
    void post_get_put_delete_conIlDatabaseVero() throws Exception {
        // POST: 201 con il campetto intestato a chi lo crea, alla versione 0, di tipo campetto, con tutte le 21 chiavi
        JsonNode creato = json(invia(POST, CAMPETTI, campetto("Parco Dora — Le Arcate", 45.08972, 7.66669, null), mario)
                .andExpect(status().isCreated()));
        UUID id = UUID.fromString(creato.get("id").asString());
        assertThat(creato.get("autore").asString()).isEqualTo("Mario");
        assertThat(creato.get("autoreId").asString()).isEqualTo(mario.getId().toString());
        assertThat(creato.get("versione").asLong()).isZero();
        assertThat(creato.get("tipo").asString()).isEqualTo("campetto");
        assertThat(creato.get("coperto").asBoolean()).as("un booleano assente vale false").isFalse();
        assertThat(creato.get("indirizzo").asString()).as("un testo assente è vuoto, mai null").isEmpty();
        assertThat(creato.size()).isEqualTo(21);
        campettoService.crea(luigi, campetto("Campo Vanchiglia", 45.07047, 7.71690, null));
        campettoService.crea(luigi, campetto("Campo lontano", 45.5, 7.68, null)); // a 48 km: fuori dal raggio

        // GET per raggio, senza token: i due entro 20 km, dal più vicino al centro (Vanchiglia dista 2,9 km, Dora 2,4)
        mvc.perform(get(CAMPETTI).param("lat", LAT_TORINO).param("lng", LNG_TORINO).param("raggioKm", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].nome").value("Parco Dora — Le Arcate"))
                .andExpect(jsonPath("$[1].nome").value("Campo Vanchiglia"));

        // GET per testo, senza token: sottostringa e senza distinzione di maiuscole, sul nome o sulla città
        mvc.perform(get(CAMPETTI).param("q", "DORA")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()));
        mvc.perform(get(CAMPETTI).param("q", "torino")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // PUT con la versione letta: 200 con la versione successiva e il ts di adesso
        JsonNode aggiornato = json(invia(PUT, CAMPETTI + "/" + id, campetto("Parco Dora — Campo 1", 45.08972, 7.66669, 0L), mario)
                .andExpect(status().isOk()));
        assertThat(aggiornato.get("nome").asString()).isEqualTo("Parco Dora — Campo 1");
        assertThat(aggiornato.get("versione").asLong()).isEqualTo(1);
        assertThat(aggiornato.get("ts").asLong()).isGreaterThanOrEqualTo(creato.get("ts").asLong());
        assertThat(jdbc.queryForObject("select versione from campetti where id = ?", Long.class, id)).isEqualTo(1);

        // DELETE: 204 e il campetto non c'è più
        invia(DELETE, CAMPETTI + "/" + id, null, mario).andExpect(status().isNoContent());
        mvc.perform(get(CAMPETTI).param("q", "dora")).andExpect(jsonPath("$.length()").value(0));
    }

    /* ── L'ospite non vede l'id dell'autore ── */

    // Come per l'anagrafe: il nome dell'autore è pubblico, il suo id no. Senza token autoreId è null in entrambi i modi della
    // GET; con un token qualsiasi (non serve essere l'autore) c'è
    @Test
    void laGetSenzaToken_nascondeAutoreId_conUnTokenLoMostra() throws Exception {
        campettoService.crea(mario, campetto("Campo Vanchiglia", 45.07047, 7.71690, null));

        mvc.perform(get(CAMPETTI).param("q", "vanchiglia")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].autore").value("Mario"))
                .andExpect(jsonPath("$[0].autoreId").value(nullValue()));
        mvc.perform(get(CAMPETTI).param("lat", LAT_TORINO).param("lng", LNG_TORINO).param("raggioKm", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].autoreId").value(nullValue()));
        mvc.perform(get(CAMPETTI).param("q", "vanchiglia").header(AUTHORIZATION, "Bearer " + jwt.generateToken(luigi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].autore").value("Mario"))
                .andExpect(jsonPath("$[0].autoreId").value(mario.getId().toString()));
    }

    /* ── L'autore eliminato ── */

    // Il campetto è un dato del territorio: sopravvive a chi l'ha inserito (ON DELETE SET NULL), la lettura non dice più chi era,
    // e da allora lo modifica solo un ADMIN, perché nessun utente è il proprietario di un autore nullo
    @Test
    void seLAutoreVieneEliminato_ilCampettoRestaSenzaAutore_eLoModificaSoloUnAdmin() throws Exception {
        UUID id = campettoService.crea(mario, campetto("Campo Vanchiglia", 45.07047, 7.71690, null)).id();

        jdbc.update("delete from utenti where id = ?", mario.getId());

        mvc.perform(get(CAMPETTI).param("q", "vanchiglia")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].autore").value(""))
                .andExpect(jsonPath("$[0].autoreId").value(nullValue()));
        assertThat(jdbc.queryForObject("select autore_id from campetti where id = ?", UUID.class, id)).isNull();
        invia(PUT, CAMPETTI + "/" + id, campetto("Campo Vanchiglia", 45.07047, 7.71690, null), luigi)
                .andExpect(status().isForbidden());
        invia(PUT, CAMPETTI + "/" + id, campetto("Campo Vanchiglia — Rifatto", 45.07047, 7.71690, null), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autore").value(""));
    }

    /* ── Due dispositivi sullo stesso campetto ── */

    @Test
    void duePutConLaStessaVersione_laSecondaRiceve409ENonSalvaNulla() throws Exception {
        UUID id = campettoService.crea(mario, campetto("Parco Ruffini", 45.05923, 7.63174, null)).id();

        invia(PUT, CAMPETTI + "/" + id, campetto("Dal primo dispositivo", 45.05923, 7.63174, 0L), mario).andExpect(status().isOk());
        JsonNode errore = json(invia(PUT, CAMPETTI + "/" + id, campetto("Dal secondo dispositivo", 45.05923, 7.63174, 0L), admin)
                .andExpect(status().isConflict()));

        // Lo stesso corpo {message, timestamp} dei conflitti dell'anagrafe
        assertThat(errore.propertyNames()).containsExactlyInAnyOrder("message", "timestamp");
        assertThat(errore.get("message").asString()).isEqualTo("I dati sono stati modificati o eliminati da un'altra richiesta: ricarica");
        assertThat(nomeNelDatabase(id)).isEqualTo("Dal primo dispositivo");
    }

    /* ── Chi non è l'autore ── */

    @Test
    void chiNonELAutore_riceve403ELaRigaRestaIntatta() throws Exception {
        UUID id = campettoService.crea(mario, campetto("Parco Ruffini", 45.05923, 7.63174, null)).id();
        List<String> prima = righe();

        invia(PUT, CAMPETTI + "/" + id, campetto("Rubato", 45.05923, 7.63174, null), luigi)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Solo chi ha creato questa scheda campetto (o un ADMIN) può modificarla"));
        invia(DELETE, CAMPETTI + "/" + id, null, luigi).andExpect(status().isForbidden());

        assertThat(righe()).isEqualTo(prima);
    }

    /* ── La rete di sicurezza della migrazione ── */

    // La validazione del DTO risponde 400 prima, ma se qualcuno scrivesse in SQL una coordinata impossibile il CHECK della V8 la rifiuta
    @Test
    void unaCoordinataFuoriIntervalloNonEntraNelDatabase() {
        assertThatThrownBy(() -> jdbc.update("insert into campetti (id, nome, lat, lng, superficie, canestri, stato, creato_il, modificato_il) "
                + "values (?, 'Impossibile', 91, 7.68, 'Asfalto', 2, 'buono', now(), now())", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into campetti (id, nome, lat, lng, superficie, canestri, stato, creato_il, modificato_il) "
                + "values (?, 'Impossibile', 45.07, 7.68, 'Asfalto', 9, 'buono', now(), now())", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from campetti", Integer.class)).isZero();
    }

    /* ── Richieste e dati di prova ── */

    /** Un campetto a Torino con quel nome, in quel punto e con la versione letta (null: il client non la manda) */
    private static CampettoRequestDTO campetto(String nome, double lat, double lng, Long versione) {
        return new CampettoRequestDTO(nome, null, "Torino", lat, lng, "Sintetico", 4, true, null, null, null, null, null,
                "buono", null, versione);
    }

    /** La richiesta di `chi`, con il corpo in JSON se c'è */
    private ResultActions invia(HttpMethod metodo, String percorso, Object corpo, Utente chi) throws Exception {
        MockHttpServletRequestBuilder richiesta = request(metodo, percorso).header(AUTHORIZATION, "Bearer " + jwt.generateToken(chi));
        if (corpo != null) richiesta.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo));
        return mvc.perform(richiesta);
    }

    private String nomeNelDatabase(UUID id) {
        return jdbc.queryForObject("select nome from campetti where id = ?", String.class, id);
    }

    /** Nome, versione e data di modifica di ogni campetto: dopo un rifiuto sono gli stessi */
    private List<String> righe() {
        return jdbc.queryForList("select nome || ' ' || versione || ' ' || modificato_il from campetti order by 1", String.class);
    }

    /** La risposta come JSON grezzo: così si contano anche le chiavi, come le vede il frontend */
    private JsonNode json(ResultActions esito) throws Exception {
        return mapper.readTree(esito.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
