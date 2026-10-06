package com.hoop3x3.backend;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JWTtools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controllo di salute per Render (healthCheckPath): /actuator/health è pubblico e, con il database raggiungibile,
 * risponde 200 con status UP e senza dettagli interni. Gli altri endpoint dell'actuator non sono esposti.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class SaluteIT {

    @Autowired MockMvc mvc;
    @Autowired UtenteRepository utenti;
    @Autowired JWTtools jwt;

    @Test
    void healthSenzaTokenRispondeUpSenzaDettagli() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // niente dettagli di database e disco (show-details=never); restano solo i nomi dei gruppi liveness/readiness
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    // Anche un admin autenticato non vede gli altri endpoint (env, beans, ...): esporli rivelerebbe la configurazione
    @Test
    void gliAltriEndpointDellActuatorNonSonoEsposti() throws Exception {
        Utente admin = utenti.save(new Utente("salute@test.it", "hash", "Salute", Ruolo.ADMIN));

        mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + jwt.generateToken(admin)))
                .andExpect(status().isNotFound());
    }

    // Senza token gli altri percorsi dell'actuator restano protetti come il resto dell'API
    @Test
    void gliAltriPercorsiDellActuatorSenzaTokenRispondono401() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
