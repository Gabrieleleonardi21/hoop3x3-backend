package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.AnagrafeController;
import com.hoop3x3.backend.controllers.LegaController;
import com.hoop3x3.backend.controllers.TappaController;
import com.hoop3x3.backend.controllers.UtenteController;
import com.hoop3x3.backend.LogCatturato;
import com.hoop3x3.backend.TappaDiProva;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.UtenteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Errori e autenticazione a livello web: ogni risposta di errore ha il corpo {message, timestamp}. */
@WebMvcTest(controllers = {LegaController.class, TappaController.class, AnagrafeController.class, UtenteController.class})
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JwtTools.class, JsonAuthEntryPoint.class, ExceptionsHandler.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
@ExtendWith(OutputCaptureExtension.class) // serve a controllare che gli errori 500 lascino la riga ERROR nei log
class ErroriWebTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTools jwt;
    @Autowired ObjectMapper mapper;
    @MockitoBean LegaService legaService;
    @MockitoBean AnagrafeService anagrafeService;
    @MockitoBean UtenteService utenteService;
    @MockitoBean UtenteRepository utenteRepository;

    String bearer;
    UUID utenteId;
    // CapturedOutput accumula l'output di tutta la classe: ogni test legge solo ciò che è uscito dopo il suo inizio, così la
    // riga ERROR di un test non può soddisfare le prove di un altro
    private int inizio;

    @BeforeEach
    void ricordaDoveCominciaLUscita(CapturedOutput output) {
        inizio = output.getAll().length();
    }

    @BeforeEach
    void utenteAutenticato() {
        Utente u = new Utente("mario@x.it", "hash", "Mario", Ruolo.USER);
        utenteId = UUID.randomUUID();
        ReflectionTestUtils.setField(u, "id", utenteId);
        when(utenteRepository.findById(utenteId)).thenReturn(Optional.of(u));
        bearer = "Bearer " + jwt.generateToken(u);
    }

    @Test
    void erroreImprevistoConTokenValido_risponde500ConCorpoStandard(CapturedOutput output) throws Exception {
        when(legaService.indice(any())).thenThrow(new IllegalStateException("dettaglio interno [insert into tappe ...]"));

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Errore interno del server: riprova più tardi"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("insert into"))));

        assertRigaErrorNeiLog(output, "Errore non gestito su GET /api/leghe", "IllegalStateException");
    }

    @Test
    void erroreDiSpringMvcCon5xx_risponde500ConCorpoStandardEScriveLaRigaError(CapturedOutput output) throws Exception {
        // Eccezioni come questa (risposta non scrivibile) le prende la superclasse di ExceptionsHandler, non il gestore
        // generico: il 500 deve lasciare comunque una traccia nei log
        when(legaService.indice(any())).thenThrow(new HttpMessageNotWritableException("x"));

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Errore interno del server: riprova più tardi"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertRigaErrorNeiLog(output, "Errore 500 di Spring MVC su GET /api/leghe", "HttpMessageNotWritableException");
    }

    @Test
    void vincoloDelDatabaseViolato_risponde409SenzaDettagliSql() throws Exception {
        when(legaService.indice(any())).thenThrow(new DataIntegrityViolationException("could not execute statement [value too long for type character varying(120)]"));

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Operazione in conflitto con i dati già salvati"))
                .andExpect(content().string(not(containsString("varying"))));
    }

    // Due dispositivi salvano la stessa tappa: il servizio, o Hibernate al flush, rifiuta la seconda con un errore di versione. Non è
    // un guasto e non dice niente del database: 409, con un messaggio che dice di ricaricare
    @Test
    void conflittoDiVersioneDellaTappa_risponde409ConIlMessaggio() throws Exception {
        UUID tappa = UUID.randomUUID();
        when(legaService.aggiornaTappa(any(), any(), any())).thenThrow(new ObjectOptimisticLockingFailureException(Tappa.class, tappa));

        mvc.perform(put("/api/tappe/" + tappa).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(TappaDiProva.tappa().id(tappa).build())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("La tappa è stata modificata da un altro dispositivo: ricaricala"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("optimistic"))));
    }

    // Hibernate lancia lo stesso errore per ogni UPDATE o DELETE che non trova la riga, anche su una entity senza versione (qui una
    // rinomina arrivata mentre la lega veniva eliminata): è un conflitto come per la tappa, ma il messaggio non può parlare di una tappa
    @Test
    void conflittoSuUnaLega_risponde409ConUnMessaggioGenerico() throws Exception {
        UUID lega = UUID.randomUUID();
        when(legaService.rinomina(any(), any(), any())).thenThrow(new ObjectOptimisticLockingFailureException(Lega.class, lega));

        mvc.perform(patch("/api/leghe/" + lega).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Nuovo nome\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("I dati sono stati modificati o eliminati da un'altra richiesta: ricarica"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("optimistic"))));
    }

    @Test
    void tokenAlterato_risponde401() throws Exception {
        mvc.perform(get("/api/leghe").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sessione scaduta o token non valido: accedi di nuovo"));
    }

    // «Authorization: Bearer » senza token, o con soli spazi: JJWT lancia IllegalArgumentException e non una JwtException,
    // e la richiesta finiva in un 500 invece che in un 401
    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer    "})
    void bearerSenzaToken_risponde401(String header) throws Exception {
        mvc.perform(get("/api/leghe").header("Authorization", header))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sessione scaduta o token non valido: accedi di nuovo"));
    }

    @Test
    void tokenDiUtenteEliminato_risponde401() throws Exception {
        when(utenteRepository.findById(utenteId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("L'utente associato al token non esiste più"));
    }

    @Test
    void databaseNonRaggiungibileNelFiltro_risponde500ConCorpoStandard(CapturedOutput output) throws Exception {
        // Il filtro legge l'utente dal database prima del controller: l'errore nasce nel filtro, fuori da Spring MVC,
        // e deve comunque passare dal gestore generico (corpo {message, timestamp} e riga ERROR nei log, con il percorso)
        when(utenteRepository.findById(utenteId)).thenThrow(new DataAccessResourceFailureException("database non raggiungibile"));

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Errore interno del server: riprova più tardi"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertRigaErrorNeiLog(output, "Errore non gestito su GET /api/leghe", "DataAccessResourceFailureException");
    }

    @Test
    void senzaTokenSuEndpointProtetto_risponde401() throws Exception {
        mvc.perform(get("/api/leghe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void ruoloInsufficiente_risponde403() throws Exception {
        mvc.perform(get("/api/utenti").header("Authorization", bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Non hai i permessi necessari per questa operazione"));
    }

    @Test
    void idNonUuidNelPercorso_risponde400ConCorpoStandard() throws Exception {
        mvc.perform(get("/api/leghe/non-un-uuid").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Richiesta non valida"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void metodoNonConsentito_risponde405ConCorpoStandard() throws Exception {
        mvc.perform(patch("/api/tappe/" + UUID.randomUUID()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message").value("Metodo non consentito per questo indirizzo"));
    }

    @Test
    void percorsoInesistente_risponde404ConCorpoStandard() throws Exception {
        mvc.perform(get("/api/non-esiste").header("Authorization", bearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Risorsa non trovata"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void corpoNonJson_risponde415ConCorpoStandard() throws Exception {
        mvc.perform(post("/api/leghe").header("Authorization", bearer)
                        .contentType(MediaType.TEXT_PLAIN).content("nome=Roma"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Formato della richiesta non supportato"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void corpoNonValido_risponde400ConIlCampo() throws Exception {
        mvc.perform(post("/api/leghe").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("nome")));
    }

    @Test
    void jsonMalformato_risponde400() throws Exception {
        mvc.perform(post("/api/leghe").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{nome:"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Corpo della richiesta non valido"));
    }

    /* ── Un client che non accetta JSON riceve comunque l'errore nel formato di sempre ── */

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void tokenAlterato_conAcceptNonJson_risponde401InJson(String accept) throws Exception {
        erroreInJson(get("/api/leghe").header("Authorization", "Bearer abc.def.ghi"), accept, 401);
    }

    // Questo lo scrive JsonAuthEntryPoint per conto suo, non ExceptionsHandler: deve fissare anche lui il tipo
    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void senzaToken_conAcceptNonJson_risponde401InJson(String accept) throws Exception {
        erroreInJson(get("/api/leghe"), accept, 401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void ruoloInsufficiente_conAcceptNonJson_risponde403InJson(String accept) throws Exception {
        erroreInJson(get("/api/utenti").header("Authorization", bearer), accept, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void erroreDelServizio_conAcceptNonJson_risponde404InJson(String accept) throws Exception {
        when(legaService.indice(any())).thenThrow(new NotFoundException("Lega non trovata"));

        erroreInJson(get("/api/leghe").header("Authorization", bearer), accept, 404);
    }

    // Gli errori di Spring MVC passano da handleExceptionInternal e dagli altri metodi che ExceptionsHandler ridefinisce
    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void percorsoInesistente_conAcceptNonJson_risponde404InJson(String accept) throws Exception {
        erroreInJson(get("/api/non-esiste").header("Authorization", bearer), accept, 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void jsonMalformato_conAcceptNonJson_risponde400InJson(String accept) throws Exception {
        erroreInJson(post("/api/leghe").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{nome:"),
                accept, 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void corpoNonValido_conAcceptNonJson_risponde400InJson(String accept) throws Exception {
        erroreInJson(post("/api/leghe").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"),
                accept, 400);
    }

    /**
     * La richiesta con quell'Accept riceve lo stato atteso e il corpo {message, timestamp} in JSON, e Spring non scrive niente
     * nei log. Senza il Content-Type fissato nella risposta Spring sceglieva il tipo in base ad Accept e per text/html o
     * application/xml non trovava un convertitore: il corpo restava vuoto e ogni errore lasciava un WARN con più di cento
     * righe di stack.
     */
    private void erroreInJson(MockHttpServletRequestBuilder richiesta, String accept, int stato) throws Exception {
        try (LogCatturato springWeb = new LogCatturato("org.springframework.web")) {
            mvc.perform(richiesta.accept(MediaType.parseMediaType(accept)))
                    .andExpect(status().is(stato))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.timestamp").exists());

            assertThat(springWeb.righe()).as("righe di log di Spring").isEmpty();
        }
    }

    /**
     * Nei log del test c'è una riga ERROR scritta da ExceptionsHandler con quel messaggio (che nomina metodo e percorso
     * della richiesta) e, sotto, lo stack con il nome dell'eccezione
     */
    private void assertRigaErrorNeiLog(CapturedOutput output, String messaggio, String eccezione) {
        String uscita = output.getAll().substring(inizio);
        assertThat(uscita).containsPattern("(?m)^.*ERROR.*ExceptionsHandler +: " + Pattern.quote(messaggio) + "$").contains(eccezione);
    }
}
