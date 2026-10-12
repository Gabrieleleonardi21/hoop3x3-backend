package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.LuoghiController;
import com.hoop3x3.backend.dto.LuogoDTO;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.LuoghiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo strato web della ricerca di un luogo con il servizio simulato: la validazione di q (400 prima del servizio), il trovato
 * (200 con i campi), il non trovato (404), e il GET pubblico senza token.
 */
@WebMvcTest(controllers = LuoghiController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JwtTools.class, JsonAuthEntryPoint.class, ExceptionsHandler.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
class LuoghiWebTest {

    private static final String LUOGHI = "/api/luoghi";

    @Autowired MockMvc mvc;
    @MockitoBean LuoghiService luoghi;
    @MockitoBean UtenteRepository utenteRepository;

    @Test
    void senzaQ_400ConIlMessaggioItaliano() throws Exception {
        mvc.perform(get(LUOGHI)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Indica il luogo da cercare")));
        verifyNoInteractions(luoghi);
    }

    // Vuoto, solo spazi, un carattere o centouno caratteri sono fuori intervallo: 400 con il nome del campo, il servizio non parte
    @ParameterizedTest
    @MethodSource("qFuoriIntervallo")
    void qFuoriIntervallo_400(String q) throws Exception {
        mvc.perform(get(LUOGHI).param("q", q)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("da 2 a 100 caratteri")));
        verifyNoInteractions(luoghi);
    }

    static Stream<String> qFuoriIntervallo() {
        return Stream.of("", "   ", "a", "x".repeat(101));
    }

    @Test
    void qDiUnSoloCarattereDopoIlTrim_400() throws Exception {
        mvc.perform(get(LUOGHI).param("q", "  a  ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("da 2 a 100 caratteri")));
        verifyNoInteractions(luoghi);
    }

    // Il q arriva al servizio già ripulito dagli spazi ai bordi, e la risposta ha i suoi tre campi
    @Test
    void trovato_200ConNomeELatLng_senzaToken() throws Exception {
        when(luoghi.cerca("firenze")).thenReturn(new LuogoDTO("Firenze", 43.771, 11.2486));

        mvc.perform(get(LUOGHI).param("q", "  firenze  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Firenze"))
                .andExpect(jsonPath("$.lat").value(43.771))
                .andExpect(jsonPath("$.lng").value(11.2486));
    }

    @Test
    void nonTrovato_404ConIlMessaggio() throws Exception {
        when(luoghi.cerca(anyString())).thenThrow(new NotFoundException("Luogo non trovato"));

        mvc.perform(get(LUOGHI).param("q", "zzzzqq"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Luogo non trovato"));
        verify(luoghi).cerca("zzzzqq");
    }
}
