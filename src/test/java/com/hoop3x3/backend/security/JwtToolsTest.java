package com.hoop3x3.backend.security;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.UnauthorizedException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Senza contesto Spring: JwtTools si costruisce con un JwtProperties scritto a mano. Si verificano la durata del token, il
 * formato di ciò che emette e che cosa respinge quando il token è alterato o il suo JSON è rotto: il JSON lo gestisce JwtJson
 * (Jackson 3, con un mapper suo), e questi test provano che firma e lettura dei token funzionano con lui.
 */
class JwtToolsTest {

    private static final String SEGRETO = "segreto-di-prova-di-almeno-32-caratteri";
    // Solo per leggere nei test le parti già decodificate di un token: il JSON dei token lo gestisce JwtJson
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAPPA = new TypeReference<>() {};
    private static final String INTESTAZIONE = "{\"alg\":\"HS256\"}";
    private static final String PAYLOAD_VALIDO = "{\"sub\":\"x\",\"exp\":4102444800}"; // scade nel 2100

    /**
     * Un token come lo emetteva JwtTools con jjwt-jackson (Jackson 2), generato con questo segreto prima del cambio: soggetto
     * 11111111-2222-4333-8444-555555555555, emesso il 14/11/2023 (1700000000) e valido fino al 1/1/2100 (4102444800).
     * Cambiare la libreria del JSON non deve far uscire gli utenti che hanno già un token.
     */
    private static final String TOKEN_DEL_VECCHIO_STACK = "eyJhbGciOiJIUzI1NiJ9"
            + ".eyJzdWIiOiIxMTExMTExMS0yMjIyLTQzMzMtODQ0NC01NTU1NTU1NTU1NTUiLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6NDEwMjQ0NDgwMH0"
            + ".5tagJ9w8r6UuoGjbyeh2mvBcdK7urwkJcKC64qma_aw";

    private final UUID id = UUID.randomUUID();
    private final Utente utente = new Utente("mario@test.it", "hash", "Mario", Ruolo.USER);
    private final JwtTools jwtTools = new JwtTools(new JwtProperties(SEGRETO, 30));

    @BeforeEach
    void setUp() {
        // L'id lo assegna JPA al salvataggio e l'entità non ha il setter: qui si imposta a mano
        ReflectionTestUtils.setField(utente, "id", id);
    }

    @Test
    void ilTokenScadeDopoIMinutiConfigurati() {
        Claims claims = jwtTools.verifyToken(jwtTools.generateToken(utente));

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        // iat ed exp nel JWT sono in secondi, e il token li calcola dallo stesso istante: la differenza è esatta
        Duration durata = Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
        assertThat(durata).isEqualTo(Duration.ofMinutes(30));
    }

    // Emissione e scadenza fanno il giro completo (scritte da JwtJson, rilette da JwtJson) e tornano gli istanti giusti
    @Test
    void ilTokenRiportaSoggettoEmissioneEScadenza() {
        long prima = System.currentTimeMillis();
        String token = jwtTools.generateToken(utente);
        long dopo = System.currentTimeMillis();

        Claims claims = jwtTools.verifyToken(token);

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        // Nel JWT l'istante è in secondi: l'emissione è quella del momento della chiamata, troncata al secondo
        assertThat(claims.getIssuedAt().getTime()).isBetween(prima / 1000 * 1000, dopo);
        assertThat(claims.getExpiration().getTime()).isEqualTo(claims.getIssuedAt().getTime() + 30 * 60 * 1000);
    }

