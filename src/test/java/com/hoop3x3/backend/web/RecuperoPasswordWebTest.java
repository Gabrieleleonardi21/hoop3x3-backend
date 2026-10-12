package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.RecuperoPasswordController;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.LimiteDimensioneFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.RecuperoPasswordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le due rotte del recupero password nella catena vera (sicurezza, JwtFilter, validazione, ExceptionsHandler) con il servizio
 * simulato: sono pubbliche, un corpo non valido risponde 400 senza arrivare al servizio, la richiesta del link risponde sempre
 * 200 con lo stesso messaggio, e il token non torna mai indietro nella risposta. Il servizio vero lo prova
 * RecuperoPasswordServiceTest, il giro completo con il database RecuperoPasswordIT.
 */
@WebMvcTest(controllers = RecuperoPasswordController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JsonAuthEntryPoint.class, ExceptionsHandler.class,
        LimiteDimensioneFilter.class})
@TestPropertySource(properties = "limite.auth-al-minuto=100000")
class RecuperoPasswordWebTest {

    private static final String DIMENTICATA = "/api/auth/password/dimenticata";
    private static final String REIMPOSTA = "/api/auth/password/reimposta";
    private static final String RISPOSTA_RICHIESTA =
            "{\"message\":\"Se l'indirizzo è registrato, ti abbiamo inviato un link per reimpostare la password.\"}";
    private static final String LINK_NON_VALIDO = "Il link non è valido o è scaduto. Richiedine uno nuovo.";
    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean RecuperoPasswordService service;
    @MockitoBean JwtTools jwtTools;
    @MockitoBean UtenteRepository utenteRepository;

    private ResultActions invia(String url, Object corpo) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)));
    }

    /* ── Richiesta del link ── */

    // Registrata o no, la risposta è la stessa: è il servizio a decidere se mandare l'email, e non lo dice a nessuno
    @ParameterizedTest
    @ValueSource(strings = {"mario@x.it", "nessuno@x.it"})
    void laRichiestaDelLink_risponde200SempreConLoStessoMessaggio(String email) throws Exception {
        invia(DIMENTICATA, Map.of("email", email)).andExpect(status().isOk())
                .andExpect(content().json(RISPOSTA_RICHIESTA, true));

        verify(service).richiedi(email);
    }

    // Un Bearer scaduto rimasto nel client non impedisce il recupero (come per login e registrazione, non passa dal JwtFilter)
    @Test
    void conUnBearerNonValido_laRichiestaPassaLoStesso() throws Exception {
        mvc.perform(post(DIMENTICATA).header("Authorization", "Bearer scaduto").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"mario@x.it\"}")).andExpect(status().isOk());

        verifyNoInteractions(jwtTools);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"\"}", "{\"email\":\"non-una-email\"}", "{\"email\":null}"})
    void laRichiestaConUnEmailNonValida_risponde400ConIlCampoSenzaChiamareIlServizio(String corpo) throws Exception {
        mvc.perform(post(DIMENTICATA).contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("email:")));

        verifyNoInteractions(service);
    }

    @Test
    void unCorpoIllegibile_risponde400() throws Exception {
        mvc.perform(post(DIMENTICATA).contentType(MediaType.APPLICATION_JSON).content("{email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Corpo della richiesta non valido"));

        verifyNoInteractions(service);
    }

    /* ── Nuova password ── */

    @Test
    void reimpostaRiuscita_risponde200ConIlMessaggio() throws Exception {
        invia(REIMPOSTA, Map.of("token", TOKEN, "nuovaPassword", "nuova-password")).andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"Password aggiornata. Accedi con la nuova password.\"}", true));

        verify(service).reimposta(TOKEN, "nuova-password");
    }

    // Scaduto, già usato o sconosciuto: un solo messaggio, e il token non torna nella risposta
    @Test
    void reimpostaConUnLinkNonValido_risponde400ConIlMessaggioUnicoSenzaIlToken() throws Exception {
        doThrow(new BadRequestException(LINK_NON_VALIDO)).when(service).reimposta(any(), any());

        invia(REIMPOSTA, Map.of("token", TOKEN, "nuovaPassword", "nuova-password")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NON_VALIDO))
                .andExpect(content().string(not(containsString(TOKEN))));
    }

    @Test
    void reimpostaSenzaCampi_risponde400NominandoliTutti() throws Exception {
        invia(REIMPOSTA, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", allOf(containsString("token:"), containsString("nuovaPassword:"))));

        verifyNoInteractions(service);
    }

    // Le stesse regole della registrazione: almeno 8 caratteri, al massimo 72 (i byte li controlla il servizio)
    @Test
    void reimpostaConUnaPasswordDi7Caratteri_risponde400ConIlMessaggioDelMinimo() throws Exception {
        invia(REIMPOSTA, Map.of("token", TOKEN, "nuovaPassword", "x".repeat(7))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", allOf(containsString("nuovaPassword:"), containsString("almeno 8 caratteri"),
                        not(containsString(TOKEN)))));

        verifyNoInteractions(service);
    }

    @Test
    void reimpostaConUnaPasswordDi73Caratteri_risponde400ConIlMessaggioDelMassimo() throws Exception {
        invia(REIMPOSTA, Map.of("token", TOKEN, "nuovaPassword", "x".repeat(73))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", allOf(containsString("nuovaPassword:"), containsString("al massimo 72 caratteri"))));

        verifyNoInteractions(service);
    }
}
