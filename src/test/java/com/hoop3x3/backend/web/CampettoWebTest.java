package com.hoop3x3.backend.web;

import com.hoop3x3.backend.UtenteDiProva;
import com.hoop3x3.backend.controllers.CampettoController;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.CampettoService;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo strato web dei campetti con il servizio simulato: i parametri della GET pubblica (i due modi, per raggio e per testo,
 * e i loro 400) e la validazione del corpo di POST e PUT (coordinate fuori intervallo, superficie e stato non ammessi:
 * 400 con il nome del campo). I tetti dei campi di testo li prova ValidazioneWebTest con la tabella CampiDiTesto.
 */
@WebMvcTest(controllers = CampettoController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JwtTools.class, JsonAuthEntryPoint.class, ExceptionsHandler.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
class CampettoWebTest {

    private static final String CAMPETTI = "/api/campetti";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CampettoService campettoService;
    @MockitoBean UtenteRepository utenteRepository;

    private final Utente mario = UtenteDiProva.conId("mario@test.it");

    /* ── GET pubblica: per raggio ── */

    @Test
    void getPerRaggio_senzaToken_arrivaAlServizioConIlPunto() throws Exception {
        mvc.perform(get(CAMPETTI).param("lat", "45.07").param("lng", "7.68").param("raggioKm", "20"))
                .andExpect(status().isOk());

        verify(campettoService).cercaPerRaggio(45.07, 7.68, 20);
    }

    // Senza nessun parametro non c'è niente da cercare: 400, con il messaggio del controller (non il 401 della sicurezza)
    @Test
    void getSenzaParametri_risponde400() throws Exception {
        rifiutata(get(CAMPETTI), startsWith("Indica lat, lng e raggioKm"));
    }

    static Stream<Arguments> parametriNonValidi() {
        return Stream.of(
                arguments("lat fuori intervallo", Map.of("lat", "91", "lng", "7.68", "raggioKm", "20"), "lat:"),
                arguments("lat non è un numero (NaN)", Map.of("lat", "NaN", "lng", "7.68", "raggioKm", "20"), "lat:"),
                arguments("lng fuori intervallo", Map.of("lat", "45.07", "lng", "-181", "raggioKm", "20"), "lng:"),
                arguments("raggioKm zero", Map.of("lat", "45.07", "lng", "7.68", "raggioKm", "0"), "raggioKm:"),
                arguments("raggioKm negativo", Map.of("lat", "45.07", "lng", "7.68", "raggioKm", "-5"), "raggioKm:"),
                arguments("raggioKm oltre 500", Map.of("lat", "45.07", "lng", "7.68", "raggioKm", "500.5"), "raggioKm:"),
                arguments("raggioKm senza il punto", Map.of("raggioKm", "20"), "raggioKm:"),
                arguments("lat senza lng", Map.of("lat", "45.07", "raggioKm", "20"), "lat e lng"),
                arguments("q di soli spazi", Map.of("q", "   "), "Indica lat, lng e raggioKm"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parametriNonValidi")
    void getConParametriNonValidi_risponde400ConIlNomeDelParametro(String caso, Map<String, String> parametri, String messaggio)
            throws Exception {
        var richiesta = get(CAMPETTI);
        parametri.forEach(richiesta::param);

        rifiutata(richiesta, containsString(messaggio));
    }

    // Un parametro che non è un numero lo ferma Spring MVC prima del controller, con il 400 generico
    @Test
    void getConRaggioKmNonNumerico_risponde400() throws Exception {
        rifiutata(get(CAMPETTI).param("lat", "45.07").param("lng", "7.68").param("raggioKm", "venti"), containsString("Richiesta non valida"));
    }

    @Test
    void getAlLimiteDelRaggio_siAccetta() throws Exception {
        mvc.perform(get(CAMPETTI).param("lat", "-90").param("lng", "180").param("raggioKm", "500")).andExpect(status().isOk());

        verify(campettoService).cercaPerRaggio(-90, 180, 500);
    }

    /* ── GET pubblica: per testo ── */

    @Test
    void getPerTesto_arrivaAlServizioSenzaPunto() throws Exception {
        mvc.perform(get(CAMPETTI).param("q", " dora ")).andExpect(status().isOk());

        verify(campettoService).cercaPerTesto("dora", null, null);
    }

    @Test
    void getPerTestoConUnPunto_arrivaAlServizioConIlPunto() throws Exception {
        mvc.perform(get(CAMPETTI).param("q", "dora").param("lat", "45.07").param("lng", "7.68")).andExpect(status().isOk());

        verify(campettoService).cercaPerTesto("dora", 45.07, 7.68);
    }

    // Con raggioKm vince la ricerca per raggio: q si ignora
    @Test
    void getConRaggioEQ_cercaPerRaggio() throws Exception {
        mvc.perform(get(CAMPETTI).param("q", "dora").param("lat", "45.07").param("lng", "7.68").param("raggioKm", "20"))
                .andExpect(status().isOk());

        verify(campettoService).cercaPerRaggio(45.07, 7.68, 20);
        verify(campettoService, org.mockito.Mockito.never()).cercaPerTesto(any(), any(), any());
    }

    /* ── Il corpo di POST e PUT ── */

    static Stream<Arguments> corpiNonValidi() {
        return Stream.of(
                arguments("lat", 90.001), arguments("lat", -91),
                arguments("lng", 180.5), arguments("lng", -181),
                arguments("superficie", "Erba"), arguments("superficie", "asfalto"), arguments("superficie", ""),
                arguments("stato", "ottimo"), arguments("stato", "Buono"), arguments("stato", ""),
                arguments("canestri", 0), arguments("canestri", 9),
                arguments("nome", " "));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("corpiNonValidi")
    void postConUnCampoNonValido_risponde400ConIlNomeDelCampo(String campo, Object valore) throws Exception {
        Map<String, Object> corpo = campetto();
        corpo.put(campo, valore);

        rifiutata(post(CAMPETTI).with(user(mario)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)),
                containsString(campo + ":"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("corpiNonValidi")
    void putConUnCampoNonValido_risponde400ConIlNomeDelCampo(String campo, Object valore) throws Exception {
        Map<String, Object> corpo = campetto();
        corpo.put(campo, valore);

        rifiutata(put(CAMPETTI + "/" + UUID.randomUUID()).with(user(mario)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(corpo)), containsString(campo + ":"));
    }

    // Una coordinata che non è un numero non costruisce il DTO: il 400 è quello del corpo illeggibile, come per nGironi della tappa
    @Test
    void postConLatNonNumerica_risponde400ComeCorpoNonLeggibile() throws Exception {
        Map<String, Object> corpo = campetto();
        corpo.put("lat", "nord");

        rifiutata(post(CAMPETTI).with(user(mario)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)),
                containsString("Corpo della richiesta non valido"));
    }

    // Un campo numerico che manca è un 400 con il suo nome, non il «corpo non valido» di Jackson: il frontend sa quale campo
    @ParameterizedTest
    @ValueSource(strings = {"lat", "lng", "canestri", "superficie", "stato", "nome"})
    void postSenzaUnCampoObbligatorio_risponde400ConIlNomeDelCampo(String campo) throws Exception {
        Map<String, Object> corpo = campetto();
        corpo.remove(campo);

        rifiutata(post(CAMPETTI).with(user(mario)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)),
                containsString(campo + ":"));
    }

    // tipo, autore, id e ts nel corpo si ignorano: il DTO di richiesta non li ha, e Jackson non si ferma sui campi sconosciuti
    @Test
    void postConTipoEAutoreNelCorpo_liIgnora_eRisponde201() throws Exception {
        Map<String, Object> corpo = campetto();
        corpo.put("tipo", "arena");
        corpo.put("autore", "Qualcun altro");
        corpo.put("autoreId", UUID.randomUUID());
        corpo.put("id", UUID.randomUUID());

        mvc.perform(post(CAMPETTI).with(user(mario)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)))
                .andExpect(status().isCreated());

        verify(campettoService).crea(eq(mario), any(CampettoRequestDTO.class));
    }

    // I valori ammessi al limite passano: coordinate agli estremi, 8 canestri, booleani assenti
    @Test
    void postAiLimiti_siAccetta() throws Exception {
        Map<String, Object> corpo = campetto();
        corpo.put("lat", -90);
        corpo.put("lng", 180);
        corpo.put("canestri", 8);
        corpo.put("stato", "da sistemare");
        corpo.put("superficie", "Altro");
        corpo.remove("illuminato");

        mvc.perform(post(CAMPETTI).with(user(mario)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)))
                .andExpect(status().isCreated());
    }

    @Test
    void postSenzaToken_risponde401ENonToccaIlServizio() throws Exception {
        mvc.perform(post(CAMPETTI).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(campetto())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(campettoService);
    }

    /* ── Aiuti ── */

    /** Un corpo valido: i test cambiano solo il campo che vogliono mettere alla prova */
    private static Map<String, Object> campetto() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("nome", "Parco Dora — Le Arcate");
        c.put("citta", "Torino");
        c.put("lat", 45.08972);
        c.put("lng", 7.66669);
        c.put("superficie", "Sintetico");
        c.put("canestri", 4);
        c.put("illuminato", true);
        c.put("stato", "buono");
        return c;
    }

    /** 400 con il solo corpo {message, timestamp}, il messaggio che soddisfa `messaggio`, e il servizio mai chiamato */
    private void rifiutata(MockHttpServletRequestBuilder richiesta, Matcher<String> messaggio) throws Exception {
        mvc.perform(richiesta).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.message", messaggio))
                .andExpect(jsonPath("$.timestamp").exists());
        verifyNoInteractions(campettoService);
    }
}
