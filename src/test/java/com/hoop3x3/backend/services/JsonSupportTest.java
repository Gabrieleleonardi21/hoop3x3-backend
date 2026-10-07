package com.hoop3x3.backend.services;

import com.hoop3x3.backend.exceptions.BadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JsonSupport da solo, con un ObjectMapper vero: nessun contesto Spring e nessun database. */
class JsonSupportTest {

    private static final int UN_MB = 1024 * 1024;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final JsonSupport json = new JsonSupport(mapper);

    /** Array con un solo testo, lungo in JSON esattamente `byte` byte: la cornice [""] ne occupa 4 */
    private JsonNode arrayDi(int byteTotali) {
        return mapper.createArrayNode().add("x".repeat(byteTotali - 4));
    }

    /** Oggetto con un solo testo, lungo in JSON esattamente `byte` byte: la cornice {"k":""} ne occupa 8 */
    private JsonNode oggettoDi(int byteTotali) {
        return mapper.createObjectNode().put("k", "x".repeat(byteTotali - 8));
    }

    @Test
    void arrayObbligatoreOltre1Mb_risponde400NominandoIlCampo() {
        assertThatThrownBy(() -> json.arrayOrEmpty(arrayDi(UN_MB + 1), "partite"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'partite'")
                .hasMessageContaining("1 MB");
    }

    @Test
    void arrayOpzionaleOltre1Mb_risponde400NominandoIlCampo() {
        assertThatThrownBy(() -> json.arrayOrNull(arrayDi(UN_MB + 1), "bracket"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'bracket'")
                .hasMessageContaining("1 MB");
    }

    @Test
    void oggettoOltre1Mb_risponde400NominandoIlCampo() {
        assertThatThrownBy(() -> json.objectOrFail(oggettoDi(UN_MB + 1), "regole"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'regole'")
                .hasMessageContaining("1 MB");
    }

    @Test
    void bloccoDiEsattamente1Mb_siAccetta() {
        assertThat(json.arrayOrEmpty(arrayDi(UN_MB), "partite")).hasSize(UN_MB);
        assertThat(json.arrayOrNull(arrayDi(UN_MB), "bracket")).hasSize(UN_MB);
        assertThat(json.objectOrFail(oggettoDi(UN_MB), "regole")).hasSize(UN_MB);
    }

    @Test
    void ilLimiteSiMisuraInByteNonInCaratteri() {
        // 600.000 lettere accentate sono meno di 1 milione di caratteri ma 1,2 MB in UTF-8, quanto occupano in tabella
        JsonNode accentate = mapper.createArrayNode().add("è".repeat(600_000));
        assertThat(accentate.toString().length()).isLessThan(UN_MB);
        assertThat(accentate.toString().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(UN_MB);

        assertThatThrownBy(() -> json.arrayOrEmpty(accentate, "squadre"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("'squadre'");
    }

    /* ── testoDaSalvare: un blocco con lo stesso contenuto resta quello già salvato ── */

    // Il blocco come lo riscrive PostgreSQL (spazi dopo i due punti e le virgole, chiavi dalla più corta) e come lo manda il client
    private static final String DAL_DATABASE = "[{\"a\": \"s1\", \"b\": \"s2\", \"id\": \"m1\", \"sa\": 21}]";
    private static final String DAL_CLIENT = "[{\"id\":\"m1\",\"a\":\"s1\",\"b\":\"s2\",\"sa\":21}]";

    @Test
    void unBloccoConLoStessoContenutoMaScrittoDiversamente_restaQuelloGiaSalvato() {
        assertThat(json.testoDaSalvare(DAL_DATABASE, DAL_CLIENT)).isEqualTo(DAL_DATABASE);
        assertThat(json.testoDaSalvare(DAL_DATABASE, DAL_DATABASE)).isEqualTo(DAL_DATABASE);
    }

    @Test
    void unBloccoCambiato_siSalvaIlNuovo() {
        String punteggioCambiato = DAL_CLIENT.replace("\"sa\":21", "\"sa\":22");
        String altriElementi = "[{\"id\":\"m1\"},{\"id\":\"m2\"}]";
        String altroOrdine = "[{\"id\":\"m2\"},{\"id\":\"m1\"}]";

        assertThat(json.testoDaSalvare(DAL_DATABASE, punteggioCambiato)).isEqualTo(punteggioCambiato);
        // L'ordine degli elementi di un array è contenuto, quello delle chiavi di un oggetto no
        assertThat(json.testoDaSalvare(altriElementi, altroOrdine)).isEqualTo(altroOrdine);
    }

    // gironi e bracket possono mancare (null): un blocco che compare o sparisce è un cambiamento, e due blocchi assenti no
    @Test
    void unBloccoCheCompareOSparisce_siSalvaIlNuovo() {
        assertThat(json.testoDaSalvare(null, DAL_CLIENT)).isEqualTo(DAL_CLIENT);
        assertThat(json.testoDaSalvare(DAL_DATABASE, null)).isNull();
        assertThat(json.testoDaSalvare(null, null)).isNull();
    }
}
