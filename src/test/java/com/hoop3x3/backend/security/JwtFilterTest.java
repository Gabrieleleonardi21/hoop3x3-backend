package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Il filtro da solo, senza Spring: JWTtools, repository e resolver degli errori sono simulati.
 * ErroriWebTest controlla le risposte complete, ma con il gestore generico di ExceptionsHandler i suoi casi
 * sull'errore a valle sono verdi anche con un filtro che inghiotte l'eccezione: qui si controlla la causa.
 */
class JwtFilterTest {

    private final JWTtools jwtTools = mock(JWTtools.class);
    private final UtenteRepository utenteRepository = mock(UtenteRepository.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private final JwtFilter filtro = new JwtFilter(jwtTools, utenteRepository, resolver);

    // Con un token valido il filtro riempie il SecurityContext: si svuota perché non resti ai test successivi
    @AfterEach
    void svuotaIlContesto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void erroreAValleConTokenValido_risaleENonPassaDalResolver() throws Exception {
        UUID id = UUID.randomUUID();
        tokenFirmatoConSubject(id.toString());
        when(utenteRepository.findById(id)).thenReturn(Optional.of(new Utente("mario@x.it", "hash", "Mario", Ruolo.USER)));
        FilterChain aValleEsplode = (rq, rs) -> {
            throw new IllegalStateException("errore a valle");
        };

        // Un errore a valle non è affare del filtro: risale per la strada normale, come per le richieste anonime
        assertThatThrownBy(() -> filtro.doFilter(richiestaConBearer(), new MockHttpServletResponse(), aValleEsplode))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(resolver);
    }

    // Token con la firma valida ma il subject mancante o non UUID: 401, e la richiesta non prosegue
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "non-un-uuid"})
    void subjectMancanteONonUuid_risponde401(String subject) throws Exception {
        tokenFirmatoConSubject(subject);
        FilterChain catena = mock(FilterChain.class);
        MockHttpServletRequest richiesta = richiestaConBearer();

        filtro.doFilter(richiesta, new MockHttpServletResponse(), catena);

        verify(resolver).resolveException(eq(richiesta), any(), isNull(), isA(UnauthorizedException.class));
        verifyNoInteractions(catena);
    }

    /** JWTtools simulato: la firma è valida e le claims hanno il subject indicato */
    private void tokenFirmatoConSubject(String subject) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(subject);
        when(jwtTools.verifyToken("t")).thenReturn(claims);
    }

    private static MockHttpServletRequest richiestaConBearer() {
        MockHttpServletRequest richiesta = new MockHttpServletRequest();
        richiesta.addHeader("Authorization", "Bearer t");
        return richiesta;
    }
}
