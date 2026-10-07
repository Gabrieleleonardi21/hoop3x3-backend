package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.RegoleDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * La tappa dei test: l'unico punto dove si chiama il costruttore di TappaDTO, che ha un argomento per campo. Un campo nuovo
 * del DTO si aggiunge qui e non in ogni test che costruisce una tappa. Parte da una tappa valida di Roma, non conclusa,
 * con un id nuovo e senza squadre, gironi, partite, bracket né video: ogni test cambia solo ciò che gli serve.
 */
public final class TappaDiProva {

    private UUID id = UUID.randomUUID();
    private String nome = "Tappa";
    private String luogo = "Roma";
    private String data = "2026-06-14";
    private String squadre = "[]";
    private String gironi = null; // come una tappa i cui gironi non sono ancora stati sorteggiati
    private String partite = "[]";
    private String bracket = null; // come una tappa senza fase finale
    private String video = "[]";
    private boolean conclusa = false;
    private Long versione = null; // come una tappa che il client non ha ancora letto dal server

    private TappaDiProva() {}

    public static TappaDiProva tappa() {
        return new TappaDiProva();
    }

    /**
     * Parte da un'altra tappa, per esempio quella che il client ha letto: stessi id, nome, luogo, data, squadre, gironi,
     * partite, bracket, video, conclusa e versione, e il test cambia quelli che gli servono. Gli altri campi non sono nel builder
     * e tornano ai valori fissi di build(): nGironi e regole. Una tappa che li usa non si copia con questo metodo.
     */
    public static TappaDiProva da(TappaDTO modello) {
        return tappa().id(modello.id()).nome(modello.nome()).luogo(modello.luogo()).data(modello.data())
                .squadre(modello.squadre().toString()).gironi(testo(modello.gironi())).partite(modello.partite().toString())
                .bracket(testo(modello.bracket())).video(testo(modello.video()))
                .conclusa(Boolean.TRUE.equals(modello.conclusa())).versione(modello.versione());
    }

    /** Il JSON di un blocco opzionale, o null se il blocco manca */
    private static String testo(JsonNode blocco) {
        if (blocco == null) return null;
        return blocco.toString();
    }

    public TappaDiProva id(UUID id) {
        this.id = id;
        return this;
    }

    public TappaDiProva nome(String nome) {
        this.nome = nome;
        return this;
    }

    public TappaDiProva luogo(String luogo) {
        this.luogo = luogo;
        return this;
    }

    public TappaDiProva data(String data) {
        this.data = data;
        return this;
    }

    /** I blocchi di gioco si scrivono come JSON, come li manda il frontend */
    public TappaDiProva squadre(String json) {
        this.squadre = json;
        return this;
    }

    /** null: i gironi non sono ancora stati sorteggiati */
    public TappaDiProva gironi(String json) {
        this.gironi = json;
        return this;
    }

    public TappaDiProva partite(String json) {
        this.partite = json;
        return this;
    }

    /** null: la fase finale non è stata generata */
    public TappaDiProva bracket(String json) {
        this.bracket = json;
        return this;
    }

    public TappaDiProva video(String json) {
        this.video = json;
        return this;
    }

    public TappaDiProva conclusa(boolean conclusa) {
        this.conclusa = conclusa;
        return this;
    }

    /** La versione che il client ha letto e rimanda con la PUT; null: il client non la manda */
    public TappaDiProva versione(Long versione) {
        this.versione = versione;
        return this;
    }

    public TappaDTO build() {
        // La lettura di una stringa JSON non dipende dalla configurazione del mapper: basta quello condiviso di Jackson
        JsonMapper json = JsonMapper.shared();
        return new TappaDTO(id, nome, luogo, data, 1, new RegoleDTO(21, 10, 2, 12),
                json.readTree(squadre), opzionale(json, gironi), json.readTree(partite), json.readTree(video), conclusa,
                opzionale(json, bracket), versione);
    }

    /** Un blocco che può mancare: null resta null */
    private static JsonNode opzionale(JsonMapper json, String blocco) {
        if (blocco == null) return null;
        return json.readTree(blocco);
    }
}
