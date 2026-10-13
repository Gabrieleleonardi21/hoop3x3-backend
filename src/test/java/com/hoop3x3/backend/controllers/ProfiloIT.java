package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.OrologioDiProva;
import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.EmailFinto;
import com.hoop3x3.backend.services.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il profilo dell'account con il database vero e la catena vera (JWT, limiti di frequenza): il nome cambia subito ed è quello
 * che l'anagrafe mostra come autore, la password cambia solo con quella attuale giusta e chiude le altre sessioni. Il limite
 * dei tentativi è quello di produzione (10 al minuto) con un orologio che si sposta a comando: ogni test parte da un minuto
 * nuovo. Nessuna email vera: EmailSender è quello finto (EmailFinto).
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
@Import({EmailFinto.Configurazione.class, OrologioDiProva.Configurazione.class})
@TestPropertySource(properties = "limite.auth-al-minuto=10")
class ProfiloIT {

    private static final String PASSWORD = "/api/auth/me/password";
    private static final String VECCHIA = "vecchia-password";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UtenteRepository utenti;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtTools jwt;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired AnagrafeService anagrafeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailFinto email;
    @Autowired OrologioDiProva orologio;

    private Utente mario;

    @BeforeEach
    void creaMario() {
        orologio.giornoNuovo();
        email.svuota();
        mario = utenti.save(new Utente("mario@test.it", encoder.encode(VECCHIA), "Mario", Ruolo.USER));
    }

    /* ── Nome ── */

    // I DTO dell'anagrafe leggono il nome dell'autore dal database: dopo la PATCH le schede di Mario portano il nome nuovo
    @Test
    void cambiareIlNome_loAggiornaSubito_eCompareComeAutoreNellAnagrafe() throws Exception {
        anagrafeService.creaGiocatore(mario, new GiocatoreRequestDTO("Luca", "Neri", null, null, null, null, null, null, null,
                null, null, null, null, null));

        mvc.perform(patch("/api/auth/me").header(AUTHORIZATION, bearer(mario)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "  Mario Rossi "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mario Rossi"))
                .andExpect(jsonPath("$.email").value("mario@test.it"))
                .andExpect(jsonPath("$.password").doesNotExist());

        assertThat(jdbc.queryForObject("select nome from utenti where id = ?", String.class, mario.getId())).isEqualTo("Mario Rossi");
        mvc.perform(get("/api/anagrafe/giocatori").header(AUTHORIZATION, bearer(mario)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].autore").value("Mario Rossi"));
    }

    /* ── Password ── */

    @Test
    void cambiareLaPassword_conQuellaAttualeSbagliata_e400_eLHashNonCambia() throws Exception {
        String prima = hashDiMario();

        cambiaPassword(mario, "sbagliata", "nuova-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La password attuale non è corretta"));

        assertThat(hashDiMario()).isEqualTo(prima);
        login(VECCHIA).andExpect(status().isOk());
    }

    @Test
    void laNuovaPasswordUgualeAllAttuale_oAdmin123_eRifiutata() throws Exception {
        String prima = hashDiMario();

        cambiaPassword(mario, VECCHIA, VECCHIA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La nuova password è uguale a quella attuale"));
        cambiaPassword(mario, VECCHIA, "admin123").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Questa password non è consentita: scegline un'altra"));

        assertThat(hashDiMario()).isEqualTo(prima);
    }

    // Le sessioni aperte prima (telefono e computer) non si rinnovano più; il browser che ha cambiato la password riceve un
    // cookie nuovo e resta dentro
    @Test
    void cambiareLaPassword_chiudeLeAltreSessioni_eRinnovaQuestaConUnCookieNuovo() throws Exception {
        String telefono = refreshTokenService.emetti(mario);
        String computer = refreshTokenService.emetti(mario);

        MockHttpServletResponse risposta = cambiaPassword(mario, VECCHIA, "nuova-password").andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.email").value("mario@test.it"))
                .andReturn().getResponse();
        String nuovo = risposta.getCookie(AuthCookies.NOME).getValue();

        assertThat(nuovo).isNotBlank().isNotIn(telefono, computer);
        rinnova(telefono).andExpect(status().isUnauthorized());
        rinnova(computer).andExpect(status().isUnauthorized());
        rinnova(nuovo).andExpect(status().isOk());
        login("nuova-password").andExpect(status().isOk());
        login(VECCHIA).andExpect(status().isUnauthorized());
    }

    // Un JWT rubato non basta a provare password a raffica: oltre 10 tentativi nel minuto, 429 prima del BCrypt
    @Test
    void oltre10TentativiAlMinuto_429() throws Exception {
        for (int i = 0; i < 10; i++) {
            cambiaPassword(mario, "sbagliata-" + i, "nuova-password").andExpect(status().isBadRequest());
        }

        cambiaPassword(mario, VECCHIA, "nuova-password").andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "40"))
                .andExpect(jsonPath("$.message").value("Troppi tentativi con la password: riprova tra 40 secondi"));

        login(VECCHIA).andExpect(status().isOk());
    }

    /* ── Richieste ── */

    private String bearer(Utente utente) {
        return "Bearer " + jwt.generateToken(utente);
    }

    private ResultActions cambiaPassword(Utente utente, String attuale, String nuova) throws Exception {
        return mvc.perform(post(PASSWORD).header(AUTHORIZATION, bearer(utente)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("passwordAttuale", attuale, "nuovaPassword", nuova))));
    }

    private ResultActions rinnova(String cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie(AuthCookies.NOME, cookie)));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", "mario@test.it", "password", password))));
    }

    private String json(Object corpo) {
        return mapper.writeValueAsString(corpo);
    }

    private String hashDiMario() {
        return jdbc.queryForObject("select password from utenti where id = ?", String.class, mario.getId());
    }
}
