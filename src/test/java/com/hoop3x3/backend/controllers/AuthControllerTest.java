package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest; // Spring Boot 4: package del modulo webmvc-test
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Solo lo strato web: servizi e repository sono simulati, la catena di sicurezza è quella vera. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtFilter.class, JsonAuthEntryPoint.class, AuthCookies.class})
class AuthControllerTest {

    // Il 401 che il filtro dà a un JWT scaduto: lo stesso testo di JwtTools, che qui è simulato
    private static final String TOKEN_SCADUTO = "Sessione scaduta o token non valido: accedi di nuovo";

    @Autowired MockMvc mockMvc;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean UtenteService utenteService;
    @MockitoBean JwtTools jwtTools;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean UtenteRepository utenteRepository;

    private final Utente utente = new Utente("mario@test.it", "hash", "Mario", Ruolo.USER);

    @Test
    void loginRispondeConIlJwtEImpostaIlCookieDiRefresh() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken(utente, null, utente.getAuthorities()));
        when(jwtTools.generateToken(utente)).thenReturn("jwt-di-prova");
        when(refreshTokenService.emetti(utente)).thenReturn("token-di-refresh");

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mario@test.it\",\"password\":\"segreta123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-di-prova"))
                .andExpect(jsonPath("$.user.name").value("Mario"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("hoop3x3_refresh=token-di-refresh"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Lax"),
                        containsString("Path=/api/auth"),
                        containsString("Max-Age=2592000"))));
    }

    @Test
    void refreshSenzaCookieRisponde401NelFormatoStandard() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sessione scaduta: accedi di nuovo"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void refreshRuotaIlTokenERispondeConNuovoJwtENuovoCookie() throws Exception {
        when(refreshTokenService.ruota("vecchio")).thenReturn(new RefreshTokenService.Rinnovo(utente, "nuovo"));
        when(jwtTools.generateToken(utente)).thenReturn("jwt-nuovo");

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("hoop3x3_refresh", "vecchio")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-nuovo"))
                .andExpect(jsonPath("$.user.email").value("mario@test.it"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("hoop3x3_refresh=nuovo")));
    }

    @Test
    void refreshInGaraConUnAltraRichiestaRisponde409() throws Exception {
        when(refreshTokenService.ruota("vecchio")).thenThrow(new ConflictException("Sessione già rinnovata da un'altra richiesta: riprova"));

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("hoop3x3_refresh", "vecchio")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Sessione già rinnovata da un'altra richiesta: riprova"));
    }

    @Test
    void logoutRevocaIlTokenECancellaIlCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("hoop3x3_refresh", "vecchio")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("hoop3x3_refresh="),
                        containsString("Max-Age=0"),
                        containsString("Path=/api/auth"))));
        verify(refreshTokenService).revoca("vecchio");
    }

    @Test
    void logoutSenzaCookieRisponde204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    // Rinnovo e uscita si autenticano con il cookie: un JWT scaduto rimasto nell'Authorization non deve fermarli
    @Test
    void refreshConBearerScadutoUsaComunqueIlCookie() throws Exception {
        jwtScaduto();
        when(refreshTokenService.ruota("vecchio")).thenReturn(new RefreshTokenService.Rinnovo(utente, "nuovo"));
        when(jwtTools.generateToken(utente)).thenReturn("jwt-nuovo");

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("hoop3x3_refresh", "vecchio"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer scaduto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-nuovo"));
    }

    @Test
    void logoutConBearerScadutoRevocaComunqueIlToken() throws Exception {
        jwtScaduto();

        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("hoop3x3_refresh", "vecchio"))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer scaduto"))
                .andExpect(status().isNoContent());
        verify(refreshTokenService).revoca("vecchio");
    }

    // Il filtro salta solo i quattro endpoint pubblici di autenticazione: su /me esamina il Bearer e lo respinge con il
    // messaggio del token scaduto. Se lo saltasse, la richiesta proseguirebbe anonima: sempre 401, ma con un altro messaggio
    @Test
    void meConBearerScadutoRisponde401ConIlMessaggioDelToken() throws Exception {
        jwtScaduto();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer scaduto"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(TOKEN_SCADUTO));
    }

    /** Il JwtTools simulato respinge il JWT «scaduto» con la stessa eccezione di quello vero */
    private void jwtScaduto() {
        when(jwtTools.verifyToken("scaduto")).thenThrow(new UnauthorizedException(TOKEN_SCADUTO));
    }
}
