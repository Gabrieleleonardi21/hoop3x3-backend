package com.hoop3x3.backend.web;

import com.hoop3x3.backend.UtenteDiProva;
import com.hoop3x3.backend.controllers.AnagrafeController;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.AccessGuard;
import com.hoop3x3.backend.services.AnagrafeService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La forma pubblica dell'anagrafe (TR-4): chi chiama GET /api/anagrafe senza un token valido riceve la stessa forma JSON
 * di sempre, ma con i dati personali vuoti (le stringhe riservate «», l'id dell'autore null); chi ha un account riceve tutto.
 * Il servizio è quello vero, con i repository simulati: si prova il percorso dalla richiesta al JSON, e con un token
 * scaduto o non valido la risposta resta il 401 che il JwtFilter dà già su tutte le rotte (non diventa la forma pubblica).
 */
@WebMvcTest(controllers = AnagrafeController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JWTtools.class, JsonAuthEntryPoint.class,
        ExceptionsHandler.class, AnagrafeService.class})
@TestPropertySource(properties = {"jwt.secret=" + AnagrafePubblicaWebTest.SEGRETO, "cors.origins=http://localhost:5173"})
class AnagrafePubblicaWebTest {

    static final String SEGRETO = "0123456789abcdef0123456789abcdef";
    private static final String GIOCATORI = "/api/anagrafe/giocatori";
    private static final String SQUADRE = "/api/anagrafe/squadre";
    private static final String TOKEN_NON_VALIDO = "Sessione scaduta o token non valido: accedi di nuovo";

    @Autowired MockMvc mvc;
    @Autowired JWTtools jwt;
    @MockitoBean AnagrafeGiocatoreRepository giocatori;
    @MockitoBean AnagrafeSquadraRepository squadre;
    @MockitoBean AccessGuard guard;
    @MockitoBean UtenteRepository utenteRepository;

    private final UUID idGiocatore = UUID.randomUUID();
    private final UUID idSquadra = UUID.randomUUID();
    private final Utente autore = UtenteDiProva.conId("autore@test.it");
    /** Un utente qualsiasi che non è l'autore delle schede: per «token valido» basta un account */
    private final Utente altro = UtenteDiProva.conId("altro@test.it");

    @BeforeEach
    void anagrafeConUnaSchedaPerTipo() {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        ReflectionTestUtils.setField(g, "id", idGiocatore);
        g.setNome("Mario");
        g.setCognome("Rossi");
        g.setSoprannome("Rosso");
        g.setNascita("1998-03-15");
        g.setCitta("Roma");
        g.setNazionalita("ITA");
        g.setAltezza("192");
        g.setPeso("88");
        g.setRuolo("Guardia");
        g.setNumero("7");
        g.setSquadra("Roma 3x3");
        g.setEsperienza("5 anni");
        g.setNote("Infortunio al ginocchio nel 2024");
        g.setAutore(autore);
        g.setModificatoIl(LocalDateTime.now());
        when(giocatori.findAllByOrderByModificatoIlDesc()).thenReturn(List.of(g));

        AnagrafeSquadra s = new AnagrafeSquadra();
        ReflectionTestUtils.setField(s, "id", idSquadra);
        s.setNome("Roma 3x3");
        s.setCitta("Roma");
        s.setAnno("2020");
        s.setRank("220");
        s.setReferente("Luigi Bianchi");
        s.setLogo("/logos/roma.svg");
        s.setWebsite("https://roma3x3.example");
        s.setInstagram("https://instagram.example/roma3x3");
        s.setNote("Circuito Elite");
        s.getRoster().add(g);
        s.setAutore(autore);
        s.setModificatoIl(LocalDateTime.now());
        when(squadre.findAllByOrderByModificatoIlDesc()).thenReturn(List.of(s));

        // Il JwtFilter ricostruisce l'utente dall'id del token: solo «altro» ha un account (l'autore non serve al filtro)
        when(utenteRepository.findById(altro.getId())).thenReturn(Optional.of(altro));
    }

    /* ── Giocatori ── */

