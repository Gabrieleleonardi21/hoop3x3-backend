package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Senza contesto Spring: JWTtools si costruisce con un JwtProperties scritto a mano. Si verifica la durata del token. */
class JWTtoolsTest {

    private static final String SEGRETO = "segreto-di-prova-di-almeno-32-caratteri";
    private final UUID id = UUID.randomUUID();
    private final Utente utente = new Utente("mario@test.it", "hash", "Mario", Ruolo.USER);

    @BeforeEach
    void setUp() {
        // L'id lo assegna JPA al salvataggio e l'entità non ha il setter: qui si imposta a mano
        ReflectionTestUtils.setField(utente, "id", id);
    }

    @Test
    void ilTokenScadeDopoIMinutiConfigurati() {
        JWTtools jwtTools = new JWTtools(new JwtProperties(SEGRETO, 30));

        Claims claims = jwtTools.verifyToken(jwtTools.generateToken(utente));

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        // iat ed exp nel JWT sono in secondi, e il token li calcola dallo stesso istante: la differenza è esatta
        Duration durata = Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
        assertThat(durata).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void unTokenScadutoVieneRespinto() {
        // scade un minuto prima di essere emesso: il record costruito a mano non passa dalla validazione
        JWTtools jwtTools = new JWTtools(new JwtProperties(SEGRETO, -1));

        String scaduto = jwtTools.generateToken(utente);

        assertThatThrownBy(() -> jwtTools.verifyToken(scaduto)).isInstanceOf(UnauthorizedException.class);
    }
}
