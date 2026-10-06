package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.AuthController;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.RefreshTokenService;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le righe di log che servono a diagnosticare un problema in produzione, lette dall'output di Spring Boot: cosa scrive
 * l'applicazione quando un login fallisce, quando qualcuno si registra e quando un ADMIN interviene sui dati di un altro.
 * Strato web con i servizi veri e i repository simulati: la riga esce dallo stesso codice che gira in produzione.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JsonAuthEntryPoint.class, AuthCookies.class,
        ExceptionsHandler.class, UtenteService.class})
@ExtendWith(OutputCaptureExtension.class)
class LogApplicativiTest {

    // Una password che non può comparire per caso in nessun'altra riga dell'output
    private static final String PASSWORD = "Segreta-da-non-scrivere-nei-log-42";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean JWTtools jwtTools;
    @MockitoBean UtenteRepository utenteRepository;

    // CapturedOutput accumula l'output di tutta la classe (avvio del contesto e test precedenti compresi): ogni test legge
    // solo ciò che è uscito dopo il suo inizio, altrimenti la riga di un test finirebbe nelle prove di un altro
    private int inizio;

    @BeforeEach
    void ricordaDoveCominciaLUscita(CapturedOutput output) {
        inizio = output.getAll().length();
    }

    // Login e registrazione che riescono: i test cambiano solo ciò che vogliono mettere alla prova
    @BeforeEach
    void accessoRiuscito() {
        Utente mario = utente("mario@test.it", Ruolo.USER);
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(mario, null, mario.getAuthorities()));
        when(refreshTokenService.emetti(any())).thenReturn("refresh");
        when(jwtTools.generateToken(any())).thenReturn("jwt");
    }

    /* ── Login ── */

    // L'email nel log è quella normalizzata, la stessa con cui si cerca l'utente; la password mai, nemmeno per errore
    @Test
    void loginFallito_lasciaUnWarnConLEmailENonConLaPassword(CapturedOutput output) throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("email", "Mario@Test.IT", "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email o password non corretti"));

        assertThat(messaggi(output, "WARN", "AuthController")).containsExactly("Login fallito per mario@test.it");
        assertThat(uscita(output)).doesNotContain(PASSWORD);
    }

    @Test
    void loginRiuscito_nonLasciaNessunWarn(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("email", "mario@test.it", "password", PASSWORD))))
                .andExpect(status().isOk());

        assertThat(messaggi(output, "WARN", "AuthController")).isEmpty();
        assertThat(uscita(output)).doesNotContain(PASSWORD);
    }

    // Righe false nei log: l'email la scrive chi fa il login. Con un CR o un LF dentro, una riga di log potrebbe
    // diventarne due, e la seconda sarebbe inventata da chi manda la richiesta. La validazione (@Email) rifiuta questi
    // valori con un 400 prima che il controller scriva qualcosa: se un giorno non lo facesse più, questo test cade
    @ParameterizedTest
    @ValueSource(strings = {
            "mario@test.it\r\nINFO riga inventata",   // la riga falsa dopo un a capo
            "mario@test.it\r\n",                      // a capo in coda
            "\r\nmario@test.it",                      // a capo in testa
            "mario\r\n@test.it",                      // dentro la parte prima della chiocciola
            "mario@test\r\n.it",                      // dentro il dominio
            "\"mario\\\nrossi\"@test.it"              // tra virgolette, con la barra rovescia davanti al LF
    })
    void emailConCrLfNelLogin_laValidazioneLaFermaPrimaDelLog(String email, CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("email:")));

        verifyNoInteractions(authenticationManager);
        assertThat(messaggi(output, "WARN", "AuthController")).isEmpty();
        assertThat(uscita(output)).doesNotContain("riga inventata").doesNotContain(PASSWORD);
    }

    /* ── Registrazione ── */

    @Test
    void registrazione_lasciaUnInfoConLEmailEL_IdENonConLaPassword(CapturedOutput output) throws Exception {
        UUID id = UUID.randomUUID();
        when(utenteRepository.save(any(Utente.class))).thenAnswer(chiamata -> {
            Utente salvato = chiamata.getArgument(0);
            ReflectionTestUtils.setField(salvato, "id", id); // il database lo assegnerebbe al salvataggio
            return salvato;
        });

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("name", "Mario", "email", "Mario@Test.IT", "password", PASSWORD))))
                .andExpect(status().isCreated());

        assertThat(messaggi(output, "INFO", "AuthController"))
                .containsExactly("Nuovo utente registrato: mario@test.it (id " + id + ")");
        assertThat(uscita(output)).doesNotContain(PASSWORD);
    }

    // Una registrazione respinta (email già presa) non ha creato nessun utente: niente riga che dica il contrario
    @Test
    void registrazioneRifiutata_nonLasciaLaRigaDiRegistrazione(CapturedOutput output) throws Exception {
        when(utenteRepository.existsByEmail("mario@test.it")).thenReturn(true);

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("name", "Mario", "email", "mario@test.it", "password", PASSWORD))))
                .andExpect(status().isConflict());

        assertThat(messaggi(output, "INFO", "AuthController")).isEmpty();
    }

    // Come per il login: l'email della registrazione finisce nel log, e la validazione ferma i CR e LF prima
    @ParameterizedTest
    @ValueSource(strings = {"mario@test.it\r\nINFO riga inventata", "ma\r\nrio@test.it"})
    void emailConCrLfNellaRegistrazione_laValidazioneLaFermaPrimaDelLog(String email, CapturedOutput output) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(Map.of("name", "Mario", "email", email, "password", PASSWORD))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(utenteRepository);
        assertThat(messaggi(output, "INFO", "AuthController")).isEmpty();
        assertThat(uscita(output)).doesNotContain("riga inventata").doesNotContain(PASSWORD);
    }

    /* ── Aiuti ── */

    private String corpo(Map<String, Object> campi) {
        return mapper.writeValueAsString(campi);
    }

    /** Un utente con l'id che il database gli darebbe al salvataggio (il campo non ha un setter) */
    private static Utente utente(String email, Ruolo ruolo) {
        Utente utente = new Utente(email, "hash", "Nome", ruolo);
        ReflectionTestUtils.setField(utente, "id", UUID.randomUUID());
        return utente;
    }

    /** Ciò che è uscito dall'inizio del test in corso */
    private String uscita(CapturedOutput output) {
        return output.getAll().substring(inizio);
    }

    /**
     * I messaggi delle righe di log di quel livello scritte da quella classe nel test in corso, senza il prefisso di
     * Spring Boot (data, thread, nome del logger abbreviato): chi controlla vede solo ciò che l'applicazione ha scritto.
     */
    private List<String> messaggi(CapturedOutput output, String livello, String classe) {
        Matcher riga = Pattern.compile("(?m)^\\S+ +" + livello + " .*" + classe + " +: (.*)$").matcher(uscita(output));
        List<String> trovati = new ArrayList<>();
        while (riga.find()) {
            trovati.add(riga.group(1));
        }
        return trovati;
    }
}
