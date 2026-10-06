package com.hoop3x3.backend.exceptions;

import com.hoop3x3.backend.dto.ErrorsDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

import static com.hoop3x3.backend.services.LogSupport.perLog;

/**
 * Tutte le risposte di errore hanno lo stesso corpo {message, timestamp}: il frontend legge un solo formato.
 * Estende ResponseEntityExceptionHandler così anche gli errori standard di Spring MVC (405, 415, 404,
 * parametro di tipo sbagliato…) passano da handleExceptionInternal e prendono lo stesso corpo.
 */
@Slf4j
@RestControllerAdvice
public class ExceptionsHandler extends ResponseEntityExceptionHandler {

    /* ── Errori standard di Spring MVC ── */

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        // Risposta già partita (client andato via, errore a metà scrittura): non c'è un secondo corpo da scrivere e non è
        // un errore del server. La superclasse lascia un WARN di una riga e risponde null, invece di un ERROR con lo stack
        if (rispostaGiaInviata(request)) {
            return super.handleExceptionInternal(ex, body, headers, status, request);
        }
        // I 5xx delle eccezioni di Spring MVC (risposta non scrivibile, timeout asincrono...) li prende la superclasse e
        // arrivano qui, non a handleImprevisto: la riga ERROR con lo stack va scritta qui, altrimenti il 500 neutro
        // non lascerebbe nessuna traccia nei log
        if (status.is5xxServerError()) {
            log.error("Errore {} di Spring MVC su {}", status.value(), richiestaPerLog(request), ex);
        }
        return ResponseEntity.status(status).headers(headers).body(errore(messaggioPer(status)));
    }

    // Payload che non rispetta le regole @NotBlank/@Email/...: un messaggio per campo
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        String messaggi = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(errore(messaggi));
    }

    // JSON malformato o tipo sbagliato in un campo del body
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(errore("Corpo della richiesta non valido"));
    }

    /* ── Eccezioni dell'applicazione ── */

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorsDTO> handleBadRequest(BadRequestException ex) {
        return risposta(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Login con email/password sbagliate: AuthenticationManager lancia BadCredentialsException
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorsDTO> handleBadCredentials() {
        return risposta(HttpStatus.UNAUTHORIZED, "Email o password non corretti");
    }

    // Token mancante/scaduto/non valido: arriva dal JwtFilter tramite HandlerExceptionResolver
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorsDTO> handleUnauthorized(UnauthorizedException ex) {
        return risposta(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    // @PreAuthorize non superato: senza questo gestore lo prenderebbe quello generico e diventerebbe un 500
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorsDTO> handleAccessDenied() {
        return risposta(HttpStatus.FORBIDDEN, "Non hai i permessi necessari per questa operazione");
    }

    // 403 lanciato dai service (es. modifica di una lega di un altro utente)
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorsDTO> handleForbidden(ForbiddenException ex) {
        return risposta(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorsDTO> handleNotFound(NotFoundException ex) {
        return risposta(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorsDTO> handleConflict(ConflictException ex) {
        return risposta(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Servizio esterno (Groq) non raggiungibile o in errore
    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<ErrorsDTO> handleUpstream(UpstreamException ex) {
        return risposta(ex.getStatus(), ex.getMessage());
    }

    // Vincolo del database violato (doppione, valore troppo lungo…): il dettaglio SQL resta nei log
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorsDTO> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Il messaggio del database può contenere il valore che ha violato il vincolo (l'email di un doppione), cioè qualcosa
        // che ha scritto un utente
        log.warn("Vincolo del database violato: {}", perLog(ex.getMostSpecificCause().getMessage()));
        return risposta(HttpStatus.CONFLICT, "Operazione in conflitto con i dati già salvati");
    }

    // Rete di sicurezza per ciò che nessun altro gestore prende: 500 con messaggio generico e riga ERROR nei log, con
    // metodo e percorso della richiesta (i 5xx delle eccezioni di Spring MVC passano da handleExceptionInternal, che li
    // scrive nei log)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorsDTO> handleImprevisto(Exception ex, WebRequest request) {
        log.error("Errore non gestito su {}", richiestaPerLog(request), ex);
        return risposta(HttpStatus.INTERNAL_SERVER_ERROR, "Errore interno del server: riprova più tardi");
    }

    /* ── Helper ── */

    private static ErrorsDTO errore(String messaggio) {
        return new ErrorsDTO(messaggio, LocalDateTime.now());
    }

    private static ResponseEntity<ErrorsDTO> risposta(HttpStatus status, String messaggio) {
        return ResponseEntity.status(status).body(errore(messaggio));
    }

    /**
     * «GET /api/leghe»: la richiesta che ha dato l'errore, per le righe ERROR dei log (senza la query, che può contenere
     * dati personali). Il percorso lo sceglie chi manda la richiesta, quindi passa da perLog: a capo e caratteri di
     * controllo si scrivono per esteso e non possono chiudere la riga per inventarne una sua.
     */
    private static String richiestaPerLog(WebRequest request) {
        // Con Spring MVC la richiesta è sempre un ServletWebRequest: un gestore di errori che lanciasse nasconderebbe l'errore vero
        if (!(request instanceof ServletWebRequest web)) return "una richiesta sconosciuta";
        HttpServletRequest richiesta = web.getRequest();
        return perLog(richiesta.getMethod() + " " + richiesta.getRequestURI());
    }

    /** I primi byte della risposta sono già partiti verso il client: non si può più scriverle sopra un corpo d'errore */
    private static boolean rispostaGiaInviata(WebRequest request) {
        if (!(request instanceof ServletWebRequest web) || web.getResponse() == null) return false;
        return web.getResponse().isCommitted();
    }

    /** Messaggio in italiano per gli errori standard di Spring MVC, scelto in base allo status */
    private static String messaggioPer(HttpStatusCode status) {
        int codice = status.value();
        if (codice == 404) return "Risorsa non trovata";
        if (codice == 405) return "Metodo non consentito per questo indirizzo";
        if (codice == 415) return "Formato della richiesta non supportato";
        if (codice >= 500) return "Errore interno del server: riprova più tardi";
        return "Richiesta non valida";
    }
}
