package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.MappaController;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.exceptions.UpstreamException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.MappaCampiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo strato web della mappa dei campetti con il servizio simulato: i parametri (400 con il nome di quello che non va, prima di
 * toccare il servizio), l'immagine con i suoi header senza nessun token, e il 503 del servizio portato al client.
 */
@WebMvcTest(controllers = MappaController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JwtTools.class, JsonAuthEntryPoint.class, ExceptionsHandler.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
class MappaWebTest {

    private static final String MAPPA = "/api/mappa-campetti";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @Autowired MockMvc mvc;
    @MockitoBean MappaCampiService mappa;
    @MockitoBean UtenteRepository utenteRepository;

    @Test
    void senzaParametri_400ConIlMessaggioItaliano() throws Exception {
        mvc.perform(get(MAPPA)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Indica lat, lng e zoom")));
        verifyNoInteractions(mappa);
    }

    // Ogni parametro fuori dal suo intervallo risponde 400 con il suo nome, e il servizio (che chiamerebbe Google) non parte
    @ParameterizedTest
    @MethodSource("parametriFuoriIntervallo")
    void parametroFuoriIntervallo_400ConIlNomeDelParametro(String query, String messaggio) throws Exception {
        mvc.perform(get(MAPPA + "?" + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString(messaggio)));
        verify(mappa, never()).immagine(anyDouble(), anyDouble(), anyInt());
    }

    static Stream<Arguments> parametriFuoriIntervallo() {
        return Stream.of(
                arguments("lat=85.06&lng=12.5&zoom=12", "lat: deve essere tra -85.0511 e 85.0511"),
                arguments("lat=-85.06&lng=12.5&zoom=12", "lat: deve essere tra -85.0511 e 85.0511"),
                arguments("lat=NaN&lng=12.5&zoom=12", "lat: deve essere tra -85.0511 e 85.0511"),
                arguments("lat=41.9&lng=180.5&zoom=12", "lng: deve essere tra -180 e 180"),
                arguments("lat=41.9&lng=12.5&zoom=0", "zoom: deve essere un numero intero tra 1 e 18"),
                arguments("lat=41.9&lng=12.5&zoom=19", "zoom: deve essere un numero intero tra 1 e 18"),
                arguments("lat=41.9&lng=12.5&zoom=dodici", "Richiesta non valida"));
    }

    @Test
    void parametroMancante_400() throws Exception {
        mvc.perform(get(MAPPA + "?lat=41.9&lng=12.5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Indica lat, lng e zoom")));
        verifyNoInteractions(mappa);
    }

    // Senza nessun token l'immagine arriva con il tipo PNG e la cache di un giorno, e il client non sceglie la dimensione
    @Test
    void immagine_senzaToken_conGliHeaderGiusti() throws Exception {
        when(mappa.immagine(41.9028, 12.4964, 12)).thenReturn(PNG);

        mvc.perform(get(MAPPA).param("lat", "41.9028").param("lng", "12.4964").param("zoom", "12"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"))
                .andExpect(content().bytes(PNG));
    }

    // Il 503 del servizio (chiave assente) arriva al client così com'è, con il messaggio
    @Test
    void servizioNonDisponibile_503ConIlMessaggio() throws Exception {
        when(mappa.immagine(41.9028, 12.4964, 12)).thenThrow(new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE,
                MappaCampiService.MESSAGGIO_CHIAVE_ASSENTE));

        mvc.perform(get(MAPPA).param("lat", "41.9028").param("lng", "12.4964").param("zoom", "12"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(MappaCampiService.MESSAGGIO_CHIAVE_ASSENTE));
    }
}
