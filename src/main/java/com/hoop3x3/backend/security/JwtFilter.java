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
import java.util.UUID;

/**
 * Legge il Bearer token e mette l'utente nel SecurityContext.
 * Senza header la richiesta prosegue anonima: sono poi le regole di SecurityConfig
 * a decidere se l'endpoint è pubblico (anagrafe/archivio in lettura) o no. Con un
 * header presente ma non valido si risponde subito 401.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JWTtools jwtTools;
    private final UtenteRepository utenteRepository;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtFilter(JWTtools jwtTools, UtenteRepository utenteRepository,
                     @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.jwtTools = jwtTools;
        this.utenteRepository = utenteRepository;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtTools.verifyToken(header.substring(7));
            UUID utenteId = UUID.fromString(claims.getSubject());
            Utente utente = utenteRepository.findById(utenteId)
                    .orElseThrow(() -> new UnauthorizedException("L'utente associato al token non esiste più"));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(utente, null, utente.getAuthorities()));
            chain.doFilter(request, response);
        } catch (Exception e) {
            // Il filtro è fuori dai controller: gira l'eccezione all'ExceptionsHandler per avere il solito corpo JSON
            exceptionResolver.resolveException(request, response, null, e);
        }
    }
}
