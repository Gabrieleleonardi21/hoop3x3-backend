package com.hoop3x3.backend.security;

import io.jsonwebtoken.io.AbstractDeserializer;
import io.jsonwebtoken.io.AbstractSerializer;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.Serializer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.io.Reader;
import java.util.Map;

/**
 * Il JSON di intestazione e claims dei JWT, scritto e letto con Jackson 3. JJWT non legge JSON da sé: usa il modulo che trova
 * nel classpath, e quello pronto (jjwt-jackson) è scritto per Jackson 2, che si porterebbe in un'applicazione che usa
 * Jackson 3. Qui lo si dice a JJWT in modo esplicito, con {@code JwtBuilder.json(...)} e {@code JwtParserBuilder.json(...)}: a JJWT
 * servono solo le due interfacce, e AbstractSerializer e AbstractDeserializer fanno il resto (la conversione degli errori in
 * eccezioni di JJWT, il passaggio da byte a testo).
 * <p>
 * Il mapper è privato e fisso, come quello di jjwt-jackson, e non è il JsonMapper di Spring: quello lo configurano le proprietà
 * {@code spring.jackson.*} (anche da una variabile d'ambiente) e i moduli e i customizer che si aggiungeranno per l'API, e
 * ciò che serve all'API non deve entrare nei token. Il formato dei JWT e le regole con cui si leggono (l'intestazione si legge
 * prima di controllare la firma) restano quelli di qui, qualunque cosa cambi nella configurazione JSON del resto dell'applicazione.
 */
final class JwtJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Scrive intestazione e claims. Lo stream è di JJWT, che lo chiude da sé: Jackson non deve farlo prima */
    static final Serializer<Map<String, ?>> SERIALIZZATORE = serializzatore();

    /**
     * Legge intestazione e claims come mappa. Una chiave ripetuta ({"sub":"a","sub":"b"}) è un errore e non vince l'ultima: due
     * lettori che scelgono in modo diverso vedrebbero due token diversi (è la regola che jjwt-jackson applica dalla 0.12.4).
     */
    static final Deserializer<Map<String, ?>> DESERIALIZZATORE = deserializzatore();

    private JwtJson() {
    }

    private static Serializer<Map<String, ?>> serializzatore() {
        ObjectWriter scrittore = MAPPER.writer().without(StreamWriteFeature.AUTO_CLOSE_TARGET);
        return new AbstractSerializer<Map<String, ?>>() {
            @Override
            protected void doSerialize(Map<String, ?> contenuto, OutputStream out) {
                scrittore.writeValue(out, contenuto);
            }
        };
    }

    private static Deserializer<Map<String, ?>> deserializzatore() {
        ObjectReader lettore = MAPPER.readerFor(Map.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        return new AbstractDeserializer<Map<String, ?>>() {
            @Override
            protected Map<String, ?> doDeserialize(Reader reader) {
                return lettore.readValue(reader);
            }
        };
    }
}
