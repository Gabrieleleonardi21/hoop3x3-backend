package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.RichiesteContemporanee;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
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
 * in silenzio. Ora ogni tappa ha una versione, che il server aumenta a ogni salvataggio e che il client rimanda con la PUT:
 * senza versione la PUT è un 400, con una versione che non è più quella del database un 409.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class VersioneTappeIT {

    // I messaggi che il client legge: il frontend li mostra e li distingue per metodo e stato, quindi il testo è un contratto
    private static final String TAPPA_MODIFICATA = "La tappa è stata modificata da un altro dispositivo: ricaricala";
    private static final String SENZA_VERSIONE = "Manca la versione della tappa (campo versione): ricarica la pagina e riprova";
    // Una partita giocata: Team Rome batte Team Milan 21-17
    private static final String PARTITA_GIOCATA = "[{\"id\":\"m1\",\"a\":\"s1\",\"b\":\"s2\",\"sa\":21,\"sb\":17,\"done\":true}]";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JWTtools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transazioni;

    private Utente mario; // proprietario della lega di prova
    private RichiesteContemporanee insieme;

    @BeforeEach
    void creaIlProprietario() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        insieme = new RichiesteContemporanee(jdbc, transazioni);
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

        salva(tappa.id(), TappaDiProva.da(tappa).nome("Prima modifica").build(), 0L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Prima modifica"))
                .andExpect(jsonPath("$.versione").value(1));
        // Il client rimanda la versione ricevuta: così salva una dopo l'altra, come fa la sua coda
        salva(tappa.id(), TappaDiProva.da(tappa).nome("Seconda modifica").build(), 1L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versione").value(2));

        leggi(lega).andExpect(jsonPath("$.tappe[0].nome").value("Seconda modifica"))
                .andExpect(jsonPath("$.tappe[0].versione").value(2));
    }

    /* ── La PUT deve portare la versione letta dal client ── */

    // Il caso del brief: due dispositivi leggono la tappa alla versione 0. Il primo salva una partita giocata, il secondo, che non
    // la conosce, salva la sua copia vecchia: se passasse, la partita sparirebbe in silenzio
    @Test
    void dueDispositiviConLaStessaVersione_ilSecondoRiceve409ENonCancellaIlLavoroDelPrimo() throws Exception {
        UUID lega = nuovaLega();
        TappaDTO tappa = TappaDiProva.tappa().squadre("[{\"id\":\"s1\"},{\"id\":\"s2\"}]").build();
        aggiungi(lega, tappa, null).andExpect(status().isCreated());

        salva(tappa.id(), TappaDiProva.da(tappa).partite(PARTITA_GIOCATA).build(), 0L).andExpect(status().isOk());
        salva(tappa.id(), TappaDiProva.da(tappa).nome("Dal secondo dispositivo").build(), 0L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(TAPPA_MODIFICATA))
                .andExpect(jsonPath("$.timestamp").exists());

        // La tappa è com'era dopo il primo salvataggio: la partita c'è ancora, il nome del secondo no
        leggi(lega).andExpect(jsonPath("$.tappe[0].nome").value("Tappa"))
                .andExpect(jsonPath("$.tappe[0].partite[0].sa").value(21))
                .andExpect(jsonPath("$.tappe[0].versione").value(1));
    }

    // Qualunque versione diversa da quella del database è un conflitto: una più avanti (il client ha una tappa che il server non ha
    // mai avuto) come una negativa. Non solo «più vecchia»: il controllo è un'uguaglianza
    @Test
    void unaVersioneDiversaDaQuellaDelDatabase_risponde409() throws Exception {
        UUID lega = nuovaLega();
        TappaDTO tappa = TappaDiProva.tappa().build();
        aggiungi(lega, tappa, null).andExpect(status().isCreated());

        salva(tappa.id(), tappa, 5L).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(TAPPA_MODIFICATA));
        salva(tappa.id(), tappa, -1L).andExpect(status().isConflict());

        leggi(lega).andExpect(jsonPath("$.tappe[0].versione").value(0));
    }

    // Senza la versione la PUT non può sapere se sovrascrive il lavoro di un altro dispositivo: niente salvataggio silenzioso.
    // Il frontend nuovo la manda sempre; la rifiuta chi non la manda, per esempio una pagina aperta prima dell'aggiornamento
    @Test
    void unaPutSenzaVersione_risponde400ENonSalvaNulla() throws Exception {
        UUID lega = nuovaLega();
        TappaDTO tappa = TappaDiProva.tappa().build();
        aggiungi(lega, tappa, null).andExpect(status().isCreated());
        TappaDTO modificata = TappaDiProva.da(tappa).nome("Modificata").build();

        // Il campo manca del tutto...
        salva(tappa.id(), modificata, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SENZA_VERSIONE))
                .andExpect(jsonPath("$.timestamp").exists());
        // ...oppure c'è e vale null
        mvc.perform(put("/api/tappe/" + tappa.id()).header(AUTHORIZATION, bearer(mario))
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(modificata)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(SENZA_VERSIONE));

        leggi(lega).andExpect(jsonPath("$.tappe[0].nome").value("Tappa")).andExpect(jsonPath("$.tappe[0].versione").value(0));
    }

    // Chi non è il proprietario non deve poter scoprire la versione di una tappa altrui provando numeri: il 403 viene prima del
    // 409, con la versione giusta come con una sbagliata
    @Test
    void chiNonEProprietarioRiceve403AncheConUnaVersioneSbagliata() throws Exception {
        UUID lega = nuovaLega();
        TappaDTO tappa = TappaDiProva.tappa().build();
        aggiungi(lega, tappa, null).andExpect(status().isCreated());
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));

        salva(luigi, tappa.id(), tappa, 99L).andExpect(status().isForbidden());
        salva(luigi, tappa.id(), tappa, 0L).andExpect(status().isForbidden());

        leggi(lega).andExpect(jsonPath("$.tappe[0].versione").value(0));
    }

    /* ── Due richieste insieme: la versione è giusta quando la richiesta comincia, ma cambia mentre salva ── */

    // Il controllo della versione nel servizio non basta: la seconda richiesta legge la tappa alla versione 0, giusta, e prima che
    // scriva il primo dispositivo conferma la sua. L'intreccio è forzato, non lasciato al caso: la tappa del primo è scritta e
    // non confermata, la seconda legge la versione 0 e si ferma sul lock della riga, poi il primo conferma. A quel punto
    // l'UPDATE della seconda («where versione = 0») non trova più la riga e Hibernate la rifiuta: 409, come per una versione già vecchia
    @Test
    void dueRichiesteInsieme_laSecondaRiceve409() throws Exception {
        UUID lega = nuovaLega();
        TappaDTO tappa = TappaDiProva.tappa().build();
        aggiungi(lega, tappa, null).andExpect(status().isCreated());

        ResultActions seconda = insieme.mentreUnaTransazioneTieneUnaRiga(
                () -> jdbc.update("update tappe set nome = 'Dal primo dispositivo', versione = versione + 1 where id = ?", tappa.id()),
                () -> salva(tappa.id(), TappaDiProva.da(tappa).nome("Dal secondo dispositivo").build(), 0L));

        seconda.andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(TAPPA_MODIFICATA))
                .andExpect(jsonPath("$.timestamp").exists());
        // Resta il salvataggio del primo, e la richiesta respinta non ha lasciato niente
        leggi(lega).andExpect(jsonPath("$.tappe[0].nome").value("Dal primo dispositivo"))
                .andExpect(jsonPath("$.tappe[0].versione").value(1));
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
        return salva(mario, tappaId, tappa, versione);
    }

    /** La stessa PUT, mandata da `chi` */
    private ResultActions salva(Utente chi, UUID tappaId, TappaDTO tappa, Long versione) throws Exception {
        return mvc.perform(put("/api/tappe/" + tappaId).header(AUTHORIZATION, bearer(chi))
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
