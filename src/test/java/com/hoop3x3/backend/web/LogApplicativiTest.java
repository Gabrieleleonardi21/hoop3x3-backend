package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.AnagrafeController;
import com.hoop3x3.backend.controllers.ArchivioController;
import com.hoop3x3.backend.controllers.AuthController;
import com.hoop3x3.backend.controllers.LegaController;
import com.hoop3x3.backend.controllers.TappaController;
import com.hoop3x3.backend.entities.AnagrafeGiocatore;
import com.hoop3x3.backend.entities.AnagrafeSquadra;
import com.hoop3x3.backend.entities.ArchivioTappa;
import com.hoop3x3.backend.entities.Lega;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Tappa;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.AnagrafeGiocatoreRepository;
import com.hoop3x3.backend.repositories.AnagrafeSquadraRepository;
import com.hoop3x3.backend.repositories.ArchivioTappaRepository;
import com.hoop3x3.backend.repositories.LegaRepository;
import com.hoop3x3.backend.repositories.TappaRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.AccessGuard;
import com.hoop3x3.backend.services.AnagrafeService;
import com.hoop3x3.backend.services.ArchivioService;
import com.hoop3x3.backend.services.JsonSupport;
import com.hoop3x3.backend.services.LegaService;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le righe di log che servono a diagnosticare un problema in produzione, lette dall'output di Spring Boot: cosa scrive
 * l'applicazione quando un login fallisce, quando qualcuno si registra e quando un ADMIN interviene sui dati di un altro.
 * Strato web con i servizi veri e i repository simulati: la riga esce dallo stesso codice che gira in produzione.
 */
@WebMvcTest(controllers = {AuthController.class, AnagrafeController.class, LegaController.class, TappaController.class,
        ArchivioController.class})
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JsonAuthEntryPoint.class, AuthCookies.class,
        ExceptionsHandler.class, UtenteService.class, AnagrafeService.class, LegaService.class, ArchivioService.class,
        AccessGuard.class, JsonSupport.class})
// I login e le registrazioni di questa classe partono tutti dallo stesso indirizzo e sono quasi quanti ne ammette il limite di
// produzione (10 al minuto): un test in più ne farebbe cadere altri con un 429 che non c'entra. Il limite vale quanto nel
// profilo di test
@TestPropertySource(properties = "limite.auth-al-minuto=100000")
@ExtendWith(OutputCaptureExtension.class)
class LogApplicativiTest {

    // Una password che non può comparire per caso in nessun'altra riga dell'output
    private static final String PASSWORD = "Segreta-da-non-scrivere-nei-log-42";
    // Un'email valida per @Email con U+2028, U+2029 e U+0085 dentro, e come deve comparire nei log
    private static final String EMAIL_CON_SEPARATORI_UNICODE = "ma\u2028r\u2029i\u0085o@test.it";
    private static final String EMAIL_CON_SEPARATORI_PER_ESTESO = "ma\\u2028r\\u2029i\\u0085o@test.it";

    // Gli utenti e i dati di prova: id fissi, così le righe di log attese si scrivono per intero
    private static final UUID ID_MARIO = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ID_ADMIN = UUID.fromString("00000000-0000-4000-8000-0000000000ad");
    private static final UUID ID_LUCA = UUID.fromString("00000000-0000-4000-8000-00000000001a");
    private static final UUID ID_GIOCATORE = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID ID_GIOCATORE_DELL_ADMIN = UUID.fromString("00000000-0000-4000-8000-0000000000a5");
    private static final UUID ID_SQUADRA = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID ID_LEGA = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final UUID ID_TAPPA = UUID.fromString("00000000-0000-4000-8000-0000000000a4");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean JWTtools jwtTools;
    @MockitoBean UtenteRepository utenteRepository;
    @MockitoBean AnagrafeGiocatoreRepository giocatori;
    @MockitoBean AnagrafeSquadraRepository squadre;
    @MockitoBean LegaRepository leghe;
    @MockitoBean TappaRepository tappe;
    @MockitoBean ArchivioTappaRepository archivio;