    @Test
    void giocatoriSenzaToken_hannoSoloIDatiPubblici() throws Exception {
        mvc.perform(get(GIOCATORI))
                .andExpect(status().isOk())
                // Pubblici: quelli che servono a riconoscere il giocatore nelle squadre e nelle tappe
                .andExpect(jsonPath("$[0].id").value(idGiocatore.toString()))
                .andExpect(jsonPath("$[0].nome").value("Mario"))
                .andExpect(jsonPath("$[0].cognome").value("Rossi"))
                .andExpect(jsonPath("$[0].soprannome").value("Rosso"))
                .andExpect(jsonPath("$[0].squadra").value("Roma 3x3"))
                .andExpect(jsonPath("$[0].ruolo").value("Guardia"))
                .andExpect(jsonPath("$[0].numero").value("7"))
                .andExpect(jsonPath("$[0].ts").isNumber())
                // Riservati: stringa vuota, e null l'id dell'autore
                .andExpect(jsonPath("$[0].nascita").value(""))
                .andExpect(jsonPath("$[0].citta").value(""))
                .andExpect(jsonPath("$[0].nazionalita").value(""))
                .andExpect(jsonPath("$[0].altezza").value(""))
                .andExpect(jsonPath("$[0].peso").value(""))
                .andExpect(jsonPath("$[0].esperienza").value(""))
                .andExpect(jsonPath("$[0].note").value(""))
                .andExpect(jsonPath("$[0].autore").value(""))
                .andExpect(jsonPath("$[0].autoreId").value(nullValue()))
                // Stessa forma di oggi: le 17 chiavi ci sono tutte, anche quelle vuote
                .andExpect(jsonPath("$[0].length()").value(17))
                .andExpect(jsonPath("$[0]", hasKey("autoreId")));
    }

    @Test
    void giocatoriConTokenValido_hannoTuttiICampi_ancheSeNonSonoLAutore() throws Exception {
        mvc.perform(get(GIOCATORI).header("Authorization", bearerDi(altro)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idGiocatore.toString()))
                .andExpect(jsonPath("$[0].nome").value("Mario"))
                .andExpect(jsonPath("$[0].cognome").value("Rossi"))
                .andExpect(jsonPath("$[0].soprannome").value("Rosso"))
                .andExpect(jsonPath("$[0].nascita").value("1998-03-15"))
                .andExpect(jsonPath("$[0].citta").value("Roma"))
                .andExpect(jsonPath("$[0].nazionalita").value("ITA"))
                .andExpect(jsonPath("$[0].altezza").value("192"))
                .andExpect(jsonPath("$[0].peso").value("88"))
                .andExpect(jsonPath("$[0].ruolo").value("Guardia"))
                .andExpect(jsonPath("$[0].numero").value("7"))
                .andExpect(jsonPath("$[0].squadra").value("Roma 3x3"))
                .andExpect(jsonPath("$[0].esperienza").value("5 anni"))
                .andExpect(jsonPath("$[0].note").value("Infortunio al ginocchio nel 2024"))
                .andExpect(jsonPath("$[0].autore").value("Nome"))
                .andExpect(jsonPath("$[0].autoreId").value(autore.getId().toString()))
                .andExpect(jsonPath("$[0].ts").isNumber())
                .andExpect(jsonPath("$[0].length()").value(17));
    }

    /* ── Squadre ── */

    @Test
    void squadreSenzaToken_nonHannoReferenteNeAutore() throws Exception {
        mvc.perform(get(SQUADRE))
                .andExpect(status().isOk())
                // Pubblici: la scheda della squadra e il roster (gli id dei giocatori)
                .andExpect(jsonPath("$[0].id").value(idSquadra.toString()))
                .andExpect(jsonPath("$[0].nome").value("Roma 3x3"))
                .andExpect(jsonPath("$[0].citta").value("Roma"))
                .andExpect(jsonPath("$[0].anno").value("2020"))
                .andExpect(jsonPath("$[0].rank").value("220"))
                .andExpect(jsonPath("$[0].roster[0]").value(idGiocatore.toString()))
                .andExpect(jsonPath("$[0].logo").value("/logos/roma.svg"))
                .andExpect(jsonPath("$[0].website").value("https://roma3x3.example"))
                .andExpect(jsonPath("$[0].instagram").value("https://instagram.example/roma3x3"))
                .andExpect(jsonPath("$[0].note").value("Circuito Elite"))
                .andExpect(jsonPath("$[0].ts").isNumber())
                // Riservati: il referente e l'autore
                .andExpect(jsonPath("$[0].referente").value(""))
                .andExpect(jsonPath("$[0].autore").value(""))
                .andExpect(jsonPath("$[0].autoreId").value(nullValue()))
                .andExpect(jsonPath("$[0].length()").value(14))
                .andExpect(jsonPath("$[0]", hasKey("autoreId")));
    }

