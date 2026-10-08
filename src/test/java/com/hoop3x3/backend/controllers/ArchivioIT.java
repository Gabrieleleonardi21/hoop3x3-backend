package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.repositories.ArchivioTappaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.LegaService;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final String TRE_SQUADRE = "[{\"id\":\"s1\",\"nome\":\"Team Rome\"},{\"id\":\"s2\",\"nome\":\"Team Milan\"},"
            + "{\"id\":\"s3\",\"nome\":\"Team Turin\"}]";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTools jwt;
    @Autowired UtenteRepository utenti;
    @Autowired TappaRepository tappe;
    @Autowired ArchivioTappaRepository archivio;
    @Autowired LegaService legaService;
    @Autowired JdbcTemplate jdbc;

    private Utente mario; // proprietario delle leghe di prova
    private Utente luigi; // un altro utente
    private Utente admin;

    @BeforeEach
    void creaGliUtenti() {
        mario = utenti.save(new Utente("mario@test.it", "hash", "Mario", Ruolo.USER));
        luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        admin = utenti.save(new Utente("admin@test.it", "hash", "Admin", Ruolo.ADMIN));
    }

    // Il cuore di BE-7: la richiesta porta un corpo {tappa, lega}, la forma del vecchio endpoint, con nomi e punteggi
    // inventati e perfino un altro id di tappa. Il corpo si ignora (non si legge né si valida): si pubblicano i dati del
    // database, con la chiave del percorso
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
        // Il JSON come lo riceve un client: scritto e riletto. valueToTree darebbe a `versione` un LongNode, che non è uguale
        // all'IntNode che esce dalla lettura di «0», anche se il JSON è lo stesso
        assertThat(pubblico.get("tappa")).isEqualTo(mapper.readTree(mapper.writeValueAsString(salvata)));
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
    // della lega. Una riga già presente la sovrascrive solo il suo autore o un ADMIN: il proprietario della lega, che non ne è
    // l'autore, riceve 403 (è la stessa regola che ferma chi si crea una tappa con l'id di una pubblicazione orfana, vedi
    // sotto). Un ADMIN la ripubblica, e l'autore torna a essere il proprietario
    @Test
    void unaPubblicazioneVecchiaDiUnAltroAutore_ilProprietarioRiceve403_unAdminLaRipubblicaRiportandolaAlProprietario() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");
        pubblicazioneVecchia(tappaId, luigi);

        pubblica(tappaId, mario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("questa pubblicazione")));
        assertThat(letta(tappaId).get("autoreId").asString()).isEqualTo(luigi.getId().toString());

        pubblica(tappaId, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.autoreId").value(mario.getId().toString()))
                .andExpect(jsonPath("$.lega").value("Circuito 2026"));
    }

    /* ── Pubblicazioni orfane: la tappa eliminata prima della V2, la pubblicazione rimasta ── */

    // Gli id delle tappe sono pubblici e li sceglie il client, e la chiave dell'archivio è l'id della tappa. Prima chiunque
    // poteva creare una tappa con l'id di una pubblicazione orfana (visibile nell'elenco pubblico) e, pubblicandola, sostituire
    // il contenuto e l'autore della pubblicazione di un altro con i suoi; e anche senza pubblicare, eliminando poi la tappa (o la
    // lega) la chiave esterna della V2 cancellava la pubblicazione dell'altro. Ora la tappa non si crea nemmeno: 409 sia una alla
    // volta sia nell'import, e la riga resta com'era. L'autore della pubblicazione (e un ADMIN) può invece ricrearla
    @Test
    void unaTappaConLIdDiUnaPubblicazioneOrfanaDiUnAltro_nonSiCrea409ELaRigaResta() throws Exception {
        UUID orfana = UUID.randomUUID();
        ArchivioTappa diMario = pubblicazioneOrfana(orfana, mario);
        UUID legaDiLuigi = lega(luigi, "Lega di Luigi");
        String messaggio = "L'id " + orfana + " è già di una pubblicazione in archivio di un altro utente";

        assertThatThrownBy(() -> legaService.aggiungiTappa(luigi, legaDiLuigi, tappaDto(orfana, "Tappa di Luigi", true)))
                .isInstanceOf(ConflictException.class).hasMessage(messaggio);
        assertThatThrownBy(() -> legaService.crea(luigi, new NuovaLegaDTO("Importata", List.of(tappaDto(orfana, "Importata", true)))))
                .isInstanceOf(ConflictException.class).hasMessage(messaggio);

        assertThat(tappe.existsById(orfana)).isFalse();
        ArchivioTappa dopo = archivio.findById(orfana).orElseThrow();
        assertThat(dopo.getAutore().getId()).isEqualTo(mario.getId());
        assertThat(dopo.getLegaNome()).isEqualTo("Nome vecchio");
        assertThat(mapper.readTree(dopo.getContenuto())).isEqualTo(mapper.readTree(diMario.getContenuto()));
        assertThat(dopo.getPubblicatoIl()).isEqualTo(diMario.getPubblicatoIl());
        assertThat(letta(orfana).at("/tappa/nome").asString()).isEqualTo("Tappa vecchia");

        // Mario, autore della pubblicazione, può ricreare la sua tappa con quell'id (per esempio reimportando la lega da file)
        legaService.aggiungiTappa(mario, lega(mario, "Lega di Mario"), tappaDto(orfana, "Tappa di Mario", true));
        pubblica(orfana, mario).andExpect(status().isOk()).andExpect(jsonPath("$.tappa.nome").value("Tappa di Mario"));
    }

    // Il 409 ferma anche la via indiretta: con la tappa che non esiste, la cancellazione a cascata non ha niente da cancellare
    @Test
    void unAdminPuoCreareLaTappaConLIdDiUnaPubblicazioneOrfanaDiUnAltro() throws Exception {
        UUID orfana = UUID.randomUUID();
        pubblicazioneOrfana(orfana, mario);

        legaService.aggiungiTappa(admin, lega(admin, "Lega dell'admin"), tappaDto(orfana, "Tappa dell'admin", true));

        assertThat(tappe.existsById(orfana)).isTrue();
    }

    @Test
    void ripubblicareAggiornaLoSnapshotESostituisceLaRigaSenzaDuplicarla() throws Exception {
        TappaDTO tappa = tappaDto("Tappa di Roma", true);
        lega(mario, "Circuito 2026", tappa);
        pubblica(tappa.id(), mario).andExpect(status().isOk());
        // La prima pubblicazione risale a ieri: dopo la ripubblicazione la data deve essere quella di adesso
        ArchivioTappa riga = archivio.findById(tappa.id()).orElseThrow();
        riga.setPubblicatoIl(Tempo.adesso().minusDays(1));
        archivio.save(riga);

        // La tappa cambia dopo la prima pubblicazione: la copia pubblica resta com'era finché non si ripubblica
        salvaDiNuovo(tappa, "Tappa di Roma (finale)", true, 0);
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

    // La lettura è pubblica: senza token, una tappa che non è in archivio è un 404 e non un 401
    @Test
    void laLetturaDiUnaPubblicazioneCheNonEsiste_risponde404() throws Exception {
        mvc.perform(get("/api/archivio/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(startsWith("Tappa non presente in archivio")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void laTappaDiUnAltroUtente_risponde403ENonPubblicaNulla() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        pubblica(tappaId, luigi).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("questa tappa")));

        assertThat(archivio.count()).isZero();
    }

    // Il vecchio codice lasciava ripubblicare anche all'autore di una riga già in archivio, che poteva non essere il
    // proprietario della lega (la prima pubblicazione la faceva chiunque). Ora conta solo la proprietà della lega: chi non
    // è il proprietario né ADMIN riceve 403 anche se è l'autore della riga, e la riga resta com'è
    @Test
    void unAutoreDiPubblicazioneVecchiaCheNonEProprietario_risponde403ELaRigaResta() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");
        ArchivioTappa vecchia = pubblicazioneVecchia(tappaId, luigi);

        pubblica(tappaId, luigi).andExpect(status().isForbidden());

        // Stesso autore, stessa lega, stesso contenuto e stessa data di prima
        ArchivioTappa dopo = archivio.findById(tappaId).orElseThrow();
        assertThat(letta(tappaId).get("autoreId").asString()).isEqualTo(luigi.getId().toString());
        assertThat(dopo.getLegaNome()).isEqualTo("Nome vecchio");
        assertThat(mapper.readTree(dopo.getContenuto())).isEqualTo(mapper.readTree(vecchia.getContenuto()));
        assertThat(dopo.getPubblicatoIl()).isEqualTo(vecchia.getPubblicatoIl());
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

    // Una tappa riaperta dopo la pubblicazione (non più conclusa): la copia pubblica resta quella dell'ultima pubblicazione,
    // ripubblicare dà 409 finché la tappa non torna conclusa, e ritirare si può sempre
    @Test
    void unaTappaRiapertaDopoLaPubblicazione_laCopiaResta_ripubblicareDa409_ritirareSiPuo() throws Exception {
        TappaDTO tappa = tappaDto("Tappa di Roma", true);
        lega(mario, "Circuito 2026", tappa);
        pubblica(tappa.id(), mario).andExpect(status().isOk());
        salvaDiNuovo(tappa, "Tappa di Roma (riaperta)", false, 0);

        pubblica(tappa.id(), mario).andExpect(status().isConflict());
        // La copia pubblica è ancora quella dell'ultima pubblicazione
        JsonNode copia = letta(tappa.id());
        assertThat(copia.at("/tappa/nome").asString()).isEqualTo("Tappa di Roma");
        assertThat(copia.at("/tappa/conclusa").asBoolean()).isTrue();
        // Ritirarla si può anche con la tappa riaperta
        mvc.perform(delete("/api/archivio/" + tappa.id()).header(AUTHORIZATION, bearer(mario)))
                .andExpect(status().isNoContent());
        // Quando la tappa torna conclusa si può pubblicare di nuovo
        salvaDiNuovo(tappa, "Tappa di Roma (riaperta)", true, 1);
        pubblica(tappa.id(), mario).andExpect(status().isOk())
                .andExpect(jsonPath("$.tappa.nome").value("Tappa di Roma (riaperta)"));
    }

    // Il vecchio endpoint, con la tappa nel corpo, non esiste più: un client non aggiornato riceve 405 e non pubblica nulla
    @Test
    void ilVecchioEndpointConLaTappaNelCorpo_risponde405() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        mvc.perform(put("/api/archivio").header(AUTHORIZATION, bearer(mario)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tappa\": {\"id\": \"" + tappaId + "\"}, \"lega\": \"Circuito 2026\"}"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(archivio.count()).isZero();
    }

    @Test
    void senzaToken_risponde401ENonPubblicaNulla() throws Exception {
        UUID tappaId = tappaConclusa(mario, "Circuito 2026");

        mvc.perform(put("/api/archivio/" + tappaId)).andExpect(status().isUnauthorized());

        assertThat(archivio.count()).isZero();
    }

    /* ── Elenco: una voce sintetica per pubblicazione, estratta dal JSONB ── */

    // L'elenco mostra di ogni tappa nome, luogo, data, numero di squadre, lega e autore: la voce ha questi campi più l'id per
    // aprirla e ts per l'ordine. Niente contenuto della tappa (lo dà GET /api/archivio/{tappaId}) e niente id dell'autore
    @Test
    void elencoHaUnaVoceSinteticaPerOgniPubblicazione_laPiuRecenteDavanti() throws Exception {
        assertThat(elenco().size()).as("voci dell'archivio vuoto").isZero();
        TappaDTO roma = tappaDto(UUID.randomUUID(), "Tappa di Roma", "Roma", "2026-06-14", SQUADRE, true);
        TappaDTO milano = tappaDto(UUID.randomUUID(), "Finale di Milano", "Milano", "2026-07-05", TRE_SQUADRE, true);
        lega(mario, "Circuito 2026", roma);
        lega(luigi, "Altro circuito", milano);
        pubblica(roma.id(), mario).andExpect(status().isOk());
        pubblica(milano.id(), luigi).andExpect(status().isOk());
        // Date certe: Roma è di ieri, Milano di adesso
        LocalDateTime ieri = Tempo.adesso().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime adesso = Tempo.adesso().truncatedTo(ChronoUnit.SECONDS);
        pubblicataIl(roma.id(), ieri);
        pubblicataIl(milano.id(), adesso);

        assertThat(elenco()).isEqualTo(mapper.readTree("""
                [{"tappaId": "%s", "nome": "Finale di Milano", "luogo": "Milano", "data": "2026-07-05", "nSquadre": 3,
                  "lega": "Altro circuito", "autore": "Luigi", "ts": %d},
                 {"tappaId": "%s", "nome": "Tappa di Roma", "luogo": "Roma", "data": "2026-06-14", "nSquadre": 2,
                  "lega": "Circuito 2026", "autore": "Mario", "ts": %d}]"""
                .formatted(milano.id(), millis(adesso), roma.id(), millis(ieri))));
    }

    // L'elenco legge le colonne scritte alla pubblicazione (V6), non il contenuto JSONB: cambiato il contenuto in SQL, la voce
    // resta quella della pubblicazione, e non c'è più niente da decomprimere a ogni richiesta. Le pubblicazioni di prima della
    // V6, anche con contenuti strani, le ha riempite la migrazione: lo prova MigrazioniIT
    @Test
    void elencoLeggeLeColonneDellaPubblicazione_nonIlContenuto() throws Exception {
        TappaDTO roma = tappaDto(UUID.randomUUID(), "Tappa di Roma", "Roma", "2026-06-14", SQUADRE, true);
        lega(mario, "Circuito 2026", roma);
        pubblica(roma.id(), mario).andExpect(status().isOk());

        jdbc.update("update archivio_tappe set contenuto = '{}'::jsonb where tappa_id = ?", roma.id());

        assertThat(elenco()).singleElement().satisfies(voce -> {
            assertThat(voce.get("nome").asString()).isEqualTo("Tappa di Roma");
            assertThat(voce.get("luogo").asString()).isEqualTo("Roma");
            assertThat(voce.get("data").asString()).isEqualTo("2026-06-14");
            assertThat(voce.get("nSquadre").asInt()).isEqualTo(2);
        });
    }

    // Ripubblicare aggiorna anche le colonne dell'elenco, non solo lo snapshot
    @Test
    void ripubblicareAggiornaLaVoceDellElenco() throws Exception {
        TappaDTO tappa = tappaDto(UUID.randomUUID(), "Tappa di Roma", "Roma", "2026-06-14", SQUADRE, true);
        lega(mario, "Circuito 2026", tappa);
        pubblica(tappa.id(), mario).andExpect(status().isOk());

        legaService.aggiornaTappa(mario, tappa.id(), TappaDiProva.da(tappa).nome("Finale di Milano").luogo("Milano")
                .data("2026-07-05").squadre(TRE_SQUADRE).versione(0L).build());
        pubblica(tappa.id(), mario).andExpect(status().isOk());

        assertThat(elenco()).singleElement().satisfies(voce -> {
            assertThat(voce.get("nome").asString()).isEqualTo("Finale di Milano");
            assertThat(voce.get("luogo").asString()).isEqualTo("Milano");
            assertThat(voce.get("data").asString()).isEqualTo("2026-07-05");
            assertThat(voce.get("nSquadre").asInt()).isEqualTo(3);
        });
    }

    /* ── Pulizia: la pubblicazione segue la sua tappa (V2) ── */

    @Test
    void eliminareLaTappaPubblicataTogliePureLaPubblicazione() throws Exception {
        TappaDTO daEliminare = tappaDto("Da eliminare", true);
        TappaDTO daTenere = tappaDto("Da tenere", true);
        lega(mario, "Circuito 2026", daEliminare, daTenere);
        pubblica(daEliminare.id(), mario).andExpect(status().isOk());
        pubblica(daTenere.id(), mario).andExpect(status().isOk());

        mvc.perform(delete("/api/tappe/" + daEliminare.id()).header(AUTHORIZATION, bearer(mario)))
                .andExpect(status().isNoContent());

        // Resta solo la pubblicazione dell'altra tappa, e quella eliminata non si legge più dall'archivio
        assertThat(tappeInArchivio()).containsExactly(daTenere.id());
        mvc.perform(get("/api/archivio/" + daEliminare.id())).andExpect(status().isNotFound());
    }

    @Test
    void eliminareLaLegaTogliLePubblicazioniDelleSueTappe() throws Exception {
        TappaDTO prima = tappaDto("Prima", true);
        TappaDTO seconda = tappaDto("Seconda", true);
        TappaDTO diUnAltraLega = tappaDto("Di un'altra lega", true);
        UUID legaId = lega(mario, "Circuito 2026", prima, seconda);
        lega(luigi, "Altro circuito", diUnAltraLega);
        pubblica(prima.id(), mario).andExpect(status().isOk());
        pubblica(seconda.id(), mario).andExpect(status().isOk());
        pubblica(diUnAltraLega.id(), luigi).andExpect(status().isOk());

        mvc.perform(delete("/api/leghe/" + legaId).header(AUTHORIZATION, bearer(mario)))
                .andExpect(status().isNoContent());

        // Le pubblicazioni di Mario se ne vanno con la sua lega, quella di Luigi resta
        assertThat(tappeInArchivio()).containsExactly(diUnAltraLega.id());
    }

    /* ── Dati di prova ── */

    /** Tappa di Roma con id nuovo: i blocchi di gioco sono quelli di SQUADRE e PARTITE */
    private TappaDTO tappaDto(String nome, boolean conclusa) {
        return tappaDto(UUID.randomUUID(), nome, conclusa);
    }

    private TappaDTO tappaDto(UUID id, String nome, boolean conclusa) {
        return tappaDto(id, nome, "Roma", "2026-06-14", SQUADRE, conclusa);
    }

    private TappaDTO tappaDto(UUID id, String nome, String luogo, String data, String squadre, boolean conclusa) {
        return TappaDiProva.tappa().id(id).nome(nome).luogo(luogo).data(data).squadre(squadre).partite(PARTITE)
                .conclusa(conclusa).build();
    }

    /**
     * Mario salva di nuovo la tappa (PUT) con un altro nome e un altro stato, rimandando la versione che ha letto: 0 dopo
     * l'import, una in più a ogni salvataggio che cambia la tappa
     */
    private void salvaDiNuovo(TappaDTO tappa, String nome, boolean conclusa, long versione) {
        legaService.aggiornaTappa(mario, tappa.id(), TappaDiProva.da(tappa).nome(nome).conclusa(conclusa).versione(versione).build());
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

    /**
     * Una pubblicazione come le faceva il vecchio endpoint: l'autore è chi l'aveva mandata, non per forza il proprietario,
     * e il contenuto una tappa completa scelta dal client (qui con un altro nome, per riconoscerla)
     */
    private ArchivioTappa pubblicazioneVecchia(UUID tappaId, Utente autore) {
        return pubblicazioneConContenuto(tappaId, autore, mapper.writeValueAsString(tappaDto(tappaId, "Tappa vecchia", true)));
    }

    /**
     * Una pubblicazione orfana, come quelle rimaste nei database già in uso: la sua tappa non esiste. La chiave esterna della
     * V2 (NOT VALID) lascia stare le righe già presenti ma controlla quelle nuove, quindi si toglie per il tempo di scrivere la
     * riga e si rimette com'era (NOT VALID), senza che PostgreSQL controlli le righe esistenti
     */
    private ArchivioTappa pubblicazioneOrfana(UUID tappaId, Utente autore) {
        jdbc.execute("alter table archivio_tappe drop constraint archivio_tappe_tappa_id_fkey");
        try {
            return pubblicazioneVecchia(tappaId, autore);
        } finally {
            jdbc.execute("alter table archivio_tappe add constraint archivio_tappe_tappa_id_fkey "
                    + "foreign key (tappa_id) references tappe(id) on delete cascade not valid");
        }
    }

    /** Una pubblicazione di ieri, con il contenuto JSON scelto dal chiamante (anche uno che il server non costruirebbe mai) */
    private ArchivioTappa pubblicazioneConContenuto(UUID tappaId, Utente autore, String contenuto) {
        ArchivioTappa vecchia = new ArchivioTappa();
        vecchia.setTappaId(tappaId);
        vecchia.setLegaNome("Nome vecchio");
        vecchia.setAutore(autore);
        vecchia.setContenuto(contenuto);
        // Al secondo, senza frazioni: la colonna arrotonda al microsecondo e il confronto con la riga riletta non tornerebbe
        vecchia.setPubblicatoIl(Tempo.adesso().minusDays(1).truncatedTo(ChronoUnit.SECONDS));
        return archivio.save(vecchia);
    }

    /** Cambia la data di pubblicazione di una pubblicazione (al secondo, come sopra) */
    private void pubblicataIl(UUID tappaId, LocalDateTime quando) {
        ArchivioTappa riga = archivio.findById(tappaId).orElseThrow();
        riga.setPubblicatoIl(quando);
        archivio.save(riga);
    }

    /** `ts` dell'API: i millisecondi epoch di una data del database, in UTC, come il server (Tempo) */
    private static long millis(LocalDateTime data) {
        return Tempo.inMillisecondi(data);
    }

    /* ── Richieste ── */

    private String bearer(Utente utente) {
        return "Bearer " + jwt.generateToken(utente);
    }

    /** «Pubblica» premuto da `chi`: nessun corpo */
    private ResultActions pubblica(UUID tappaId, Utente chi) throws Exception {
        return mvc.perform(put("/api/archivio/" + tappaId).header(AUTHORIZATION, bearer(chi)));
    }

    /** Gli id delle tappe che hanno una pubblicazione */
    private List<UUID> tappeInArchivio() {
        return archivio.findAll().stream().map(ArchivioTappa::getTappaId).toList();
    }

    /** L'elenco dell'archivio: la GET è pubblica, senza token */
    private JsonNode elenco() throws Exception {
        return mapper.readTree(corpo(mvc.perform(get("/api/archivio")).andExpect(status().isOk())));
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
