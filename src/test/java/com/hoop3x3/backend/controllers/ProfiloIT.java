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
import com.hoop3x3.backend.support.Tempo;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il profilo dell'account con il database vero e la catena vera (JWT, limiti di frequenza): il nome cambia subito ed è quello
 * che l'anagrafe mostra come autore, la password cambia solo con quella attuale giusta e chiude le altre sessioni, l'email
 * cambia solo con il link mandato al nuovo indirizzo, l'account si elimina solo con la password giusta e mai l'ADMIN. Il limite
 * dei tentativi è quello di produzione (10 al minuto) con un orologio che si sposta a comando: ogni test parte da un minuto
 * nuovo. Nessuna email vera: EmailSender è quello finto (EmailFinto).
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
@Import({EmailFinto.Configurazione.class, OrologioDiProva.Configurazione.class})
@TestPropertySource(properties = "limite.auth-al-minuto=10")
class ProfiloIT {

    private static final String PASSWORD = "/api/auth/me/password";
    private static final String EMAIL = "/api/auth/me/email";
    private static final String CONFERMA = "/api/auth/email/conferma";
    private static final String LINK_NON_VALIDO = "Il link non è valido o è scaduto. Richiedine uno nuovo.";
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

    // Chi conosceva la password vecchia ha chiesto il cambio email verso il suo indirizzo: il cambio password della vittima
    // chiude anche quel link, che altrimenti varrebbe ancora 30 minuti con la conferma pubblica
    @Test
    void unLinkDiCambioEmailChiestoPrimaDelCambioPassword_nonValePiu() throws Exception {
        richiediEmail(mario, "attaccante@test.it", VECCHIA).andExpect(status().isOk());
        String link = email.ultimoToken();

        cambiaPassword(mario, VECCHIA, "nuova-password").andExpect(status().isOk());

        conferma(link).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));
        assertThat(emailDiMario()).isEqualTo("mario@test.it");
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

    /* ── Email ── */

    // D3: il link va al nuovo indirizzo e l'account cambia solo alla conferma. Il JWT di prima vale ancora (identifica
    // l'utente per id), e un Bearer non valido rimasto nel browser non ferma la conferma, che non lo legge
    @Test
    void richiedereIlCambioEmail_mandaIlLinkAlNuovoIndirizzo_eLEmailCambiaSoloAllaConferma() throws Exception {
        String jwtDiPrima = bearer(mario);

        richiediEmail(mario, "Mario.Nuovo@Test.it", VECCHIA).andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Ti abbiamo inviato un link al nuovo indirizzo: aprilo entro 30 minuti per confermare il cambio."));

        assertThat(utenti.findByEmail("mario.nuovo@test.it")).isEmpty();
        assertThat(email.inviate()).singleElement().satisfies(inviata -> {
            assertThat(inviata.destinatario()).isEqualTo("mario.nuovo@test.it");
            assertThat(inviata.testo()).contains("http://localhost:5173/conferma-email?token=" + email.ultimoToken());
        });
        // Nel database l'email nuova e lo SHA-256 del token, mai il token
        assertThat(jdbc.queryForList("select email_nuova || ' ' || token_hash from token_cambio_email", String.class))
                .containsExactly("mario.nuovo@test.it " + sha256(email.ultimoToken()));
        login("mario@test.it", VECCHIA).andExpect(status().isOk());

        mvc.perform(post(CONFERMA).header(AUTHORIZATION, "Bearer non-valido").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", email.ultimoToken()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Email aggiornata: da ora accedi con il nuovo indirizzo."));

        login("mario.nuovo@test.it", VECCHIA).andExpect(status().isOk());
        login("mario@test.it", VECCHIA).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(AUTHORIZATION, jwtDiPrima)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("mario.nuovo@test.it"));
    }

    @Test
    void laConfermaConUnTokenGiaUsato_scaduto_oInventato_eRifiutataConLoStessoMessaggio() throws Exception {
        richiediEmail(mario, "a@test.it", VECCHIA).andExpect(status().isOk());
        String usato = email.ultimoToken();
        conferma(usato).andExpect(status().isOk());

        conferma(usato).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));

        richiediEmail(mario, "b@test.it", VECCHIA).andExpect(status().isOk());
        jdbc.update("update token_cambio_email set scadenza = ? where usato_il is null", Tempo.adesso().minusMinutes(1));
        conferma(email.ultimoToken()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));

        conferma("token-inventato").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));
        assertThat(emailDiMario()).isEqualTo("a@test.it");
    }

    @Test
    void unLinkNuovo_invalidaQuelloPrecedente() throws Exception {
        richiediEmail(mario, "prima@test.it", VECCHIA).andExpect(status().isOk());
        String primo = email.ultimoToken();
        richiediEmail(mario, "seconda@test.it", VECCHIA).andExpect(status().isOk());

        conferma(primo).andExpect(status().isBadRequest());
        conferma(email.ultimoToken()).andExpect(status().isOk());

        assertThat(emailDiMario()).isEqualTo("seconda@test.it");
    }

    // Tra la richiesta e la conferma un altro si registra con quell'indirizzo: 409, l'email resta, e il link non vale più
    @Test
    void seIlNuovoIndirizzoVieneRegistratoPrimaDellaConferma_409_eLEmailResta() throws Exception {
        richiediEmail(mario, "conteso@test.it", VECCHIA).andExpect(status().isOk());
        String token = email.ultimoToken();
        utenti.save(new Utente("conteso@test.it", "hash", "Luigi", Ruolo.USER));

        conferma(token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email conteso@test.it già registrata"));

        assertThat(emailDiMario()).isEqualTo("mario@test.it");
        assertThat(jdbc.queryForObject("select count(*) from token_cambio_email where usato_il is null", Integer.class)).isZero();
        conferma(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));
    }

    @Test
    void unEmailGiaRegistrata_oUgualeAllAttuale_eRifiutataAllaRichiesta_eNonParteNessunaEmail() throws Exception {
        utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));

        richiediEmail(mario, "Luigi@test.it", VECCHIA).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email luigi@test.it già registrata"));
        richiediEmail(mario, "MARIO@test.it", VECCHIA).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("È già l'email del tuo account"));
        richiediEmail(mario, "nuova@test.it", "sbagliata").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La password attuale non è corretta"));

        assertThat(email.inviate()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from token_cambio_email", Integer.class)).isZero();
    }

    @Test
    void allaQuartaRichiestaIn15Minuti_429() throws Exception {
        for (int i = 0; i < 3; i++) {
            richiediEmail(mario, "nuova" + i + "@test.it", VECCHIA).andExpect(status().isOk());
        }

        richiediEmail(mario, "nuova3@test.it", VECCHIA).andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
                .andExpect(jsonPath("$.message").value("Hai già richiesto 3 link in 15 minuti: riprova tra 15 minuti"));

        assertThat(email.inviate()).hasSize(3);
    }

    // D6: l'ADMIN nasce da ADMIN_EMAIL, e il seeder ne creerebbe un altro
    @Test
    void lAdmin_nonCambiaEmail_403() throws Exception {
        Utente admin = utenti.save(new Utente("admin@test.it", encoder.encode(VECCHIA), "Admin", Ruolo.ADMIN));

        richiediEmail(admin, "altro@test.it", VECCHIA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("L'account ADMIN si gestisce dalla configurazione del server (ADMIN_EMAIL)"));

        assertThat(email.inviate()).isEmpty();
    }

    @Test
    void eliminareLUtente_eliminaISuoiTokenDiCambioEmail() throws Exception {
        richiediEmail(mario, "nuova@test.it", VECCHIA).andExpect(status().isOk());
        Utente luigi = utenti.save(new Utente("luigi@test.it", encoder.encode(VECCHIA), "Luigi", Ruolo.USER));
        richiediEmail(luigi, "luigi.nuovo@test.it", VECCHIA).andExpect(status().isOk());

        jdbc.update("delete from utenti where id = ?", mario.getId());

        List<String> rimasti = jdbc.queryForList("select utente_id::text from token_cambio_email", String.class);
        assertThat(rimasti).containsExactly(luigi.getId().toString());
    }

    /* ── Eliminazione ── */

    // Dopo il 204 l'account non c'è più: il cookie si cancella, il login non riesce e il JWT di prima non vale più
    @Test
    void eliminareLAccount_conLaPasswordGiusta_204_cancellaIlCookie_eIlLoginNonFunzionaPiu() throws Exception {
        String jwtDiPrima = bearer(mario);

        elimina(mario, VECCHIA).andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString(AuthCookies.NOME + "="), containsString("Max-Age=0"), containsString("Path=/api/auth"))));

        assertThat(utenti.findById(mario.getId())).isEmpty();
        login(VECCHIA).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(AUTHORIZATION, jwtDiPrima)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("L'utente associato al token non esiste più"));
    }

    @Test
    void eliminareLAccount_conLaPasswordSbagliata_400_eNienteCambia() throws Exception {
        String sessione = refreshTokenService.emetti(mario);

        elimina(mario, "sbagliata").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La password attuale non è corretta"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(utenti.findById(mario.getId())).isPresent();
        rinnova(sessione).andExpect(status().isOk());
    }

    // D6: l'ADMIN nasce da ADMIN_EMAIL; senza la sua riga il seeder lo ricreerebbe e il seed demo resterebbe senza proprietario
    @Test
    void lAdmin_nonSiElimina_403() throws Exception {
        Utente admin = utenti.save(new Utente("admin@test.it", encoder.encode(VECCHIA), "Admin", Ruolo.ADMIN));

        elimina(admin, VECCHIA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("L'account ADMIN si gestisce dalla configurazione del server (ADMIN_EMAIL)"));

        assertThat(utenti.findById(admin.getId())).isPresent();
    }

    /* ── Richieste ── */

    private ResultActions elimina(Utente utente, String password) throws Exception {
        return mvc.perform(post("/api/auth/me/elimina").header(AUTHORIZATION, bearer(utente))
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("password", password))));
    }

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
        return login("mario@test.it", password);
    }

    private ResultActions login(String indirizzo, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", indirizzo, "password", password))));
    }

    private ResultActions richiediEmail(Utente utente, String nuovaEmail, String password) throws Exception {
        return mvc.perform(post(EMAIL).header(AUTHORIZATION, bearer(utente)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nuovaEmail", nuovaEmail, "password", password))));
    }

    /** La conferma come la manda la pagina /conferma-email: senza Bearer */
    private ResultActions conferma(String token) throws Exception {
        return mvc.perform(post(CONFERMA).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("token", token))));
    }

    private String json(Object corpo) {
        return mapper.writeValueAsString(corpo);
    }

    private String emailDiMario() {
        return jdbc.queryForObject("select email from utenti where id = ?", String.class, mario.getId());
    }

    private static String sha256(String testo) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(testo.getBytes(StandardCharsets.UTF_8)));
    }

    private String hashDiMario() {
        return jdbc.queryForObject("select password from utenti where id = ?", String.class, mario.getId());
    }
}
