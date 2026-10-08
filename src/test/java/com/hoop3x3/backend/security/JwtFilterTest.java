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
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Il filtro da solo, senza Spring: JwtTools, repository e resolver degli errori sono simulati.
 * ErroriWebTest controlla le risposte complete, ma con il gestore generico di ExceptionsHandler i suoi casi
 * sull'errore a valle sono verdi anche con un filtro che inghiotte l'eccezione: qui si controlla la causa.
 */
class JwtFilterTest {

    private final JwtTools jwtTools = mock(JwtTools.class);
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

    // Token con la firma valida ma il subject mancante o non UUID: 401 con lo stesso messaggio di JwtTools per ogni token
    // non valido, e la richiesta non prosegue
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "non-un-uuid"})
    void subjectMancanteONonUuid_risponde401(String subject) throws Exception {
        tokenFirmatoConSubject(subject);
        FilterChain catena = mock(FilterChain.class);
        MockHttpServletRequest richiesta = richiestaConBearer();

        filtro.doFilter(richiesta, new MockHttpServletResponse(), catena);

        ArgumentCaptor<Exception> errore = ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(eq(richiesta), any(), isNull(), errore.capture());
        assertThat(errore.getValue()).isInstanceOf(UnauthorizedException.class).hasMessage(JwtTools.TOKEN_NON_VALIDO);
        verifyNoInteractions(catena);
    }

    // Accesso, registrazione, rinnovo e uscita non usano il Bearer: il filtro non lo esamina e la richiesta prosegue.
    // AuthControllerTest controlla rinnovo e uscita nella catena vera; qui ci sono tutti e quattro i percorsi
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/logout"})
    void endpointDiAutenticazione_nonEsaminanoIlBearer(String percorso) throws Exception {
        MockHttpServletRequest richiesta = richiestaConBearer();
        richiesta.setRequestURI(percorso);
        FilterChain catena = mock(FilterChain.class);

        filtro.doFilter(richiesta, new MockHttpServletResponse(), catena);

        verify(catena).doFilter(eq(richiesta), any());
        verifyNoInteractions(jwtTools, resolver);
    }

    // Con un context path (l'app pubblicata sotto /hoop) l'URI lo contiene: il percorso si confronta senza
    @Test
    void endpointDiAutenticazioneConContextPath_nonEsaminaIlBearer() throws Exception {
        MockHttpServletRequest richiesta = richiestaConBearer();
        richiesta.setContextPath("/hoop");
        richiesta.setRequestURI("/hoop/api/auth/refresh");
        FilterChain catena = mock(FilterChain.class);

        filtro.doFilter(richiesta, new MockHttpServletResponse(), catena);

        verify(catena).doFilter(eq(richiesta), any());
        verifyNoInteractions(jwtTools, resolver);
    }

    /** JwtTools simulato: la firma è valida e le claims hanno il subject indicato */
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
