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

    /**
     * Il testo da salvare per un blocco: `attuale` (quello già nella colonna) se `nuovo` ha lo stesso contenuto JSON, altrimenti
     * `nuovo`. I blocchi sono stringhe, e PostgreSQL riscrive il JSONB con degli spazi dopo i due punti e le virgole e con le chiavi
     * in un altro ordine: confrontate come testo, due versioni dello stesso blocco risultano diverse, Hibernate scrive un UPDATE e
     * la versione della tappa sale anche se non è cambiato niente (e un altro dispositivo riceve un 409 per niente). Si confrontano
     * quindi i valori JSON, e se sono uguali resta il testo che c'è già, così Hibernate non vede nessun cambiamento.
     */
    public String testoDaSalvare(String attuale, String nuovo) {
        if (attuale == null || nuovo == null) return nuovo;
        if (attuale.equals(nuovo) || parse(attuale).equals(parse(nuovo))) return attuale;
        return nuovo;
    }

    public JsonNode parse(String json) {
        if (json == null) return null;
        return mapper.readTree(json);
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
