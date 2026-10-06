package com.hoop3x3.backend.exceptions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hoop3x3.backend.dto.ErrorsDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ExceptionsHandler da solo, senza Spring: richiesta e risposta sono finte e i log si leggono dal test.
 * Una connessione che si chiude a metà risposta non esiste in MockMvc: qui la risposta già partita è una risposta «committed».
 */
class ExceptionsHandlerTest {

    private final ExceptionsHandler gestore = new ExceptionsHandler();
    private final MockHttpServletResponse risposta = new MockHttpServletResponse();
    private final ServletWebRequest richiesta = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/leghe/x"), risposta);

    private final Logger logDelGestore = (Logger) LoggerFactory.getLogger(ExceptionsHandler.class);
    private final ListAppender<ILoggingEvent> logCatturato = new ListAppender<>();

    @BeforeEach
    void catturaIlLog() {
        // Le righe di log del gestore si leggono dal test e non passano dalla console: l'output della build resta pulito
        logCatturato.start();
        logDelGestore.addAppender(logCatturato);
        logDelGestore.setAdditive(false);
    }

    @AfterEach
    void rilasciaIlLog() {
        logDelGestore.detachAppender(logCatturato);
        logDelGestore.setAdditive(true);
    }

    // Il client chiude la connessione mentre il server scrive una risposta grande: i primi byte sono già partiti e la
    // scrittura fallisce con HttpMessageNotWritableException (Broken pipe). Non è un errore del server: niente secondo
    // corpo da accodare a quello parziale e niente ERROR con lo stack, solo la riga WARN della superclasse
    @Test
    void rispostaGiaInviata_nonAccodaUnSecondoCorpoELasciaSoloUnWarnDiUnaRiga() {
        risposta.setCommitted(true);

        ResponseEntity<Object> esito = gestore.handleExceptionInternal(
                new HttpMessageNotWritableException("Could not write JSON: Broken pipe"),
                null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, richiesta);

        assertThat(esito).as("nessun corpo d'errore da accodare a quello già partito").isNull();
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(Level.WARN);
            assertThat(riga.getThrowableProxy()).as("una riga sola, senza stack").isNull();
        });
    }

    // Un 500 senza la richiesta che l'ha dato è un guasto da cercare a tentoni: la riga ERROR dice metodo e percorso, e
    // sotto resta lo stack dell'eccezione
    @Test
    void erroreNonGestito_scriveLaRigaErrorConMetodoEPercorsoDellaRichiesta() {
        ResponseEntity<ErrorsDTO> esito = gestore.handleImprevisto(new IllegalStateException("dettaglio interno"), richiesta);

        assertThat(esito.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(Level.ERROR);
            assertThat(riga.getFormattedMessage()).isEqualTo("Errore non gestito su GET /api/leghe/x");
            assertThat(riga.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    // I 5xx delle eccezioni di Spring MVC passano da handleExceptionInternal e non da handleImprevisto: stessa informazione
    @Test
    void erroreDiSpringMvcCon5xx_scriveLaRigaErrorConMetodoEPercorsoDellaRichiesta() {
        gestore.handleExceptionInternal(new HttpMessageNotWritableException("x"),
                null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, richiesta);

        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(Level.ERROR);
            assertThat(riga.getFormattedMessage()).isEqualTo("Errore 500 di Spring MVC su GET /api/leghe/x");
            assertThat(riga.getThrowableProxy().getClassName()).isEqualTo(HttpMessageNotWritableException.class.getName());
        });
    }

    // Il percorso lo scrive chi manda la richiesta: con un CR o un LF dentro, una riga di log ne diventerebbe due e la
    // seconda sarebbe inventata da lui. Nel log restano i caratteri «\r» e «\n» scritti per esteso, così il tentativo si vede
    @Test
    void percorsoConCrLf_nonPuoInventareRigheDiLog() {
        ServletWebRequest ostile = new ServletWebRequest(
                new MockHttpServletRequest("GET", "/api/leghe/x\r\nERROR riga inventata"), risposta);

        gestore.handleImprevisto(new IllegalStateException("x"), ostile);
        gestore.handleExceptionInternal(new HttpMessageNotWritableException("x"),
                null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, ostile);

        assertThat(logCatturato.list).hasSize(2).allSatisfy(riga ->
                assertThat(riga.getFormattedMessage()).doesNotContain("\r", "\n")
                        .endsWith("GET /api/leghe/x\\r\\nERROR riga inventata"));
    }

    // La pulizia non si ferma a CR e LF: i separatori di riga di Unicode e i caratteri di controllo si scrivono per esteso
    @Test
    void percorsoConSeparatoriDiRigaUnicode_nonPuoInventareRigheDiLog() {
        ServletWebRequest ostile = new ServletWebRequest(
                new MockHttpServletRequest("GET", "/api/leghe/x\u2028ERROR riga inventata\u0085\u001b[2J"), risposta);

        gestore.handleImprevisto(new IllegalStateException("x"), ostile);

        assertThat(logCatturato.list).singleElement().satisfies(riga ->
                assertThat(riga.getFormattedMessage()).doesNotContainPattern("[\\p{Cc}\\p{Zl}\\p{Zp}]")
                        .endsWith("GET /api/leghe/x\\u2028ERROR riga inventata\\u0085\\u001b[2J"));
    }

    // Il messaggio del database può contenere il valore che ha violato un vincolo (per esempio l'email di un doppione):
    // lo ha scritto un utente, quindi passa dalla stessa pulizia
    @Test
    void vincoloDelDatabaseViolato_ilMessaggioDelDatabaseNonPuoInventareRigheDiLog() {
        ResponseEntity<ErrorsDTO> esito = gestore.handleDataIntegrity(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"utenti_email_key\" Detail: Key (email)=(ma\u2028rio@x.it\r\nINFO riga inventata) already exists."));

        assertThat(esito.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(logCatturato.list).singleElement().satisfies(riga -> {
            assertThat(riga.getLevel()).isEqualTo(Level.WARN);
            assertThat(riga.getFormattedMessage()).doesNotContainPattern("[\\p{Cc}\\p{Zl}\\p{Zp}]")
                    .contains("Key (email)=(ma\\u2028rio@x.it\\r\\nINFO riga inventata) already exists.");
        });
    }

    // Un gestore di errori che a sua volta lancia nasconderebbe l'errore vero: una richiesta di un tipo imprevisto non lo rompe
    @Test
    void richiestaDiUnTipoImprevisto_nonRompeIlGestoreEScriveComunqueLaRiga() {
        ResponseEntity<ErrorsDTO> esito = gestore.handleImprevisto(new IllegalStateException("x"), mock(WebRequest.class));

        assertThat(esito.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(logCatturato.list).singleElement().satisfies(riga ->
                assertThat(riga.getFormattedMessage()).isEqualTo("Errore non gestito su una richiesta sconosciuta"));
    }

    // Gli errori di Groq li registra già CoachAiService (con lo stato e il corpo della risposta): se li scrivesse anche
    // il gestore, ogni errore del Coach comparirebbe due volte nei log
    @Test
    void erroreDelCoach_ilGestoreRispondeESenzaScrivereUnaSecondaRiga() {
        ResponseEntity<ErrorsDTO> esito = gestore.handleUpstream(
                new UpstreamException(HttpStatus.BAD_GATEWAY, "Servizio AI non raggiungibile"));

        assertThat(esito.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(logCatturato.list).isEmpty();
    }

    // Un limite di frequenza superato è una risposta normale e non un guasto: 429 con lo stesso corpo {message, timestamp} degli
    // altri errori, il Retry-After in secondi per chi vuole aspettare il tempo giusto e nessuna riga nei log del gestore (la
    // riga d'avviso, una per chiave e per finestra, la scrive il filtro che conta le richieste)
    @Test
    void troppeRichieste_rispondeConRetryAfterEIlCorpoStandardSenzaScrivereNelLog() {
        ResponseEntity<ErrorsDTO> esito = gestore.handleTroppeRichieste(
                new TroppeRichiesteException("Troppi tentativi di accesso", 40));

        assertThat(esito.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(esito.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("40");
        assertThat(esito.getBody().message()).isEqualTo("Troppi tentativi di accesso: riprova tra 40 secondi");
        assertThat(esito.getBody().timestamp()).isNotNull();
        assertThat(logCatturato.list).isEmpty();
    }

    // Ogni errore esce con il Content-Type JSON fissato, qualunque Accept mandi il client: senza, Spring sceglie il tipo in base
    // ad Accept e per text/html o application/xml non riesce a scrivere il corpo (le richieste vere sono in ErroriWebTest)
    @Test
    void ogniErrore_escePerSempreInJson() {
        List<ResponseEntity<?>> risposte = List.of(
                gestore.handleBadRequest(new BadRequestException("x")),
                gestore.handleBadCredentials(),
                gestore.handleUnauthorized(new UnauthorizedException("x")),
                gestore.handleAccessDenied(),
                gestore.handleForbidden(new ForbiddenException("x")),
                gestore.handleNotFound(new NotFoundException("x")),
                gestore.handleConflict(new ConflictException("x")),
                gestore.handleUpstream(new UpstreamException(HttpStatus.BAD_GATEWAY, "x")),
                gestore.handleTroppeRichieste(new TroppeRichiesteException("x", 1)),
                gestore.handleDataIntegrity(new DataIntegrityViolationException("x")),
                gestore.handleImprevisto(new IllegalStateException("x"), richiesta),
                // Gli errori di Spring MVC, che ExceptionsHandler ridefinisce
                gestore.handleExceptionInternal(new IllegalStateException("x"), null, new HttpHeaders(), HttpStatus.NOT_FOUND, richiesta),
                gestore.handleHttpMessageNotReadable(new HttpMessageNotReadableException("x", mock(HttpInputMessage.class)),
                        new HttpHeaders(), HttpStatus.BAD_REQUEST, richiesta));

        assertThat(risposte).hasSize(13).allSatisfy(risposta ->
                assertThat(risposta.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON));
    }
}
