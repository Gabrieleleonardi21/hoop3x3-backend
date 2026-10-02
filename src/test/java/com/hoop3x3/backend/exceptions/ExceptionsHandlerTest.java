package com.hoop3x3.backend.exceptions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

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
}
