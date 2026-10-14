package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.BozzaFoglioDTO;
import com.hoop3x3.backend.dto.BozzaFoglioDTO.PartitaLetta;
import com.hoop3x3.backend.dto.BozzaFoglioDTO.SquadraLetta;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.UpstreamException;
import com.hoop3x3.backend.support.Testo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hoop3x3.backend.services.LogSupport.perLog;

/**
 * Lettura della foto di un foglio di tappa: controlla l'immagine, la passa al lettore scelto (ai.lettore-foglio) e ripulisce
 * la bozza secondo i limiti del contratto (README, «Lettura dei fogli (foto)»). Il server non decide niente della lega: la
 * bozza la trasforma e la importa il frontend.
 * <p>
 * L'immagine non si salva e non si logga: vive per la durata della richiesta. Nei log una riga per lettura, con l'id
 * dell'utente, la dimensione, la durata e i numeri della bozza, mai i nomi letti.
 */
@Service
@EnableConfigurationProperties(AiProperties.class)
public class FoglioService {

    private static final Logger log = LoggerFactory.getLogger(FoglioService.class);

    /* I messaggi sono un contratto con il frontend */
    public static final String FORMATO_NON_VALIDO = "L'immagine deve essere un data URL JPEG, PNG o WebP";
    public static final String TROPPO_GRANDE = "L'immagine supera 1,5 MB: riducila prima di mandarla";
    public static final String NON_CONFIGURATO = "Lettura dei fogli non configurata sul server (AI_LETTORE_FOGLIO)";
    /** Il tetto dei caratteri di base64 (circa 1,1 MB di foto): il frontend riduce la foto prima di mandarla */
    public static final int MAX_BASE64 = 1_500_000;