    private final Utente mario = utente(ID_MARIO, "mario@test.it", Ruolo.USER);
    private final Utente luca = utente(ID_LUCA, "luca@test.it", Ruolo.USER);
    private final Utente admin = utente(ID_ADMIN, "admin@test.it", Ruolo.ADMIN);
    private Tappa tappaDiMario;

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
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(mario, null, mario.getAuthorities()));
        when(refreshTokenService.emetti(any())).thenReturn("refresh");
        when(jwtTools.generateToken(any())).thenReturn("jwt");
    }

    // Dati di mario (e un giocatore dell'admin): i repository simulati li restituiscono, il resto è codice vero
    @BeforeEach
    void datiDiProva() {
        AnagrafeGiocatore giocatore = giocatore(ID_GIOCATORE, mario);
        AnagrafeGiocatore giocatoreDellAdmin = giocatore(ID_GIOCATORE_DELL_ADMIN, admin);
        when(giocatori.findById(ID_GIOCATORE)).thenReturn(Optional.of(giocatore));
        when(giocatori.findById(ID_GIOCATORE_DELL_ADMIN)).thenReturn(Optional.of(giocatoreDellAdmin));
        when(giocatori.save(any(AnagrafeGiocatore.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        AnagrafeSquadra squadra = new AnagrafeSquadra();
        ReflectionTestUtils.setField(squadra, "id", ID_SQUADRA);
        squadra.setNome("Roma 3x3");
        squadra.setAutore(mario);
        squadra.setModificatoIl(LocalDateTime.now());
        when(squadre.findById(ID_SQUADRA)).thenReturn(Optional.of(squadra));
        when(squadre.save(any(AnagrafeSquadra.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        // Una lega di mario con una tappa conclusa, e la sua pubblicazione in archivio
        Lega lega = new Lega("Circuito 2026", mario);
        ReflectionTestUtils.setField(lega, "id", ID_LEGA);
        lega.touch();
        tappaDiMario = new Tappa();
        tappaDiMario.setId(ID_TAPPA);
        tappaDiMario.setLega(lega);
        tappaDiMario.setNome("Tappa di Roma");
        tappaDiMario.setConclusa(true);
        lega.getTappe().add(tappaDiMario);
        when(leghe.findById(ID_LEGA)).thenReturn(Optional.of(lega));
        when(leghe.trovaConLock(ID_LEGA)).thenReturn(Optional.of(lega));
        when(leghe.save(any(Lega.class))).thenAnswer(chiamata -> chiamata.getArgument(0));
        when(tappe.findById(ID_TAPPA)).thenReturn(Optional.of(tappaDiMario));
        when(tappe.save(any(Tappa.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        ArchivioTappa pubblicazione = new ArchivioTappa();
        pubblicazione.setTappaId(ID_TAPPA);
        pubblicazione.setLegaNome("Circuito 2026");
        pubblicazione.setAutore(mario);
        pubblicazione.setContenuto("{}");
        pubblicazione.setPubblicatoIl(LocalDateTime.now());
        when(archivio.findById(ID_TAPPA)).thenReturn(Optional.of(pubblicazione));
        when(archivio.save(any(ArchivioTappa.class))).thenAnswer(chiamata -> chiamata.getArgument(0));
    }

    /* ── Login ── */

    // L'email nel log è quella normalizzata, la stessa con cui si cerca l'utente; la password mai, nemmeno per errore
    @Test
    void loginFallito_lasciaUnWarnConLEmailENonConLaPassword(CapturedOutput output) throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        postPubblico("/api/auth/login", Map.of("email", "Mario@Test.IT", "password", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email o password non corretti"));

        assertThat(messaggi(output, "WARN", "AuthController")).containsExactly("Login fallito per mario@test.it");
        assertThat(uscita(output)).doesNotContain(PASSWORD);
    }

    @Test
    void loginRiuscito_nonLasciaNessunWarn(CapturedOutput output) throws Exception {
        postPubblico("/api/auth/login", Map.of("email", "mario@test.it", "password", PASSWORD))
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
        postPubblico("/api/auth/login", Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("email:")));

        verifyNoInteractions(authenticationManager);
        assertThat(messaggi(output, "WARN", "AuthController")).isEmpty();
        assertThat(uscita(output)).doesNotContain("riga inventata").doesNotContain(PASSWORD);
    }

    // Il resto non lo ferma la validazione: @Email lascia passare i separatori di riga e di paragrafo di Unicode (U+2028 e
    // U+2029) e U+0085, un carattere di controllo che alcuni lettori di log trattano da a capo. Arrivano al log, e lì si
    // scrivono per esteso (LogSupport.perLog). Un'email con tutti e tre: ogni carattere da solo lo prova LogSupportTest
    @Test
    void emailConSeparatoriUnicodeNelLogin_nelLogSiScrivePerEsteso(CapturedOutput output) throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        postPubblico("/api/auth/login", Map.of("email", EMAIL_CON_SEPARATORI_UNICODE, "password", PASSWORD))
                .andExpect(status().isUnauthorized());

        assertThat(messaggi(output, "WARN", "AuthController"))
                .containsExactly("Login fallito per " + EMAIL_CON_SEPARATORI_PER_ESTESO);
        assertThat(uscita(output)).doesNotContain("\u2028", "\u2029", "\u0085");
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

        postPubblico("/api/auth/register", Map.of("name", "Mario", "email", "Mario@Test.IT", "password", PASSWORD))
                .andExpect(status().isCreated());

        assertThat(messaggi(output, "INFO", "AuthController"))
                .containsExactly("Nuovo utente registrato: mario@test.it (id " + id + ")");
        assertThat(uscita(output)).doesNotContain(PASSWORD);
    }

    // Una registrazione respinta (email già presa) non ha creato nessun utente: niente riga che dica il contrario
    @Test
    void registrazioneRifiutata_nonLasciaLaRigaDiRegistrazione(CapturedOutput output) throws Exception {
        when(utenteRepository.existsByEmail("mario@test.it")).thenReturn(true);

        postPubblico("/api/auth/register", Map.of("name", "Mario", "email", "mario@test.it", "password", PASSWORD))
                .andExpect(status().isConflict());

        assertThat(messaggi(output, "INFO", "AuthController")).isEmpty();
    }

    // Come per il login: i separatori di riga di Unicode passano la validazione e nel log si scrivono per esteso
    @Test
    void emailConSeparatoriUnicodeNellaRegistrazione_nelLogSiScrivePerEsteso(CapturedOutput output) throws Exception {
        when(utenteRepository.save(any(Utente.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        postPubblico("/api/auth/register", Map.of("name", "Mario", "email", EMAIL_CON_SEPARATORI_UNICODE, "password", PASSWORD))
                .andExpect(status().isCreated());

        assertThat(messaggi(output, "INFO", "AuthController")).singleElement()
                .isEqualTo("Nuovo utente registrato: " + EMAIL_CON_SEPARATORI_PER_ESTESO + " (id null)");
        assertThat(uscita(output)).doesNotContain("\u2028", "\u2029", "\u0085");
    }

    // Come per il login: l'email della registrazione finisce nel log, e la validazione ferma i CR e LF prima
    @ParameterizedTest
    @ValueSource(strings = {"mario@test.it\r\nINFO riga inventata", "ma\r\nrio@test.it"})
    void emailConCrLfNellaRegistrazione_laValidazioneLaFermaPrimaDelLog(String email, CapturedOutput output) throws Exception {
        postPubblico("/api/auth/register", Map.of("name", "Mario", "email", email, "password", PASSWORD))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(utenteRepository);
        assertThat(messaggi(output, "INFO", "AuthController")).isEmpty();
        assertThat(uscita(output)).doesNotContain("riga inventata").doesNotContain(PASSWORD);
    }

    /* ── Interventi dell'ADMIN su dati altrui ── */

    /** Le scritture che un ADMIN può fare sui dati di mario: metodo, indirizzo, corpo, che cosa registra la riga di log */
    static Stream<Arguments> scrittureDiUnAdmin() {
        return Stream.of(
                arguments(PUT, "/api/anagrafe/giocatori/" + ID_GIOCATORE, Map.of("nome", "Luca", "cognome", "Neri"),
                        "modifica", "giocatore", ID_GIOCATORE),
                arguments(DELETE, "/api/anagrafe/giocatori/" + ID_GIOCATORE, null, "eliminazione", "giocatore", ID_GIOCATORE),
                arguments(PUT, "/api/anagrafe/squadre/" + ID_SQUADRA, Map.of("nome", "Roma 3x3"),
                        "modifica", "squadra", ID_SQUADRA),
                arguments(DELETE, "/api/anagrafe/squadre/" + ID_SQUADRA, null, "eliminazione", "squadra", ID_SQUADRA),
                arguments(PATCH, "/api/leghe/" + ID_LEGA, Map.of("nome", "Circuito 2027"), "modifica", "lega", ID_LEGA),
                arguments(DELETE, "/api/leghe/" + ID_LEGA, null, "eliminazione", "lega", ID_LEGA),
                // Una tappa nuova è una modifica della lega che la riceve
                arguments(POST, "/api/leghe/" + ID_LEGA + "/tappe", tappa(UUID.randomUUID()), "modifica", "lega", ID_LEGA),
                arguments(PUT, "/api/tappe/" + ID_TAPPA, tappa(ID_TAPPA), "modifica", "tappa", ID_TAPPA),
                arguments(DELETE, "/api/tappe/" + ID_TAPPA, null, "eliminazione", "tappa", ID_TAPPA),
                // Pubblicare scrive (o riscrive) la copia pubblica di una tappa, intestata al proprietario della lega
                arguments(PUT, "/api/archivio/" + ID_TAPPA, null, "modifica", "pubblicazione", ID_TAPPA),
                arguments(DELETE, "/api/archivio/" + ID_TAPPA, null, "eliminazione", "pubblicazione", ID_TAPPA));
    }

    @ParameterizedTest(name = "{3} {4}: {0} {1}")
    @MethodSource("scrittureDiUnAdmin")
    void unAdminCheScriveSuiDatiDiUnAltro_lasciaUnInfoConChiCheCosaEDiChi(
            HttpMethod metodo, String indirizzo, Map<String, Object> corpo, String azione, String risorsa, UUID idRisorsa,
            CapturedOutput output) throws Exception {
        invia(metodo, indirizzo, corpo, admin).andExpect(status().is2xxSuccessful());

        assertThat(messaggi(output, "INFO", "AccessGuard")).containsExactly(
                "Intervento ADMIN: admin@test.it (id " + ID_ADMIN + "): " + azione + " " + risorsa + " " + idRisorsa
                        + " di proprietà dell'utente " + ID_MARIO);
    }

    // La lettura passa dallo stesso controllo di proprietà (LegaService.dettaglio → AccessGuard.checkOwner) ma non
    // cambia niente: un ADMIN che apre la lega di un altro non deve riempire i log
    @Test
    void unAdminCheLeggeLaLegaDiUnAltro_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        invia(GET, "/api/leghe/" + ID_LEGA, null, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID_LEGA.toString()));

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    // L'intervento è su dati di un altro: sui propri un ADMIN è un utente come gli altri
    @Test
    void unAdminCheModificaIPropriDati_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        invia(PUT, "/api/anagrafe/giocatori/" + ID_GIOCATORE_DELL_ADMIN, Map.of("nome", "Anna", "cognome", "Rossi"), admin)
                .andExpect(status().isOk());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    @Test
    void unUtenteCheModificaIPropriDati_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        invia(PUT, "/api/anagrafe/giocatori/" + ID_GIOCATORE, Map.of("nome", "Luca", "cognome", "Neri"), mario)
                .andExpect(status().isOk());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    // Chi non è il proprietario né un ADMIN riceve 403 e non interviene su niente
    @Test
    void unUtenteCheProvaAModificareDatiAltrui_riceve403SenzaRiga(CapturedOutput output) throws Exception {
        invia(PUT, "/api/anagrafe/giocatori/" + ID_GIOCATORE, Map.of("nome", "Luca", "cognome", "Neri"), luca)
                .andExpect(status().isForbidden());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    // La riga dice che l'intervento è avvenuto: una richiesta respinta (404, 409, 400) non ha cambiato niente e non la lascia
    @Test
    void unAdminCheNonTrovaLaRisorsa_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        invia(PUT, "/api/anagrafe/giocatori/" + UUID.randomUUID(), Map.of("nome", "Luca", "cognome", "Neri"), admin)
                .andExpect(status().isNotFound());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    @Test
    void unAdminCheAggiungeUnaTappaConUnIdGiaUsato_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        when(tappe.existsById(ID_TAPPA)).thenReturn(true);

        invia(POST, "/api/leghe/" + ID_LEGA + "/tappe", tappa(ID_TAPPA), admin).andExpect(status().isConflict());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    @Test
    void unAdminCheModificaUnaTappaConUnBloccoNonValido_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        Map<String, Object> tappaNonValida = tappa(ID_TAPPA);
        tappaNonValida.put("squadre", Map.of("non", "un array"));

        invia(PUT, "/api/tappe/" + ID_TAPPA, tappaNonValida, admin).andExpect(status().isBadRequest());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    @Test
    void unAdminChePubblicaUnaTappaNonConclusa_nonLasciaNessunaRiga(CapturedOutput output) throws Exception {
        tappaDiMario.setConclusa(false);

        invia(PUT, "/api/archivio/" + ID_TAPPA, null, admin).andExpect(status().isConflict());

        assertThat(messaggi(output, "INFO", "AccessGuard")).isEmpty();
    }

    /* ── Aiuti ── */

    /** Una richiesta di un utente autenticato, con il corpo in JSON se c'è */
    private ResultActions invia(HttpMethod metodo, String indirizzo, Object corpo, Utente chi) throws Exception {
        MockHttpServletRequestBuilder richiesta = request(metodo, indirizzo).with(user(chi));
        if (corpo != null) {
            richiesta.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(corpo));
        }
        return mvc.perform(richiesta);
    }

    /** Una POST senza utente autenticato, con il corpo in JSON: login e registrazione sono pubblici */
    private ResultActions postPubblico(String indirizzo, Map<String, Object> campi) throws Exception {
        return mvc.perform(post(indirizzo).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(campi)));
    }

    /** Una tappa valida nel corpo di una richiesta: ogni test cambia solo il campo che vuole mettere alla prova */
    private static Map<String, Object> tappa(UUID id) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("nome", "Tappa di Roma");
        t.put("luogo", "Roma");
        t.put("data", "2026-06-14");
        t.put("nGironi", 1);
        t.put("regole", Map.of("target", 21, "durata", 10, "ot", 2, "shot", 12));
        t.put("squadre", List.of());
        t.put("partite", List.of());
        return t;
    }

    private static AnagrafeGiocatore giocatore(UUID id, Utente autore) {
        AnagrafeGiocatore g = new AnagrafeGiocatore();
        ReflectionTestUtils.setField(g, "id", id);
        g.setNome("Luca");
        g.setCognome("Bianchi");
        g.setAutore(autore);
        g.setModificatoIl(LocalDateTime.now());
        return g;
    }

    /** Un utente con l'id che il database gli darebbe al salvataggio (il campo non ha un setter) */
    private static Utente utente(UUID id, String email, Ruolo ruolo) {
        Utente utente = new Utente(email, "hash", "Nome", ruolo);
        ReflectionTestUtils.setField(utente, "id", id);
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
