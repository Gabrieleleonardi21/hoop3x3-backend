package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.TestDiIntegrazione;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.services.EmailFinto;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.support.Tempo;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Il recupero password con il database vero e la catena vera, con un EmailSender finto al posto di Gmail (EmailFinto): il
 * link arriva, il token nel database è solo un hash, vale una volta e fino alla scadenza, la password cambia davvero, le
 * sessioni aperte si chiudono, e la tabella segue l'utente quando viene eliminato.
 */
@TestDiIntegrazione
@AutoConfigureMockMvc
@Import(EmailFinto.Configurazione.class)
class RecuperoPasswordIT {

    private static final String DIMENTICATA = "/api/auth/password/dimenticata";
    private static final String REIMPOSTA = "/api/auth/password/reimposta";
    private static final String LINK_NON_VALIDO = "Il link non è valido o è scaduto. Richiedine uno nuovo.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EmailFinto email;
    @Autowired UtenteRepository utenti;
    @Autowired PasswordEncoder encoder;
    @Autowired RefreshTokenService refreshTokenService;
    @Autowired JdbcTemplate jdbc;

    private Utente mario;

    @BeforeEach
    void creaMario() {
        email.svuota();
        mario = utenti.save(new Utente("mario@test.it", encoder.encode("vecchia-password"), "Mario", Ruolo.USER));
    }

    /* ── Il giro completo ── */

    @Test
    void giroCompleto_lEmailPortaIlLink_laPasswordCambia_eLeSessioniAperteSiChiudono() throws Exception {
        // Due sessioni aperte, per esempio telefono e computer
        String telefono = refreshTokenService.emetti(mario);
        refreshTokenService.emetti(mario);

        chiediIlLink("Mario@Test.it").andExpect(status().isOk());

        assertThat(email.inviate()).singleElement().satisfies(inviata -> {
            assertThat(inviata.destinatario()).isEqualTo("mario@test.it");
            assertThat(inviata.testo()).contains("http://localhost:5173/reimposta-password?token=" + email.ultimoToken());
        });
        String token = email.ultimoToken();
        // Nel database solo lo SHA-256 del token, mai il token
        assertThat(jdbc.queryForList("select token_hash from token_recupero_password", String.class))
                .containsExactly(sha256(token));

        reimposta(token, "nuova-password").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password aggiornata. Accedi con la nuova password."));

        login("nuova-password").andExpect(status().isOk());
        login("vecchia-password").andExpect(status().isUnauthorized());
        // Le sessioni di prima non si rinnovano più (il login appena fatto ne ha aperta una nuova)
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie(AuthCookies.NOME, telefono)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from token_recupero_password where usato_il is null", Integer.class))
                .isZero();
    }

    // Registrata o no, la risposta è la stessa parola per parola; solo per quella registrata parte un'email
    @Test
    void laRispostaPerUnEmailRegistrataEPerUnaCheNonLoE_eIdentica() throws Exception {
        String registrata = chiediIlLink("mario@test.it").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String sconosciuta = chiediIlLink("nessuno@test.it").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sconosciuta).isEqualTo(registrata)
                .isEqualTo("{\"message\":\"Se l'indirizzo è registrato, ti abbiamo inviato un link per reimpostare la password.\"}");
        assertThat(email.inviate()).extracting(EmailFinto.Email::destinatario).containsExactly("mario@test.it");
    }

    /* ── Il token vale una volta, fino alla scadenza, e solo l'ultimo ── */

    @Test
    void loStessoTokenUsatoDueVolte_laSecondaERifiutata() throws Exception {
        chiediIlLink("mario@test.it");
        String token = email.ultimoToken();
        reimposta(token, "nuova-password").andExpect(status().isOk());

        reimposta(token, "altra-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));

        login("nuova-password").andExpect(status().isOk());
    }

    @Test
    void unTokenScadutoNelDatabase_eRifiutato() throws Exception {
        chiediIlLink("mario@test.it");
        jdbc.update("update token_recupero_password set scadenza = ?", Tempo.adesso().minusMinutes(1));

        reimposta(email.ultimoToken(), "nuova-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));

        login("vecchia-password").andExpect(status().isOk());
    }

    @Test
    void unLinkNuovo_invalidaQuelloPrecedente() throws Exception {
        chiediIlLink("mario@test.it");
        String primo = email.ultimoToken();
        chiediIlLink("mario@test.it");

        reimposta(primo, "nuova-password").andExpect(status().isBadRequest());
        reimposta(email.ultimoToken(), "nuova-password").andExpect(status().isOk());
    }

    @Test
    void unTokenInventato_eRifiutatoConLoStessoMessaggio() throws Exception {
        reimposta("token-inventato", "nuova-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(LINK_NON_VALIDO));
    }

    /* ── Limite per email: 3 link ogni 15 minuti, contati nel database ── */

    @Test
    void allaQuartaRichiestaIn15Minuti_laRispostaEUguale_maLEmailNonParte() throws Exception {
        for (int i = 0; i < 4; i++) {
            chiediIlLink("mario@test.it").andExpect(status().isOk());
        }

        assertThat(email.inviate()).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from token_recupero_password", Integer.class)).isEqualTo(3);
    }

    /* ── Schema ── */

    @Test
    void eliminareLUtente_eliminaISuoiToken() throws Exception {
        chiediIlLink("mario@test.it");
        Utente luigi = utenti.save(new Utente("luigi@test.it", "hash", "Luigi", Ruolo.USER));
        chiediIlLink("luigi@test.it");

        jdbc.update("delete from utenti where id = ?", mario.getId());

        List<String> rimasti = jdbc.queryForList("select utente_id::text from token_recupero_password", String.class);
        assertThat(rimasti).containsExactly(luigi.getId().toString());
    }

    /* ── Richieste ── */

    private ResultActions chiediIlLink(String indirizzo) throws Exception {
        return invia(DIMENTICATA, Map.of("email", indirizzo));
    }

    private ResultActions reimposta(String token, String nuovaPassword) throws Exception {
        return invia(REIMPOSTA, Map.of("token", token, "nuovaPassword", nuovaPassword));
    }

    private ResultActions login(String password) throws Exception {
        return invia("/api/auth/login", Map.of("email", "mario@test.it", "password", password));
    }

    private ResultActions invia(String url, Object corpo) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo)));
    }

    private static String sha256(String testo) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(testo.getBytes(StandardCharsets.UTF_8)));
    }
}
