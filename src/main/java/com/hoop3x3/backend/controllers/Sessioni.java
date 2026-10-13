package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.AuthResponseDTO;
import com.hoop3x3.backend.dto.UtenteDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.services.RefreshTokenService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Aprire e chiudere la sessione del browser che ha fatto la richiesta, in un posto solo: la usano AuthController
 * (registrazione, login, uscita) e ProfiloController (cambio della password, eliminazione dell'account).
 */
@Component
public class Sessioni {

    private final JwtTools jwtTools;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookies cookies;

    public Sessioni(JwtTools jwtTools, RefreshTokenService refreshTokenService, AuthCookies cookies) {
        this.jwtTools = jwtTools;
        this.refreshTokenService = refreshTokenService;
        this.cookies = cookies;
    }

    /** JWT nel corpo e nuovo refresh token nel cookie httpOnly */
    public AuthResponseDTO apri(Utente utente, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.diRefresh(refreshTokenService.emetti(utente)).toString());
        return new AuthResponseDTO(jwtTools.generateToken(utente), UtenteDTO.from(utente));
    }

    /** Il cookie di cancellazione (stesso nome e path, Max-Age=0): il browser butta via il refresh token */
    public void chiudi(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.cancellazione().toString());
    }
}
