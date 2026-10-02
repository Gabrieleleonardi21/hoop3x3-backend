package com.hoop3x3.backend.exceptions;

import com.hoop3x3.backend.dto.ErrorsDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Tutte le risposte di errore hanno lo stesso corpo {message, timestamp}: il frontend legge un solo formato.
 * Estende ResponseEntityExceptionHandler così anche gli errori standard di Spring MVC (405, 415, 404,
 * parametro di tipo sbagliato…) passano da handleExceptionInternal e prendono lo stesso corpo.
 */
@RestControllerAdvice
public class ExceptionsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExceptionsHandler.class);

    /* ── Errori standard di Spring MVC ── */

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
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
        log.warn("Vincolo del database violato: {}", ex.getMostSpecificCause().getMessage());
        return risposta(HttpStatus.CONFLICT, "Operazione in conflitto con i dati già salvati");
    }

    // Rete di sicurezza: qualsiasi errore non previsto diventa un 500 con messaggio generico, e finisce nei log
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorsDTO> handleImprevisto(Exception ex) {
        log.error("Errore non gestito", ex);
        return risposta(HttpStatus.INTERNAL_SERVER_ERROR, "Errore interno del server: riprova più tardi");
    }

    /* ── Helper ── */

    private static ErrorsDTO errore(String messaggio) {
        return new ErrorsDTO(messaggio, LocalDateTime.now());
    }

    private static ResponseEntity<ErrorsDTO> risposta(HttpStatus status, String messaggio) {
        return ResponseEntity.status(status).body(errore(messaggio));
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
