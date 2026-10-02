package com.hoop3x3.backend.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Proprietà jwt.* lette da env.properties. Sono validate all'avvio: con un secret mancante, troppo corto
 * o lasciato uguale all'esempio il server NON parte, invece di firmare token con una chiave nota a tutti.
 * <p>
 * Le regole sul contenuto del secret sono metodi @AssertTrue e non annotazioni sul campo (@Size): per un campo
 * rifiutato Spring Boot scrive il valore nell'errore di avvio, quindi nei log, e un secret corto ma vero non deve
 * finirci. Di un metodo @AssertTrue scrive solo «false».
 */
@ConfigurationProperties(prefix = "jwt")
@Validated
public record JwtProperties(
        @NotBlank(message = "jwt.secret è obbligatorio: imposta JWT_SECRET in env.properties") String secret,
        @DefaultValue("30") @Min(5) @Max(1440) long durataMinuti
) {
    /** HS256 vuole una chiave di almeno 256 bit, cioè 32 byte */
    static final int LUNGHEZZA_MINIMA_SECRET = 32;
    /** Il valore che il vecchio env.properties.example proponeva: è pubblico, chi lo ha copiato non deve poterlo usare */
    static final String SECRET_DI_ESEMPIO = "cambia-questa-stringa-con-almeno-32-caratteri-casuali";

    @AssertTrue(message = "jwt.secret deve avere almeno " + LUNGHEZZA_MINIMA_SECRET + " caratteri")
    public boolean isSecretLungoAbbastanza() {
        // Se il secret manca lo segnala già @NotBlank
        return secret == null || secret.length() >= LUNGHEZZA_MINIMA_SECRET;
    }

    @AssertTrue(message = "jwt.secret è ancora il valore d'esempio: sostituiscilo con una stringa casuale")
    public boolean isSecretPersonalizzato() {
        return !SECRET_DI_ESEMPIO.equals(secret);
    }

    /** Il toString() automatico di un record scrive tutti i campi, secret compreso: chi stampa le proprietà non lo porta nei log */
    @Override
    public String toString() {
        return "JwtProperties[durataMinuti=" + durataMinuti + "]";
    }
}