    /** Il prefisso del data URL: il tipo dichiarato dal client, che poi si confronta con i primi byte */
    private static final Pattern PREFISSO = Pattern.compile("data:(image/(?:jpeg|png|webp));base64,");
    private static final byte[] FIRMA_JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] FIRMA_PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final byte[] FIRMA_RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] FIRMA_WEBP = {'W', 'E', 'B', 'P'};

    /* I limiti della bozza (contratto): nessun dato fuori misura arriva al frontend, qualunque cosa scriva il modello */
    private static final int MAX_NOME = 120;
    private static final int MAX_LUOGO = 160;
    private static final int MAX_DUBBIO = 300;
    private static final int MAX_SQUADRE = 64;
    private static final int MAX_GIOCATORI = 6;
    private static final int MAX_GIRONI = 32;
    private static final int MAX_PARTITE = 500;
    private static final int MAX_DUBBI = 50;
    private static final int MAX_PUNTI = 999;
    private static final Pattern DATA_ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final String DATA_NON_LETTA = "Data non letta";

    /** Il lettore scelto, o null se la lettura è spenta (ai.lettore-foglio vuoto o sconosciuto) */
    private final LettoreFoglio lettore;

    @Autowired // con due costruttori Spring deve sapere quale usare
    public FoglioService(AiProperties proprieta, LettoreFoglioGroq groq, LettoreFoglioAnthropic anthropic) {
        this(scegli(proprieta.lettoreFoglio(), groq, anthropic));
    }

    /** Per i test: un lettore finto, o null per la lettura spenta */
    FoglioService(LettoreFoglio lettore) {
        this.lettore = lettore;
    }

    private static LettoreFoglio scegli(String nome, LettoreFoglioGroq groq, LettoreFoglioAnthropic anthropic) {
        String scelto = Testo.ripulito(nome).toLowerCase(Locale.ROOT);
        if (scelto.equals("groq")) return groq;
        if (scelto.equals("anthropic")) return anthropic;
        // Un nome sbagliato non ferma il server (la lettura è facoltativa), ma lo dice all'avvio
        if (!scelto.isEmpty()) log.warn("ai.lettore-foglio vale «{}», né groq né anthropic: lettura dei fogli spenta", perLog(scelto));
        return null;
    }

    /** C'è un lettore e ha la sua chiave */
    public boolean isConfigurato() {
        return lettore != null && lettore.isConfigurato();
    }

    public BozzaFoglioDTO leggi(Utente utente, String dataUrl) {
        if (!isConfigurato()) {
            log.warn("Lettura di un foglio chiesta ma ai.lettore-foglio o la chiave del fornitore non sono configurati: risposta 503");
            throw new UpstreamException(HttpStatus.SERVICE_UNAVAILABLE, NON_CONFIGURATO);
        }
        ImmagineFoglio immagine = immagine(dataUrl);
        long inizio = System.nanoTime();
        BozzaFoglioDTO bozza = ripulisci(lettore.leggi(immagine));
        long millisecondi = (System.nanoTime() - inizio) / 1_000_000;
        log.info("Foglio letto: utente {}, {} KB, {} ms, {} squadre, {} partite, {} dubbi", utente.getId(),
                (immagine.dati().length + 1023) / 1024, millisecondi, bozza.squadre().size(), bozza.partite().size(),
                bozza.dubbi().size());
        return bozza;
    }

    /**
     * Il data URL controllato e decodificato. Il tetto si misura sul base64, prima di decodificare; il tipo si controlla sui
     * primi byte, perché il prefisso lo sceglie il client. Nessun messaggio riporta il contenuto
     */
    private static ImmagineFoglio immagine(String dataUrl) {
        Matcher prefisso = PREFISSO.matcher(dataUrl);
        if (!prefisso.lookingAt()) throw new BadRequestException(FORMATO_NON_VALIDO);
        String base64 = dataUrl.substring(prefisso.end());
        if (base64.length() > MAX_BASE64) throw new BadRequestException(TROPPO_GRANDE);
        byte[] dati;
        try {
            dati = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException _) {
            throw new BadRequestException(FORMATO_NON_VALIDO);
        }
        String mime = prefisso.group(1);
        if (!haLaFirmaDi(mime, dati)) throw new BadRequestException(FORMATO_NON_VALIDO);
        return new ImmagineFoglio(dati, mime);
    }

    /** I primi byte sono quelli del tipo dichiarato: JPEG FF D8 FF, PNG 89 50 4E 47 0D 0A 1A 0A, WebP RIFF....WEBP */
    private static boolean haLaFirmaDi(String mime, byte[] dati) {
        return switch (mime) {
            case "image/jpeg" -> iniziaCon(dati, 0, FIRMA_JPEG);
            case "image/png" -> iniziaCon(dati, 0, FIRMA_PNG);
            case "image/webp" -> iniziaCon(dati, 0, FIRMA_RIFF) && iniziaCon(dati, 8, FIRMA_WEBP);
            default -> false;
        };
    }

    private static boolean iniziaCon(byte[] dati, int da, byte[] firma) {
        if (dati.length < da + firma.length) return false;
        return Arrays.equals(dati, da, da + firma.length, firma, 0, firma.length);
    }

    /**
     * La bozza del modello entro i limiti del contratto: testi senza spazi ai lati e troncati, elementi senza nome scartati,
     * elenchi tagliati al tetto, punteggi solo interi da 0 a 999. Squadre dei gironi o delle partite che non sono nell'elenco
     * restano: le risolve il frontend. Senza la forma minima (squadre, partite e dubbi elenchi) è un 502, mai un 500
     */
    static BozzaFoglioDTO ripulisci(JsonNode bozza) {
        if (!SchemaFoglio.haLaForma(bozza)) {
            throw new UpstreamException(HttpStatus.BAD_GATEWAY, LettoreFoglio.RISPOSTA_NON_VALIDA);
        }
        List<String> dubbi = new ArrayList<>();
        String data = dataIso(bozza.path("data"), dubbi);

        List<SquadraLetta> squadre = new ArrayList<>();
        for (JsonNode squadra : bozza.path("squadre")) {
            if (squadre.size() == MAX_SQUADRE) break;
            String nome = testo(squadra.path("nome"), MAX_NOME);
            if (nome != null) squadre.add(new SquadraLetta(nome, testi(squadra.path("giocatori"), MAX_NOME, MAX_GIOCATORI)));
        }

        List<List<String>> gironi = null;
        if (bozza.path("gironi").isArray()) {
            gironi = new ArrayList<>();
            for (JsonNode girone : bozza.path("gironi")) {
                if (gironi.size() == MAX_GIRONI) break;
                if (girone.isArray()) gironi.add(testi(girone, MAX_NOME, MAX_SQUADRE));
            }
        }

        List<PartitaLetta> partite = new ArrayList<>();
        for (JsonNode partita : bozza.path("partite")) {
            if (partite.size() == MAX_PARTITE) break;
            String a = testo(partita.path("a"), MAX_NOME);
            String b = testo(partita.path("b"), MAX_NOME);
            // Senza una delle due squadre la partita non si può importare: sparisce come una squadra senza nome
            if (a != null && b != null) partite.add(new PartitaLetta(a, b, punti(partita.path("puntiA")), punti(partita.path("puntiB"))));
        }

        dubbi.addAll(testi(bozza.path("dubbi"), MAX_DUBBIO, MAX_DUBBI - dubbi.size()));
        return new BozzaFoglioDTO(testo(bozza.path("nome"), MAX_NOME), testo(bozza.path("luogo"), MAX_LUOGO), data, squadre,
                gironi, partite, dubbi);
    }

    /**
     * La data se è aaaa-mm-gg e una data vera. Null o assente resta null; qualunque altra cosa (scritta in un altro modo, un
     * numero, un oggetto, un testo vuoto) diventa null con il dubbio «Data non letta»
     */
    private static String dataIso(JsonNode nodo, List<String> dubbi) {
        if (nodo.isNull() || nodo.isMissingNode()) return null;
        String data = testo(nodo, Integer.MAX_VALUE);
        if (data != null && DATA_ISO.matcher(data).matches()) {
            try {
                LocalDate.parse(data);
                return data;
            } catch (DateTimeParseException _) {
                // 2026-02-30: la forma è giusta ma la data non esiste, come una data scritta in un altro modo
            }
        }
        dubbi.add(DATA_NON_LETTA);
        return null;
    }

    /** I testi di un elenco, ripuliti, al massimo `quanti`: ciò che non è un testo o è vuoto sparisce */
    private static List<String> testi(JsonNode elenco, int lunghezza, int quanti) {
        List<String> testi = new ArrayList<>();
        for (JsonNode voce : elenco) {
            if (testi.size() >= quanti) break;
            String testo = testo(voce, lunghezza);
            if (testo != null) testi.add(testo);
        }
        return testi;
    }

    /** Un testo senza spazi ai lati, troncato a `lunghezza`; null se non è un testo o è vuoto */
    private static String testo(JsonNode nodo, int lunghezza) {
        if (!nodo.isString()) return null;
        String testo = nodo.asString().trim();
        if (testo.isEmpty()) return null;
        if (testo.length() <= lunghezza) return testo;
        int fine = lunghezza;
        // Non si taglia a metà un carattere fuori dal piano base (un'emoji): resterebbe mezzo carattere
        if (Character.isHighSurrogate(testo.charAt(fine - 1))) fine--;
        return testo.substring(0, fine);
    }

    /** Un punteggio intero da 0 a 999; qualunque altra cosa (decimale, testo, fuori scala) diventa null */
    private static Integer punti(JsonNode nodo) {
        if (!nodo.isIntegralNumber() || !nodo.canConvertToInt()) return null;
        int punti = nodo.intValue();
        if (punti < 0 || punti > MAX_PUNTI) return null;
        return punti;
    }
}
