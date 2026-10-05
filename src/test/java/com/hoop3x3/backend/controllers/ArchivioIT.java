package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.RegoleDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.ArchivioTappaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.services.LegaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pubblicazione in archivio con il database vero e la catena di sicurezza vera (JWT compreso). BE-7: l'archivio
 * salvava la tappa che mandava il client senza controllare nulla, quindi un utente registrato poteva pubblicare
 * risultati inventati. Ora il client non manda niente: il server pubblica ciò che ha salvato, se la tappa è conclusa
 * e se è sua (o se chi pubblica è ADMIN).
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class ArchivioIT {

    // I dati veri della tappa: Team Rome batte Team Milan 21-17
    private static final String SQUADRE = "[{\"id\":\"s1\",\"nome\":\"Team Rome\"},{\"id\":\"s2\",\"nome\":\"Team Milan\"}]";
    private static final String PARTITE = "[{\"id\":\"m1\",\"a\":\"s1\",\"b\":\"s2\",\"sa\":21,\"sb\":17,\"done\":true}]";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JWTtools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired TappaRepository tappe;
    @Autowired ArchivioTappaRepository archivio;
    @Autowired LegaService legaService;

    private Utente mario; // proprietario delle leghe di prova
    private Utente luigi; // un altro utente
    private Utente admin;

    @BeforeEach
    void creaGliUtenti() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
    }

    // Il cuore di BE-7: un client vecchio manda ancora {tappa, lega}, qui con nomi e punteggi inventati e perfino un
    // altro id di tappa. Il corpo non si legge: si pubblicano i dati del database, con la chiave del percorso
    @Test
    void unCorpoInventatoNonCambiaNulla_siPubblicanoIDatiDelDatabase() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");
        String corpoInventato = """
                {"tappa": {"id": "%s", "nome": "Tappa inventata", "nGironi": 1,
                           "regole": {"target": 21, "durata": 10, "ot": 2, "shot": 12},
                           "squadre": [{"id": "x1", "nome": "Squadra falsa"}],
                           "partite": [{"id": "m9", "a": "x1", "b": "x1", "sa": 99, "sb": 0, "done": true}]},
                 "lega": "Lega inventata"}""".formatted(UUID.randomUUID());

        String risposta = corpo(mvc.perform(put("/api/archivio/" + tappaId).header(AUTHORIZATION, bearer(mario))
                .contentType(MediaType.APPLICATION_JSON).content(corpoInventato)).andExpect(status().isOk()));

        // La risposta ha i dati del database...
        JsonNode pubblicata = mapper.readTree(risposta);
        assertThat(pubblicata.at("/lega").asString()).isEqualTo("Circuito 2026");
        assertThat(pubblicata.at("/tappa/nome").asString()).isEqualTo("Tappa di Roma");
        assertThat(pubblicata.at("/tappa/partite/0/sa").asInt()).isEqualTo(21);
        // ...e così la riga salvata, che ha la chiave del percorso e non quella del corpo
        assertThat(archivio.findAll()).singleElement().satisfies(riga -> {
            assertThat(riga.getTappaId()).isEqualTo(tappaId);
            assertThat(riga.getLegaNome()).isEqualTo("Circuito 2026");
            assertThat(riga.getContenuto()).doesNotContain("inventat", "falsa");
        });
    }

    // Lo snapshot ha la stessa forma che il frontend riceve dalle API delle tappe e che la pagina pubblica già legge
    @Test
    void loSnapshotPubblicatoEUgualeAllaTappaSalvataNelDatabase() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        pubblica(tappaId, mario).andExpect(status().isOk());

        TappaDTO salvata = legaService.toDto(tappe.findById(tappaId).orElseThrow());
        JsonNode pubblico = letta(tappaId);
        assertThat(pubblico.get("tappa")).isEqualTo(mapper.valueToTree(salvata));
        assertThat(pubblico.get("lega").asString()).isEqualTo("Circuito 2026");
        assertThat(pubblico.get("autoreId").asString()).isEqualTo(mario.getId().toString());
    }

    @Test
    void unAdminChePubblicaLaTappaDiUnAltroUtenteLasciaComeAutoreIlProprietario() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        pubblica(tappaId, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoreId").value(mario.getId().toString()))
                .andExpect(jsonPath("$.autore").value("Mario"));
        // Vale anche alle pubblicazioni successive, non solo alla prima
        pubblica(tappaId, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoreId").value(mario.getId().toString()));
    }

    // È il motivo per cui l'autore è il proprietario: se avesse pubblicato l'ADMIN, Mario non potrebbe più ritirarla
    @Test
    void ilProprietarioPuoRitirareLaPubblicazioneFattaDaUnAdmin() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");
        pubblica(tappaId, admin).andExpect(status().isOk());

        mvc.perform(delete("/api/archivio/" + tappaId).header(AUTHORIZATION, bearer(mario)))
                .andExpect(status().isNoContent());

        assertThat(archivio.count()).isZero();
    }

    // Una pubblicazione fatta con il vecchio endpoint ha come autore chi l'ha mandata, che può non essere il proprietario
    // della lega: alla ripubblicazione l'autore torna a essere il proprietario
    @Test
    void laRipubblicazioneRiportaAlProprietarioLAutoreDiUnaPubblicazioneVecchia() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");
        ArchivioTappa vecchia = new ArchivioTappa();
        vecchia.setTappaId(tappaId);
        vecchia.setLegaNome("Nome vecchio");
        vecchia.setAutore(luigi);
        vecchia.setContenuto("{}");
        vecchia.setPubblicatoIl(LocalDateTime.now().minusDays(1));
        archivio.save(vecchia);

        pubblica(tappaId, mario).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoreId").value(mario.getId().toString()))
                .andExpect(jsonPath("$.lega").value("Circuito 2026"));
    }

    @Test
    void ripubblicareAggiornaLoSnapshotESostituisceLaRigaSenzaDuplicarla() throws Exception {
        TappaDTO tappa = tappaDto("Tappa di Roma", true);
        lega(mario, "Circuito 2026", tappa);
        pubblica(tappa.id(), mario).andExpect(status().isOk());
        // La prima pubblicazione risale a ieri: dopo la ripubblicazione la data deve essere quella di adesso
        ArchivioTappa riga = archivio.findById(tappa.id()).orElseThrow();
        riga.setPubblicatoIl(LocalDateTime.now().minusDays(1));
        archivio.save(riga);

        // La tappa cambia dopo la prima pubblicazione: la copia pubblica resta com'era finché non si ripubblica
        legaService.aggiornaTappa(mario, tappa.id(), tappaDto(tappa.id(), "Tappa di Roma (finale)", true));
        assertThat(letta(tappa.id()).at("/tappa/nome").asString()).isEqualTo("Tappa di Roma");

        pubblica(tappa.id(), mario).andExpect(status().isOk())
                .andExpect(jsonPath("$.tappa.nome").value("Tappa di Roma (finale)"));
        assertThat(archivio.count()).isEqualTo(1);
        JsonNode ripubblicata = letta(tappa.id());
        assertThat(ripubblicata.at("/tappa/nome").asString()).isEqualTo("Tappa di Roma (finale)");
        assertThat(ripubblicata.get("ts").asLong()).isGreaterThan(Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli());
    }

    @Test
    void unaTappaCheNonEsiste_risponde404() throws Exception {
        pubblica(UUID.randomUUID(), mario).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(startsWith("Tappa non trovata")))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(archivio.count()).isZero();
    }

    @Test
    void laTappaDiUnAltroUtente_risponde403ENonPubblicaNulla() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        pubblica(tappaId, luigi).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("questa tappa")));

        assertThat(archivio.count()).isZero();
    }

    // Chi non è il proprietario non deve poter scoprire se la tappa è conclusa: il 403 vince sul 409
    @Test
    void laTappaNonConclusaDiUnAltroUtente_risponde403NonConflitto() throws Exception {
        TappaDTO tappa = tappaDto("Tappa di Roma", false);
        lega(mario, "Circuito 2026", tappa);

        pubblica(tappa.id(), luigi).andExpect(status().isForbidden());
    }

    @Test
    void unaTappaNonConclusa_risponde409ConIlMessaggioENonPubblicaNulla() throws Exception {
        TappaDTO tappa = tappaDto("Tappa di Roma", false);
        lega(mario, "Circuito 2026", tappa);

        pubblica(tappa.id(), mario).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("concludila")));

        assertThat(archivio.count()).isZero();
    }

    @Test
    void senzaToken_risponde401ENonPubblicaNulla() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        mvc.perform(put("/api/archivio/" + tappaId)).andExpect(status().isUnauthorized());

        assertThat(archivio.count()).isZero();
    }

    /* ── Dati di prova ── */

    /** Tappa di Roma con id nuovo: i blocchi di gioco sono quelli di SQUADRE e PARTITE */
    private TappaDTO tappaDto(String nome, boolean conclusa) {
        return tappaDto(UUID.randomUUID(), nome, conclusa);
    }

    private TappaDTO tappaDto(UUID id, String nome, boolean conclusa) {
        return new TappaDTO(id, nome, "Roma", "2026-06-14", 1, new RegoleDTO(21, 10, 2, 12),
                mapper.readTree(SQUADRE), null, mapper.readTree(PARTITE), mapper.readTree("[]"), conclusa, null);
    }

    /** Crea la lega di `proprietario` con le tappe indicate, come fa l'import di una lega da file */
    private UUID lega(Utente proprietario, String nome, TappaDTO... tappe) {
        return legaService.crea(proprietario, new NuovaLegaDTO(nome, List.of(tappe))).id();
    }

    /** Una lega di `proprietario` con una sola tappa conclusa: restituisce l'id della tappa */
    private UUID tappaConclusa(Utente proprietario, String nomeLega) {
        TappaDTO tappa = tappaDto("Tappa di Roma", true);
        lega(proprietario, nomeLega, tappa);
        return tappa.id();
    }

    /* ── Richieste ── */

    private String bearer(Utente utente) {
        return "Bearer " + jwt.generateToken(utente);
    }

    /** «Pubblica» premuto da `chi`: nessun corpo */
    private ResultActions pubblica(UUID tappaId, Utente chi) throws Exception {
        return mvc.perform(put("/api/archivio/" + tappaId).header(AUTHORIZATION, bearer(chi)));
    }

    /** Ciò che chiunque legge dall'archivio: la GET è pubblica, senza token */
    private JsonNode letta(UUID tappaId) throws Exception {
        return mapper.readTree(corpo(mvc.perform(get("/api/archivio/" + tappaId)).andExpect(status().isOk())));
    }

    /** Corpo della risposta in UTF-8 (MockMvc, senza charset nell'header, leggerebbe ISO-8859-1) */
    private static String corpo(ResultActions esito) throws Exception {
        return esito.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
