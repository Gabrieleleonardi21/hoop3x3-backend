package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.AnagrafeController;
import com.hoop3x3.backend.controllers.LegaController;
import com.hoop3x3.backend.controllers.TappaController;
import com.hoop3x3.backend.controllers.UtenteController;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.UtenteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Errori e autenticazione a livello web: ogni risposta di errore ha il corpo {message, timestamp}. */
@WebMvcTest(controllers = {LegaController.class, TappaController.class, AnagrafeController.class, UtenteController.class})
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JWTtools.class, JsonAuthEntryPoint.class, ExceptionsHandler.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
class ErroriWebTest {

    @Autowired MockMvc mvc;
    @Autowired JWTtools jwt;
    @MockitoBean LegaService legaService;
    @MockitoBean AnagrafeService anagrafeService;
    @MockitoBean UtenteService utenteService;
    @MockitoBean UtenteRepository utenteRepository;

    String bearer;
    UUID utenteId;

    @BeforeEach
    void utenteAutenticato() {
        Utente u = new Utente("mario@x.it", "hash", "Mario", Ruolo.USER);
        utenteId = UUID.randomUUID();
        ReflectionTestUtils.setField(u, "id", utenteId);
        when(utenteRepository.findById(utenteId)).thenReturn(Optional.of(u));
        bearer = "Bearer " + jwt.generateToken(u);
    }

    @Test
    void erroreImprevistoConTokenValido_risponde500ConCorpoStandard() throws Exception {
        when(legaService.indice(any())).thenThrow(new IllegalStateException("dettaglio interno [insert into tappe ...]"));

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Errore interno del server: riprova più tardi"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(not(containsString("insert into"))));
    }

    @Test
    void vincoloDelDatabaseViolato_risponde409SenzaDettagliSql() throws Exception {
        when(legaService.indice(any())).thenThrow(new DataIntegrityViolationException("could not execute statement [value too long for type character varying(120)]"));

        mvc.perform(get("/api/leghe").header("Authorization", bearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Operazione in conflitto con i dati già salvati"))
                .andExpect(content().string(not(containsString("varying"))));
    }

    @Test
    void tokenAlterato_risponde401() throws Exception {
        mvc.perform(get("/api/leghe").header("Authorization", "Bearer abc.def.ghi"))
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
}
