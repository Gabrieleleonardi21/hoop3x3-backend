package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.dto.AuthResponseDTO;
import com.hoop3x3.backend.dto.LoginRequestDTO;
import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.dto.UtenteDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.JWTtools;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.hoop3x3.backend.services.LogSupport.perLog;

/**
 * Autenticazione: il JWT di accesso (30 minuti) viaggia nel corpo JSON, il refresh token (30 giorni)
 * in un cookie httpOnly che il browser rimanda solo a questi endpoint. Vedi AuthCookies e RefreshTokenService.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UtenteService utenteService;
    private final JWTtools jwtTools;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookies cookies;

    public AuthController(AuthenticationManager authenticationManager, UtenteService utenteService, JWTtools jwtTools,
                          RefreshTokenService refreshTokenService, AuthCookies cookies) {
        this.authenticationManager = authenticationManager;
        this.utenteService = utenteService;
        this.jwtTools = jwtTools;
        this.refreshTokenService = refreshTokenService;
        this.cookies = cookies;
    }

    /** Registrazione: crea l'utente e risponde già con token e cookie, così il client non deve rifare il login */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponseDTO register(@RequestBody @Validated RegisterRequestDTO dto, HttpServletResponse response) {
        Utente utente = utenteService.register(dto);
        log.info("Nuovo utente registrato: {} (id {})", perLog(utente.getEmail()), utente.getId());
        return accedi(utente, response);
    }

    @PostMapping("/login")
    public AuthResponseDTO login(@RequestBody @Validated LoginRequestDTO dto, HttpServletResponse response) {
        String email = UtenteService.normalizza(dto.email());
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, dto.password()));
        } catch (BadCredentialsException e) {
            // Email sconosciuta o password sbagliata (Spring non li distingue): l'email dice chi sbaglia o chi prova a
            // indovinare. La password non va mai nei log. L'email la sceglie chi fa il login: @Email ferma CR e LF ma non i
            // separatori di riga di Unicode, quindi passa da perLog. La risposta 401 la dà ExceptionsHandler
            log.warn("Login fallito per {}", perLog(email));
            throw e;
        }
        return accedi((Utente) auth.getPrincipal(), response);
    }

    /** Scambia il cookie di refresh con un nuovo JWT e un nuovo cookie (rotazione). Qui il Bearer non serve. */
    @PostMapping("/refresh")
    public AuthResponseDTO refresh(@CookieValue(name = AuthCookies.NOME, required = false) String refreshToken,
                                   HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) throw new UnauthorizedException("Sessione scaduta: accedi di nuovo");
        RefreshTokenService.Rinnovo rinnovo = refreshTokenService.ruota(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.diRefresh(rinnovo.nuovoToken()).toString());
        return new AuthResponseDTO(jwtTools.generateToken(rinnovo.utente()), UtenteDTO.from(rinnovo.utente()));
    }

    /** Revoca il refresh token e cancella il cookie; il JWT lo butta via il client */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = AuthCookies.NOME, required = false) String refreshToken,
                       HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.isBlank()) refreshTokenService.revoca(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.cancellazione().toString());
    }

    /** Usato all'avvio dell'app per verificare che il token salvato sia ancora valido */
    @GetMapping("/me")
    public UtenteDTO me(@AuthenticationPrincipal Utente utente) {
        return UtenteDTO.from(utente);
    }

    /** JWT nel corpo (come prima) e nuovo refresh token nel cookie httpOnly */
    private AuthResponseDTO accedi(Utente utente, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.diRefresh(refreshTokenService.emetti(utente)).toString());
        return new AuthResponseDTO(jwtTools.generateToken(utente), UtenteDTO.from(utente));
    }
}
