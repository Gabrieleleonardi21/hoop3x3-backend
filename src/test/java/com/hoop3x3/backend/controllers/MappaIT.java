package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.services.MappaCampiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La mappa dei campetti con la catena di sicurezza vera e il profilo di test, che non ha la chiave MAPS_API_KEY: senza chiave
 * la mappa risponde 503 con il messaggio, senza token, e i parametri fuori intervallo si rifiutano prima della chiave
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
class MappaIT {

    private static final String MAPPA = "/api/mappa-campetti";

    @Autowired MockMvc mvc;

    @Test
    void senzaChiave_503ConIlMessaggioSenzaToken() throws Exception {
        mvc.perform(get(MAPPA).param("lat", "41.9028").param("lng", "12.4964").param("zoom", "12"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(MappaCampiService.MESSAGGIO_CHIAVE_ASSENTE));
    }

    @Test
    void parametroFuoriIntervallo_400ancheSenzaChiave() throws Exception {
        mvc.perform(get(MAPPA).param("lat", "41.9028").param("lng", "12.4964").param("zoom", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("zoom")));
    }
}
