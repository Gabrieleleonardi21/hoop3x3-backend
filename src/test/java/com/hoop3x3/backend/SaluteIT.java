package com.hoop3x3.backend;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.JwtTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * I controlli di salute: /actuator/health (pubblico, con il database: lo aspetta il frontend all'avvio) e
 * /actuator/health/liveness (pubblico, solo il processo: è quello per Render, perché un database giù non deve far riavviare
 * il backend). Entrambi rispondono 200 con status UP e senza dettagli interni. Gli altri endpoint dell'actuator non sono esposti.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class SaluteIT {

    @Autowired MockMvc mvc;
    @Autowired UtenteRepository utenti;
    @Autowired JwtTools jwt;
    @Autowired HealthEndpointGroups gruppi;

    @Test
    void healthSenzaTokenRispondeUpSenzaDettagli() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // niente dettagli di database e disco (show-details=never); restano solo i nomi dei gruppi liveness/readiness
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    // Render riavvia il servizio quando il controllo di salute smette di rispondere: con il database dentro, un database giù
    // faceva riavviare un backend sano (e ogni avvio a freddo dura minuti). Il gruppo liveness non ha il database e risponde
    // UP finché il processo è vivo; /actuator/health resta com'è, con il database, perché il frontend lo aspetta all'avvio
    @Test
    void livenessSenzaTokenRispondeUp_eNonContieneIlDatabase() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(content().string(not(containsString("db"))));

        // La prova che non dipende dal database sta nella definizione del gruppo, non nel corpo (che non ha dettagli)
        assertThat(gruppi.get("liveness").isMember("db")).as("db nel gruppo liveness").isFalse();
        assertThat(gruppi.getPrimary().isMember("db")).as("db in /actuator/health").isTrue();
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
