package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Legge il Bearer token e mette l'utente nel SecurityContext.
 * Senza header la richiesta prosegue anonima: sono poi le regole di SecurityConfig
 * a decidere se l'endpoint è pubblico (anagrafe/archivio in lettura) o no. Con un
 * header presente ma non valido si risponde subito 401.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    /** Endpoint pubblici di autenticazione: non passano dal filtro (vedi shouldNotFilter) */
    private static final Set<String> ENDPOINT_SENZA_BEARER =
            Set.of("/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/logout");

    private final JWTtools jwtTools;
    private final UtenteRepository utenteRepository;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtFilter(JWTtools jwtTools, UtenteRepository utenteRepository,
                     @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.jwtTools = jwtTools;
        this.utenteRepository = utenteRepository;
        this.exceptionResolver = exceptionResolver;
    }

    /** Login, registrazione, rinnovo e uscita non usano il Bearer: un JWT scaduto rimasto nella richiesta
     *  non deve impedire di rinnovare la sessione o di uscire. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // getRequestURI e non getServletPath: con MockMvc il servlet path è vuoto e i test non vedrebbero il percorso
        String percorso = request.getRequestURI().substring(request.getContextPath().length());
        return ENDPOINT_SENZA_BEARER.contains(percorso);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        Utente utente;
        try {
            utente = utenteDelToken(header.substring(7));
        } catch (RuntimeException e) {
            // Si ferma qui solo ciò che accade nella verifica del token, mai un errore a valle: token non valido → 401,
            // errore del database nella lettura dell'utente → 500, entrambi con il solito corpo JSON
            exceptionResolver.resolveException(request, response, null, e);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(utente, null, utente.getAuthorities()));
        // Fuori dal try: un errore a valle (controller, service, database) segue il percorso normale degli errori
        chain.doFilter(request, response);
    }

    /** Token valido → utente; qualsiasi problema del token diventa UnauthorizedException (401) */
    private Utente utenteDelToken(String token) {
        Claims claims = jwtTools.verifyToken(token);
        UUID utenteId;
        try {
            utenteId = UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException | NullPointerException _) {
            throw new UnauthorizedException(JWTtools.TOKEN_NON_VALIDO);
        }
        return utenteRepository.findById(utenteId)
                .orElseThrow(() -> new UnauthorizedException("L'utente associato al token non esiste più"));
    }
}
