package com.hoop3x3.backend.services;

import com.hoop3x3.backend.exceptions.BadRequestException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Conversione tra i JsonNode dei DTO e le stringhe JSON salvate nelle colonne JSONB.
 * Verifica anche la forma minima (array/oggetto) così in tabella non finiscono scalari a caso.
 */
@Component
public class JsonSupport {

    private final ObjectMapper mapper;

    public JsonSupport(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Array obbligatorio (squadre, partite, video); null o assente → "[]" */
    public String arrayOrEmpty(JsonNode node, String campo) {
        if (node == null || node.isNull()) return "[]";
        if (!node.isArray()) throw new BadRequestException("Il campo '" + campo + "' deve essere un array");
        return node.toString();
    }

    /** Array opzionale (gironi, bracket): null resta null */
    public String arrayOrNull(JsonNode node, String campo) {
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) throw new BadRequestException("Il campo '" + campo + "' deve essere un array o null");
        return node.toString();
    }

    public String objectOrFail(JsonNode node, String campo) {
        if (node == null || !node.isObject()) throw new BadRequestException("Il campo '" + campo + "' deve essere un oggetto");
        return node.toString();
    }

    public JsonNode parse(String json) {
        if (json == null) return null;
        return mapper.readTree(json);
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }
}
