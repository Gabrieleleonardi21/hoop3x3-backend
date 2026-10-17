package com.hoop3x3.backend;

import com.hoop3x3.backend.entities.Utente;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * I test di integrazione non chiamano mai Groq né Anthropic, anche se nel terminale sono esportate AI_LETTORE_FOGLIO,
 * COACH_FORNITORE, GROQ_API_KEY o ANTHROPIC_API_KEY. Le variabili d'ambiente prevalgono sui file del profilo di test; le proprietà di
 * TestDiIntegrazione prevalgono su di loro. Qui le variabili esportate si simulano con le proprietà di sistema, che stanno
 * sopra le variabili d'ambiente e sotto le proprietà del test: se TestDiIntegrazione non le spegnesse, la lettura sarebbe
 * accesa con Groq.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
// Una proprietà che nessun altro test ha: il contesto è nuovo, creato con le proprietà di sistema qui sotto, e non uno già
// in memoria di un'altra classe
@TestPropertySource(properties = "prova.fornitori-esportati=true")
class FornitoriSpentiIT {

    /** Le variabili come le vede Spring da una shell con le chiavi esportate (AI_LETTORE_FOGLIO=groq, COACH_FORNITORE=anthropic...) */
    private static final Map<String, String> ESPORTATE = Map.of(
            "ai.lettore-foglio", "groq",
            "coach.fornitore", "anthropic",
            "groq.api.key", "gsk_esportata_nel_terminale",
            "anthropic.api.key", "sk-ant-esportata-nel-terminale");

    @Autowired MockMvc mvc;

    private final Utente mario = UtenteDiProva.conId("mario@test.it");

    @BeforeAll
    static void esportaLeChiavi() {
        ESPORTATE.forEach(System::setProperty);
    }

    @AfterAll
    static void togliLeChiavi() {
        ESPORTATE.keySet().forEach(System::clearProperty);
    }

    // Un test solo, con lo stato prima della foto: se la protezione mancasse, il test cadrebbe sullo stato e la foto non
    // partirebbe verso il Groq vero
    @Test
    void conLeChiaviEsportate_ilCoachELaLetturaRestanoSpenti() throws Exception {
        mvc.perform(get("/api/coach/status").with(user(mario))).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.foglio").value(false));

        mvc.perform(post("/api/coach/foglio").with(user(mario)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"immagine\":\"data:image/jpeg;base64,/9j/4AAQ\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Lettura dei fogli non configurata sul server (AI_LETTORE_FOGLIO)"));
    }
}
