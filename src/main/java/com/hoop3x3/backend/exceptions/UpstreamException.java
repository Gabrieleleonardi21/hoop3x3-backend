package com.hoop3x3.backend.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Errore del servizio esterno (Groq): lo status lo decide chi la lancia (429 rate limit, 502 errore, 503 non configurato). */
@Getter
public class UpstreamException extends RuntimeException {
    private final HttpStatus status;

    public UpstreamException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