    @Test
    void squadreConTokenValido_hannoTuttiICampi_ancheSeNonSonoLAutore() throws Exception {
        mvc.perform(get(SQUADRE).header("Authorization", bearerDi(altro)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(idSquadra.toString()))
                .andExpect(jsonPath("$[0].nome").value("Roma 3x3"))
                .andExpect(jsonPath("$[0].referente").value("Luigi Bianchi"))
                .andExpect(jsonPath("$[0].roster[0]").value(idGiocatore.toString()))
                .andExpect(jsonPath("$[0].note").value("Circuito Elite"))
                .andExpect(jsonPath("$[0].autore").value("Nome"))
                .andExpect(jsonPath("$[0].autoreId").value(autore.getId().toString()))
                .andExpect(jsonPath("$[0].length()").value(14));
    }

    /* ── Token scaduto o non valido: il 401 del JwtFilter, non la forma pubblica ── */

    // Il JwtFilter risponde 401 a ogni Bearer che non si verifica, anche sulle rotte pubbliche: chi ha un token scaduto
    // non riceve la forma pubblica ma l'invito a rifare il login (è ciò che fa il frontend con il refresh token)
    @Test
    void tokenScaduto_risponde401SuTutteLeLetture() throws Exception {
        rifiutateConIlBearer("Bearer " + tokenFirmato(SEGRETO, altro, -60), TOKEN_NON_VALIDO);
    }

    @Test
    void tokenAlterato_risponde401SuTutteLeLetture() throws Exception {
        // Firmato con un altro segreto: la firma non torna
        rifiutateConIlBearer("Bearer " + tokenFirmato("un-altro-segreto-di-almeno-32-caratteri", altro, 30), TOKEN_NON_VALIDO);
    }

    @Test
    void tokenMalformato_risponde401SuTutteLeLetture() throws Exception {
        rifiutateConIlBearer("Bearer abc.def.ghi", TOKEN_NON_VALIDO);
    }

    @Test
    void tokenDiUnUtenteChePiuNonEsiste_risponde401SuTutteLeLetture() throws Exception {
        rifiutateConIlBearer("Bearer " + tokenFirmato(SEGRETO, UtenteDiProva.conId("cancellato@test.it"), 30),
                "L'utente associato al token non esiste più");
    }

    /* ── Aiuti ── */

    /** Le due letture dell'anagrafe rispondono 401 con quel messaggio, e il servizio non arriva ai repository */
    private void rifiutateConIlBearer(String bearer, String messaggio) throws Exception {
        for (String url : List.of(GIOCATORI, SQUADRE)) {
            mvc.perform(get(url).header("Authorization", bearer))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(messaggio));
        }
        verifyNoInteractions(giocatori, squadre);
    }

    /** Il Bearer di un utente che ha un account, emesso dal JWTtools vero */
    private String bearerDi(Utente utente) {
        return "Bearer " + jwt.generateToken(utente);
    }

    /** Un JWT dell'utente firmato con `segreto`, che scade fra `minuti` (negativi: già scaduto) */
    private static String tokenFirmato(String segreto, Utente utente, int minuti) {
        long adesso = System.currentTimeMillis();
        return Jwts.builder()
                .subject(utente.getId().toString())
                .issuedAt(new Date(adesso - 3_600_000L))
                .expiration(new Date(adesso + minuti * 60_000L))
                .signWith(Keys.hmacShaKeyFor(segreto.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
