package com.hoop3x3.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Il filtro da solo, senza Spring: la richiesta è finta e conta i byte che il filtro legge dal corpo,
 * la catena è simulata. La stessa regola dentro la catena vera è in ValidazioneWebTest.
 */
class LimiteDimensioneFilterTest {

    private static final int LIMITE = LimiteDimensioneFilter.LIMITE_BYTE;
    private static final long SENZA_CONTENT_LENGTH = -1;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final LimiteDimensioneFilter filtro = new LimiteDimensioneFilter(mapper);
    private final FilterChain catena = mock(FilterChain.class);
    private final MockHttpServletResponse risposta = new MockHttpServletResponse();

    /**
     * POST con il corpo dato e la lunghezza dichiarata che si vuole (SENZA_CONTENT_LENGTH = corpo a blocchi).
     * Come in un container vero lo stream è uno solo e si consuma: chi lo legge dopo il filtro trova ciò che resta.
     * Tiene il conto dei byte letti, per controllare che il filtro non legga più del necessario.
     */
    private static class RichiestaDiProva extends MockHttpServletRequest {
        private final long lunghezzaDichiarata;
        private final ServletInputStream flusso;
        long byteLetti;

        RichiestaDiProva(byte[] corpo, long lunghezzaDichiarata) {
            super("POST", "/api/leghe");
            this.lunghezzaDichiarata = lunghezzaDichiarata;
            ByteArrayInputStream origine = new ByteArrayInputStream(corpo);
            this.flusso = new ServletInputStream() {
                @Override
                public int read() {
                    int letto = origine.read();
                    if (letto != -1) byteLetti++;
                    return letto;
                }

                @Override
                public int read(byte[] buffer, int inizio, int lunghezza) {
                    int letti = origine.read(buffer, inizio, lunghezza);
                    if (letti > 0) byteLetti += letti;
                    return letti;
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
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public long getContentLengthLong() {
            return lunghezzaDichiarata;
        }

        @Override
        public int getContentLength() {
            return (int) Math.min(lunghezzaDichiarata, Integer.MAX_VALUE);
        }

        @Override
        public ServletInputStream getInputStream() {
            return flusso;
        }
    }

    /** Il corpo della risposta è {message, timestamp} come quello di ExceptionsHandler, con stato 413 */
    private void assert413ConCorpoStandard() throws Exception {
        assertThat(risposta.getStatus()).isEqualTo(413);
        assertThat(risposta.getContentType()).startsWith("application/json").containsIgnoringCase("UTF-8");
        JsonNode corpo = mapper.readTree(risposta.getContentAsString(StandardCharsets.UTF_8));
        assertThat(corpo.propertyNames()).containsExactlyInAnyOrder("message", "timestamp");
        assertThat(corpo.get("message").asString()).contains("2 MB");
        assertThat(LocalDateTime.parse(corpo.get("timestamp").asString())).isNotNull();
    }

    @Test
    void contentLengthOltreIlLimite_rispondeSubito413SenzaLeggereIlCorpo() throws Exception {
        RichiestaDiProva richiesta = new RichiestaDiProva(new byte[3 * 1024 * 1024], 3L * 1024 * 1024);

        filtro.doFilter(richiesta, risposta, catena);

        assert413ConCorpoStandard();
        assertThat(richiesta.byteLetti).isZero();
        verifyNoInteractions(catena);
    }

    @Test
    void senzaContentLength_corpoOltreIlLimite_rispondeEPoiNonLeggeAltro() throws Exception {
        RichiestaDiProva richiesta = new RichiestaDiProva(new byte[3 * 1024 * 1024], SENZA_CONTENT_LENGTH);

        filtro.doFilter(richiesta, risposta, catena);

        assert413ConCorpoStandard();
        assertThat(richiesta.byteLetti).isLessThanOrEqualTo(LIMITE + 1L); // si ferma appena sorpassato il limite
        verifyNoInteractions(catena);
    }

    @ParameterizedTest
    @ValueSource(longs = {SENZA_CONTENT_LENGTH, LIMITE})
    void corpoDiEsattamente2Mb_siAccetta(long lunghezzaDichiarata) throws Exception {
        RichiestaDiProva richiesta = new RichiestaDiProva(new byte[LIMITE], lunghezzaDichiarata);

        filtro.doFilter(richiesta, risposta, catena);

        assertThat(risposta.getStatus()).isEqualTo(200);
        verify(catena).doFilter(any(ServletRequest.class), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {SENZA_CONTENT_LENGTH, LIMITE + 1L})
    void corpoDi2MbPiuUnByte_siRifiuta(long lunghezzaDichiarata) throws Exception {
        RichiestaDiProva richiesta = new RichiestaDiProva(new byte[LIMITE + 1], lunghezzaDichiarata);

        filtro.doFilter(richiesta, risposta, catena);

        assert413ConCorpoStandard();
        verifyNoInteractions(catena);
    }

    @Test
    void contentLengthNelLimite_proseguePassandoLaRichiestaOriginaleSenzaLeggerla() throws Exception {
        byte[] corpo = "{\"nome\":\"Roma\"}".getBytes(StandardCharsets.UTF_8);
        RichiestaDiProva richiesta = new RichiestaDiProva(corpo, corpo.length);

        filtro.doFilter(richiesta, risposta, catena);

        verify(catena).doFilter(same(richiesta), any());
        assertThat(richiesta.byteLetti).isZero(); // lo legge Spring MVC: il container non ne consegna più di quelli dichiarati
    }

    @Test
    void senzaContentLength_corpoNelLimite_chiEStaAValleLoRilegge() throws Exception {
        byte[] corpo = "{\"nome\":\"è\"}".getBytes(StandardCharsets.UTF_8);
        RichiestaDiProva richiesta = new RichiestaDiProva(corpo, SENZA_CONTENT_LENGTH);
        richiesta.setCharacterEncoding("UTF-8");

        filtro.doFilter(richiesta, risposta, catena);

        ArgumentCaptor<ServletRequest> aValle = ArgumentCaptor.forClass(ServletRequest.class);
        verify(catena).doFilter(aValle.capture(), any());
        // Il corpo è già stato letto dal filtro: a valle si rilegge uguale, sia come byte sia come testo
        assertThat(aValle.getValue().getInputStream().readAllBytes()).isEqualTo(corpo);
        assertThat(aValle.getValue().getReader().readLine()).isEqualTo("{\"nome\":\"è\"}");
    }

    @Test
    void senzaContentLength_corpoNelLimite_unParserJsonLoLeggeDalloStreamRiletto() throws Exception {
        // È il modo in cui lo legge Spring MVC: Jackson direttamente dallo stream della richiesta
        byte[] corpo = "{\"nome\":\"è\",\"tappe\":[]}".getBytes(StandardCharsets.UTF_8);
        RichiestaDiProva richiesta = new RichiestaDiProva(corpo, SENZA_CONTENT_LENGTH);

        filtro.doFilter(richiesta, risposta, catena);

        ArgumentCaptor<ServletRequest> aValle = ArgumentCaptor.forClass(ServletRequest.class);
        verify(catena).doFilter(aValle.capture(), any());
        JsonNode letto = mapper.readTree(aValle.getValue().getInputStream());
        assertThat(letto.get("nome").asString()).isEqualTo("è");
        assertThat(letto.get("tappe").isArray()).isTrue();
    }

    @Test
    void richiestaSenzaCorpo_prosegueConUnCorpoVuoto() throws Exception {
        RichiestaDiProva richiesta = new RichiestaDiProva(new byte[0], SENZA_CONTENT_LENGTH);

        filtro.doFilter(richiesta, risposta, catena);

        ArgumentCaptor<ServletRequest> aValle = ArgumentCaptor.forClass(ServletRequest.class);
        verify(catena).doFilter(aValle.capture(), any());
        assertThat(aValle.getValue().getInputStream().readAllBytes()).isEmpty();
    }
}
