package com.hoop3x3.backend.services;

import com.hoop3x3.backend.exceptions.BadRequestException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Conversione tra i JsonNode dei DTO e le stringhe JSON salvate nelle colonne JSONB.
 * Verifica anche la forma minima (array/oggetto) così in tabella non finiscono scalari a caso,
 * e che ogni blocco non superi 1 MB: un blocco enorme verrebbe scritto in tabella e riletto a ogni apertura.
 */
@Component
public class JsonSupport {

    /** Tetto di ogni blocco JSON (squadre, partite, video...) in megabyte; il confronto è sui byte UTF-8 */
    private static final int BLOCCO_MAX_MB = 1;

    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Array obbligatorio (squadre, partite, video); null o assente → "[]" */
    public String arrayOrEmpty(JsonNode node, String campo) {
        if (node == null || node.isNull()) return "[]";
        if (!node.isArray()) throw new BadRequestException("Il campo '" + campo + "' deve essere un array");
        return serializza(node, campo);
    }

    /** Array opzionale (gironi, bracket): null resta null */
    public String arrayOrNull(JsonNode node, String campo) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) throw new BadRequestException("Il campo '" + campo + "' deve essere un array o null");
        return serializza(node, campo);
    }

    public String objectOrFail(JsonNode node, String campo) {
        if (node == null || !node.isObject()) throw new BadRequestException("Il campo '" + campo + "' deve essere un oggetto");
        return serializza(node, campo);
    }

    public JsonNode parse(String json) {
        if (json == null) return null;
        return mapper.readTree(json);
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** JSON del blocco, o 400 se pesa più di BLOCCO_MAX_MB: si misurano i byte UTF-8, che sono ciò che occupa in tabella */
    private static String serializza(JsonNode node, String campo) {
        String testo = node.toString();
        if (testo.getBytes(StandardCharsets.UTF_8).length > BLOCCO_MAX_MB * 1024 * 1024) {
            throw new BadRequestException("Il campo '" + campo + "' supera il limite di " + BLOCCO_MAX_MB + " MB");
        }
        return testo;
    }
}
