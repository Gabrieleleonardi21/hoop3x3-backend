package com.hoop3x3.backend.security;

import com.hoop3x3.backend.dto.ErrorsDTO;
import com.hoop3x3.backend.support.Tempo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Rifiuta con 413 le richieste con il corpo oltre 2 MB, prima che arrivino a Spring MVC: senza un tetto Jackson
 * leggerebbe in memoria qualsiasi corpo, anche da centinaia di megabyte, e il login e la registrazione sono pubblici.
 * <p>
 * Il tetto regge solo perché il FormContentFilter di Spring Boot è spento (spring.mvc.formcontent.filter.enabled=false
 * in application.properties): girerebbe prima della sicurezza e di questo filtro e leggerebbe per intero, senza tetto,
 * i corpi form di PUT, PATCH e DELETE. LimiteDimensioneIT lo prova su un server vero.
 * <p>
 * Non ha {@code @Order}: gira dopo la catena di Spring Security, quindi il 413 porta gli header CORS e una richiesta
 * senza token su un endpoint protetto riceve il 401 senza che il suo corpo venga letto.
 * <p>
 * Gira prima di Spring MVC: come JsonAuthEntryPoint scrive da sé il corpo {message, timestamp}, con lo stesso ErrorsDTO
 * e lo stesso ObjectMapper che usa ExceptionsHandler, quindi con lo stesso formato (timestamp compreso).
 */
@Component
public class LimiteDimensioneFilter extends OncePerRequestFilter {

    private static final int LIMITE_MB = 2;
    public static final int LIMITE_BYTE = LIMITE_MB * 1024 * 1024;

    private final ObjectMapper mapper;

    public LimiteDimensioneFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long dichiarata = request.getContentLengthLong();
        // Content-Length oltre il limite: si risponde subito, senza leggere nulla
        if (dichiarata > LIMITE_BYTE) {
            rifiuta(response);
            return;
        }
        // Content-Length nel limite: il container non consegna più byte di quanti dichiarati, non serve contare
        if (dichiarata >= 0) {
            chain.doFilter(request, response);
            return;
        }
        // Nessun Content-Length (corpo a blocchi): la dimensione si scopre leggendo. Si leggono al massimo LIMITE + 1 byte,
        // quanti bastano per sapere se il limite è sorpassato, e a valle il corpo già letto si rilegge uguale
        byte[] corpo = request.getInputStream().readNBytes(LIMITE_BYTE + 1);
        if (corpo.length > LIMITE_BYTE) {
            rifiuta(response);
            return;
        }
        chain.doFilter(new RichiestaConCorpo(request, corpo), response);
    }

    private void rifiuta(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorsDTO corpo = new ErrorsDTO("La richiesta è troppo grande: il limite è di " + LIMITE_MB + " MB", Tempo.adesso());
        response.getWriter().write(mapper.writeValueAsString(corpo));
    }

    /** La stessa richiesta con il corpo già letto dal filtro: a valle lo si rilegge da `corpo`, come byte o come testo */
    private static class RichiestaConCorpo extends HttpServletRequestWrapper {

        private final byte[] corpo;

        RichiestaConCorpo(HttpServletRequest richiesta, byte[] corpo) {
            super(richiesta);
            this.corpo = corpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream origine = new ByteArrayInputStream(corpo);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return origine.read();
                }

                @Override
                public int read(byte[] buffer, int inizio, int lunghezza) {
                    return origine.read(buffer, inizio, lunghezza);
                }

                @Override
                public boolean isFinished() {
                    return origine.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Lettura asincrona del corpo non supportata");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            // La codifica dichiarata dalla richiesta; il JSON è UTF-8, che vale anche quando non è dichiarata
            Charset codifica = StandardCharsets.UTF_8;
            if (getCharacterEncoding() != null) codifica = Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), codifica));
        }
    }
}
