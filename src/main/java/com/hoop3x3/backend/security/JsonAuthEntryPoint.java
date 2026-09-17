package com.hoop3x3.backend.security;

import com.hoop3x3.backend.dto.ErrorsDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Richiesta senza token su un endpoint protetto: senza questo entry point Spring Security
 * risponderebbe 403 con il corpo di default. Qui si risponde 401 con il solito {message, timestamp},
 * così il frontend sa che deve (ri)fare il login e non che gli manca un ruolo.
 */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public JsonAuthEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorsDTO body = new ErrorsDTO("Autenticazione richiesta: accedi per continuare", LocalDateTime.now());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
