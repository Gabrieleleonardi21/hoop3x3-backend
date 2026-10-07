package com.hoop3x3.backend.security;

import io.jsonwebtoken.io.AbstractDeserializer;
import io.jsonwebtoken.io.AbstractSerializer;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.Serializer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

import java.io.OutputStream;
import java.io.Reader;
import java.util.Map;

/**
 * Il JSON di intestazione e claims dei JWT, scritto e letto con il Jackson 3 dell'applicazione. JJWT non legge JSON da sé: usa
 * il modulo che trova nel classpath, e quello pronto (jjwt-jackson) è scritto per Jackson 2, che si porterebbe in
 * un'applicazione che usa Jackson 3. Qui lo si dice a JJWT in modo esplicito, con {@code JwtBuilder.json(...)} e
 * {@code JwtParserBuilder.json(...)}: a JJWT servono solo le due interfacce, e AbstractSerializer e AbstractDeserializer
 * fanno il resto (la conversione degli errori in eccezioni di JJWT, il passaggio da byte a testo).
 */
final class JwtJson {

    private JwtJson() {
    }

    /** Scrive intestazione e claims. Lo stream è di JJWT, che lo chiude da sé: Jackson non deve farlo prima */
    static Serializer<Map<String, ?>> serializzatore(ObjectMapper mapper) {
        ObjectWriter scrittore = mapper.writer().without(StreamWriteFeature.AUTO_CLOSE_TARGET);
        return new AbstractSerializer<Map<String, ?>>() {
            @Override
            protected void doSerialize(Map<String, ?> contenuto, OutputStream out) {
                scrittore.writeValue(out, contenuto);
            }
        };
    }

    /**
     * Legge intestazione e claims come mappa. Una chiave ripetuta ({"sub":"a","sub":"b"}) è un errore e non vince l'ultima: due
     * lettori che scelgono in modo diverso vedrebbero due token diversi (è la regola che jjwt-jackson applica dalla 0.12.4).
     * Il lettore si ricava dal mapper dell'applicazione senza cambiarlo.
     */
    static Deserializer<Map<String, ?>> deserializzatore(ObjectMapper mapper) {
        ObjectReader lettore = mapper.readerFor(Map.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        return new AbstractDeserializer<Map<String, ?>>() {
            @Override
            protected Map<String, ?> doDeserialize(Reader reader) {
                return lettore.readValue(reader);
            }
        };
    }
}