    // Il formato sul filo è quello di sempre: intestazione con il solo algoritmo, e nei claims soggetto, emissione e scadenza
    // come numeri in secondi (non date scritte per esteso, che un client e un vecchio server non leggerebbero)
    @Test
    void ilTokenEmessoHaIlFormatoDiSempre() {
        String[] parti = jwtTools.generateToken(utente).split("\\.");

        Map<String, Object> intestazione = MAPPER.readValue(decodifica(parti[0]), MAPPA);
        Map<String, Object> claims = MAPPER.readValue(decodifica(parti[1]), MAPPA);

        assertThat(parti).hasSize(3);
        assertThat(intestazione).containsExactly(Map.entry("alg", "HS256"));
        assertThat(claims.keySet()).containsExactly("sub", "iat", "exp");
        assertThat(claims.get("sub")).isEqualTo(id.toString());
        assertThat(claims.get("iat")).isInstanceOf(Number.class);
        assertThat(claims.get("exp")).isInstanceOf(Number.class);
        assertThat(((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue()).isEqualTo(30 * 60);
    }

    // I token già in circolazione al momento del cambio di libreria restano validi
    @Test
    void unTokenEmessoDalVecchioStackSiAccetta() {
        Claims claims = jwtTools.verifyToken(TOKEN_DEL_VECCHIO_STACK);

        assertThat(claims.getSubject()).isEqualTo("11111111-2222-4333-8444-555555555555");
        assertThat(claims.getIssuedAt()).isEqualTo(new Date(1_700_000_000_000L));
        assertThat(claims.getExpiration()).isEqualTo(new Date(4_102_444_800_000L));
    }

    // La prova contraria: lo stesso token con un carattere della firma cambiato non si accetta, quindi quello di sopra passa
    // perché la firma torna e non perché la verifica sia aperta. Si cambia il primo carattere: l'ultimo di una firma di 32 byte
    // porta solo bit di riempimento e lo stesso valore si può scrivere in più modi
    @Test
    void unTokenDelVecchioStackConLaFirmaAlterataVieneRespinto() {
        int inizioFirma = TOKEN_DEL_VECCHIO_STACK.lastIndexOf('.') + 1;
        char primo = TOKEN_DEL_VECCHIO_STACK.charAt(inizioFirma);
        char altro = 'A';
        if (primo == 'A') altro = 'B';
        String alterato = TOKEN_DEL_VECCHIO_STACK.substring(0, inizioFirma) + altro
                + TOKEN_DEL_VECCHIO_STACK.substring(inizioFirma + 1);

        assertThat(alterato).isNotEqualTo(TOKEN_DEL_VECCHIO_STACK);
        assertThatThrownBy(() -> jwtTools.verifyToken(alterato))
                .isInstanceOf(UnauthorizedException.class).hasMessage(JwtTools.TOKEN_NON_VALIDO);
    }

    @Test
    void unTokenScadutoVieneRespinto() {
        // scade un minuto prima di essere emesso: il record costruito a mano non passa dalla validazione
        JwtTools jwtScaduto = new JwtTools(new JwtProperties(SEGRETO, -1));

        String scaduto = jwtScaduto.generateToken(utente);

        assertThatThrownBy(() -> jwtScaduto.verifyToken(scaduto)).isInstanceOf(UnauthorizedException.class);
    }

    // Un token nullo, vuoto o di soli spazi (per esempio da «Authorization: Bearer »): JJWT lancia IllegalArgumentException
    // e non una JwtException, quindi senza un controllo esplicito diventava un errore 500 invece di un 401
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   "})
    void unTokenVuotoODiSoliSpaziVieneRespinto(String token) {
        assertThatThrownBy(() -> jwtTools.verifyToken(token)).isInstanceOf(UnauthorizedException.class);
    }

    /* ── JSON rotto in un token con la firma giusta: la firma non basta, il contenuto deve essere JSON valido ── */

    // Prova del controllo qui sotto: la firma di firmato() è quella vera, quindi un rifiuto viene dal contenuto e non dalla firma
    @Test
    void unTokenFirmatoConUnContenutoValidoSiAccetta() throws Exception {
        Claims claims = jwtTools.verifyToken(firmato(INTESTAZIONE, PAYLOAD_VALIDO));

        assertThat(claims.getSubject()).isEqualTo("x");
    }

    // Tutti questi payload hanno la firma giusta. JJWT non riesce a leggerli come claims e li tratta come un contenuto
    // qualsiasi, che parseSignedClaims rifiuta: in ogni caso il risultato è il 401 e mai un errore 500
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"sub\":\"x\",}",                                  // virgola in più
            "{\"sub\":\"x\",\"exp\":}",                          // valore mancante
            "{\"sub\":\"x\" \"exp\":4102444800}",                // virgola mancante
            "{\"sub\":\"a\",\"sub\":\"b\",\"exp\":4102444800}",  // chiave ripetuta: un lettore sceglierebbe «a», un altro «b»
            "{\"sub\":\"x\",\"exp\":4102444800} xyz",          // testo dopo l'oggetto: il vecchio stack (Jackson 2) lo accettava
            "[\"x\"]",                                           // non è un oggetto
            "testo qualsiasi"})
    void unPayloadFirmatoMaNonJsonValidoVieneRespinto(String payload) throws Exception {
        String token = firmato(INTESTAZIONE, payload);

        assertThatThrownBy(() -> jwtTools.verifyToken(token))
                .isInstanceOf(UnauthorizedException.class).hasMessage(JwtTools.TOKEN_NON_VALIDO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{alg}",                                    // non è JSON
            "{\"alg\":\"HS256\",\"alg\":\"HS256\"}",    // chiave ripetuta
            "[]",                                       // non è un oggetto
            "null",
            "{\"alg\":\"none\"}",                       // token senza firma: JJWT non lo accetta se non glielo si chiede
            "{\"alg\":\"HS512\"}"})                     // un algoritmo diverso da quello del segreto: la firma non può tornare
    void unaIntestazioneFirmataMaNonValidaVieneRespinta(String intestazione) throws Exception {
        String token = firmato(intestazione, PAYLOAD_VALIDO);

        assertThatThrownBy(() -> jwtTools.verifyToken(token))
                .isInstanceOf(UnauthorizedException.class).hasMessage(JwtTools.TOKEN_NON_VALIDO);
    }

    /** Il token compatto con la firma HMAC-SHA256 giusta (la stessa chiave di JwtTools) su intestazione e payload dati */
    private static String firmato(String intestazione, String payload) throws GeneralSecurityException {
        String daFirmare = codifica(intestazione) + "." + codifica(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SEGRETO.getBytes(UTF_8), "HmacSHA256"));
        byte[] firma = mac.doFinal(daFirmare.getBytes(UTF_8));
        return daFirmare + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(firma);
    }

    private static String codifica(String testo) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(testo.getBytes(UTF_8));
    }

    private static String decodifica(String parte) {
        return new String(Base64.getUrlDecoder().decode(parte), UTF_8);
    }
}
