package com.hoop3x3.backend.exceptions;

import com.hoop3x3.backend.dto.ErrorsDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/** Tutte le risposte di errore hanno lo stesso corpo {message, timestamp}: il frontend legge un solo formato. */
@RestControllerAdvice
public class ExceptionsHandler {

    // Payload che non rispetta le regole @NotBlank/@Email/...: Spring blocca la richiesta prima del controller
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 400
    public ErrorsDTO handleValidation(MethodArgumentNotValidException ex) {
        String messaggi = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return new ErrorsDTO(messaggi, LocalDateTime.now());
    }

    // JSON malformato o tipo sbagliato in un campo del body
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 400
    public ErrorsDTO handleUnreadable() {
        return new ErrorsDTO("Corpo della richiesta non valido", LocalDateTime.now());
    }

    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 400
    public ErrorsDTO handleBadRequest(BadRequestException ex) {
        return new ErrorsDTO(ex.getMessage(), LocalDateTime.now());
    }

    // Login con email/password sbagliate: AuthenticationManager lancia BadCredentialsException
    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED) // 401
    public ErrorsDTO handleBadCredentials() {
        return new ErrorsDTO("Email o password non corretti", LocalDateTime.now());
    }

    // Token mancante/scaduto/non valido: arriva dal JwtFilter tramite HandlerExceptionResolver
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED) // 401
    public ErrorsDTO handleUnauthorized(UnauthorizedException ex) {
        return new ErrorsDTO(ex.getMessage(), LocalDateTime.now());
    }

    // @PreAuthorize non superato: senza handler il client riceverebbe un 403 con corpo vuoto
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN) // 403
    public ErrorsDTO handleAccessDenied() {
        return new ErrorsDTO("Non hai i permessi necessari per questa operazione", LocalDateTime.now());
    }

    // 403 lanciato dai service (es. modifica di una lega di un altro utente)
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN) // 403
    public ErrorsDTO handleForbidden(ForbiddenException ex) {
        return new ErrorsDTO(ex.getMessage(), LocalDateTime.now());
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND) // 404
    public ErrorsDTO handleNotFound(NotFoundException ex) {
        return new ErrorsDTO(ex.getMessage(), LocalDateTime.now());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT) // 409
    public ErrorsDTO handleConflict(ConflictException ex) {
        return new ErrorsDTO(ex.getMessage(), LocalDateTime.now());
    }

    // Servizio esterno (Groq) non raggiungibile o in errore
    @ExceptionHandler(UpstreamException.class)
    public org.springframework.http.ResponseEntity<ErrorsDTO> handleUpstream(UpstreamException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatus())
                .body(new ErrorsDTO(ex.getMessage(), LocalDateTime.now()));
    }
}
