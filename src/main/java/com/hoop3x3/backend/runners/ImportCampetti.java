package com.hoop3x3.backend.runners;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.CampettoRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.CampettoService;
import com.hoop3x3.backend.services.UtenteService;
import com.hoop3x3.backend.support.Testo;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Il comando che importa i campetti di Pick-Roll (resources/import/pick-roll-README.md: fonte, permesso, formato del file).
 * Non è un endpoint: parte solo con l'argomento {@code --importa-campetti=/percorso/campetti.json} e, finito, il processo
 * termina con il suo codice d'uscita (Hoop3x3BackendApplication.main). Il file dei dati non sta nel repository.
 * <p>
 * Legge e controlla tutto il file prima di scrivere: un errore di schema (JSON malformato, non un array, un campo con il
 * tipo sbagliato) ferma tutto senza toccare il database, perché vuol dire che l'export non ha il formato concordato. Le
 * singole righe passano dalla validazione degli endpoint (CampettoRequestDTO, lo stesso Validator): una riga che non la
 * supera, o senza fonteId, o che non è un «campetto» (D10: palestre e arene restano fuori) si scarta con il motivo nel log
 * e le altre entrano. Poi scrive a blocchi, una transazione per blocco: un file da migliaia di righe non sta in una
 * transazione sola. La chiave (fonte, fonteId) ha un indice unico (V8): un import ripetuto non crea doppioni e aggiorna
 * solo le righe cambiate nell'export. I campetti importati sono intestati all'admin (ADMIN_EMAIL), quindi li modifica solo
 * un ADMIN e restano allineati alla fonte.
 */
@Slf4j
@Component
@Order(3) // dopo DataSeeder: i campetti importati si intestano all'admin, che deve esistere
@EnableConfigurationProperties(SeedProperties.class)
public class ImportCampetti implements ApplicationRunner, ExitCodeGenerator {

    /** L'argomento della riga di comando che accende l'import, con il percorso del file come valore */
    public static final String ARGOMENTO = "importa-campetti";
    /** Il valore di `fonte` dei campetti importati: con fonteId è la chiave che evita i doppioni */
    static final String FONTE = "pick-roll";
    /** Righe per transazione */
    static final int BLOCCO = 500;
    /** La colonna fonte_id è VARCHAR(120): un id più lungo si scarta prima, invece di far fallire tutto il blocco all'INSERT */
    private static final int FONTE_ID_MAX = 120;
    private static final String TIPO_AMMESSO = "campetto";

    /** Una riga dell'export, con i soli campi del campo: foto, valutazioni, recensioni, eventi e utenti si ignorano */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Riga(String fonteId, String nome, String indirizzo, String citta, Double lat, Double lng, String tipo,
                String superficie, Integer canestri, Boolean illuminato, Boolean coperto, Boolean gratuito, Boolean retine,
                Boolean linee, Boolean fontanella, String stato, String note) {}

    /** Una riga valida, pronta per il database */
    private record Candidata(String fonteId, CampettoRequestDTO dto) {}

    /** I conteggi dell'import: gli scarti sono per motivo, nell'ordine alfabetico dei motivi */
    record Esito(int inseriti, int aggiornati, Map<String, Integer> scarti) {
        int scartati() {
            return scarti.values().stream().mapToInt(Integer::intValue).sum();
        }

        String motivi() {
            return scarti.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(", "));
        }
    }

    /** Un errore atteso dell'import (file, schema, admin): si stampa il messaggio e basta, senza stack */
    private static final class ImportFallito extends RuntimeException {
        ImportFallito(String messaggio) {
            super(messaggio);
        }
    }

    private final CampettoRepository campetti;
    private final UtenteRepository utenti;
    private final Validator validator;
    private final ObjectMapper mapper;
    private final TransactionTemplate transazione;
    private final String adminEmail;
    /** 0 finché tutto va bene, 1 se l'import si è fermato: lo legge SpringApplication.exit */
    private int codiceUscita;

    public ImportCampetti(CampettoRepository campetti, UtenteRepository utenti, Validator validator, ObjectMapper mapper,
                          PlatformTransactionManager transazioni, SeedProperties proprieta) {
        this.campetti = campetti;
        this.utenti = utenti;
        this.validator = validator;
        this.mapper = mapper;
        this.transazione = new TransactionTemplate(transazioni);
        this.adminEmail = proprieta.admin().email();
    }

    /** Se la riga di comando chiede l'import: il main lo legge prima di avviare Spring */
    public static boolean richiesto(String[] args) {
        return new DefaultApplicationArguments(args).containsOption(ARGOMENTO);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(ARGOMENTO)) return;
        List<String> valori = args.getOptionValues(ARGOMENTO);
        try {
            if (valori.isEmpty() || valori.getFirst().isBlank()) {
                throw new ImportFallito("indica il file: --" + ARGOMENTO + "=/percorso/campetti.json");
            }
            Esito esito = importa(Path.of(valori.getFirst()));
            String motivi = "";
            if (esito.scartati() > 0) motivi = " (" + esito.motivi() + ")";
            log.info("Import campetti completato: inseriti {}, aggiornati {}, scartati {}{}", esito.inseriti(),
                    esito.aggiornati(), esito.scartati(), motivi);
        } catch (ImportFallito e) {
            codiceUscita = 1;
            log.error("Import campetti fallito: {}", e.getMessage());
        } catch (Exception e) {
            // Un errore non previsto (il database che cade): il messaggio e lo stack, per capire che cosa è successo
            codiceUscita = 1;
            log.error("Import campetti fallito: {}", e.getMessage(), e);
        }
    }

    @Override
    public int getExitCode() {
        return codiceUscita;
    }

    /** L'import di un file: prima tutte le righe (valide o scartate), poi la scrittura a blocchi. Lancia ImportFallito */
    Esito importa(Path file) {
        Utente admin = trovaAdmin();
        List<Candidata> candidate = new ArrayList<>();
        Map<String, Integer> scarti = new TreeMap<>();
        int indice = 0;
        for (JsonNode nodo : leggi(file)) {
            indice++;
            Riga riga = converti(nodo, indice);
            String motivo = motivoDiScarto(riga);
            if (motivo != null) {
                log.warn("Import campetti: riga {} ({}) scartata: {}", indice, etichetta(riga), motivo);
                scarti.merge(motivo, 1, Integer::sum);
                continue;
            }
            candidate.add(new Candidata(riga.fonteId().trim(), dto(riga)));
        }
        int[] conteggi = new int[2]; // inseriti, aggiornati
        for (int da = 0; da < candidate.size(); da += BLOCCO) {
            List<Candidata> blocco = candidate.subList(da, Math.min(da + BLOCCO, candidate.size()));
            transazione.executeWithoutResult(_ -> scrivi(blocco, admin, conteggi));
        }
        return new Esito(conteggi[0], conteggi[1], scarti);
    }

    /* ── Lettura e controllo ── */

    private Utente trovaAdmin() {
        if (adminEmail.isBlank()) {
            throw new ImportFallito("ADMIN_EMAIL non è impostata, e i campetti importati si intestano all'admin");
        }
        Utente admin = utenti.findByEmail(UtenteService.normalizza(adminEmail))
                .orElseThrow(() -> new ImportFallito("admin " + adminEmail + " non trovato"));
        if (!admin.isAdmin()) throw new ImportFallito(adminEmail + " è di un utente con ruolo USER, non dell'admin");
        return admin;
    }

    /** Il contenuto del file: deve esistere, essere JSON ed essere un array */
    private JsonNode leggi(Path file) {
        if (!Files.isRegularFile(file)) throw new ImportFallito("file non trovato: " + file);
        JsonNode radice;
        try {
            radice = mapper.readTree(file.toFile());
        } catch (JacksonException e) {
            throw new ImportFallito("JSON non valido in " + file + ": " + e.getOriginalMessage());
        }
        if (!radice.isArray()) throw new ImportFallito("il file deve essere un array JSON di campetti: " + file);
        return radice;
    }

    /** Una riga nella forma concordata: un elemento null o un campo con il tipo sbagliato è un errore di schema e ferma l'import */
    private Riga converti(JsonNode nodo, int indice) {
        try {
            Riga riga = mapper.treeToValue(nodo, Riga.class);
            if (riga == null) throw new ImportFallito("riga " + indice + " vuota"); // un null nell'array: treeToValue dà null
            return riga;
        } catch (JacksonException e) {
            throw new ImportFallito("riga " + indice + " non ha il formato concordato: " + e.getOriginalMessage());
        }
    }

    /** Perché la riga non entra, o null se entra: fonteId, tipo, poi la validazione degli endpoint */
    private String motivoDiScarto(Riga riga) {
        String fonteId = Testo.ripulito(riga.fonteId());
        if (fonteId.isEmpty()) return "fonteId mancante";
        if (fonteId.length() > FONTE_ID_MAX) return "fonteId oltre " + FONTE_ID_MAX + " caratteri";
        String tipo = Testo.ripulito(riga.tipo());
        if (tipo.isEmpty()) return "tipo mancante";
        if (!TIPO_AMMESSO.equals(tipo)) return "tipo " + tipo;
        var violazioni = validator.validate(dto(riga));
        if (violazioni.isEmpty()) return null;
        return violazioni.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(", "));
    }

    /** La riga nel DTO degli endpoint, già nella forma che si salva (CampettoService.applica) e con i valori predefiniti */
    private static CampettoRequestDTO dto(Riga r) {
        return new CampettoRequestDTO(Testo.ripulito(r.nome()), Testo.ripulito(r.indirizzo()), Testo.ripulito(r.citta()),
                r.lat(), r.lng(), testoOppure(r.superficie(), "Altro"), numeroOppure(r.canestri(), 2), vero(r.illuminato()),
                vero(r.coperto()), vero(r.gratuito()), vero(r.retine()), vero(r.linee()), vero(r.fontanella()),
                testoOppure(r.stato(), "discreto"), Testo.ripulito(r.note()), null);
    }

    private static String etichetta(Riga riga) {
        if (Testo.ripulito(riga.fonteId()).isEmpty()) return "senza fonteId";
        return riga.fonteId();
    }

    /* ── Scrittura ── */

    /**
     * Un blocco dentro la sua transazione: le righe già importate (stessa fonte e fonteId) si aggiornano solo se cambiate,
     * le altre si inseriscono. Una riga appena inserita entra nella mappa: un fonteId ripetuto nel file aggiorna invece di
     * violare l'indice unico
     */
    private void scrivi(List<Candidata> blocco, Utente admin, int[] conteggi) {
        List<String> ids = blocco.stream().map(Candidata::fonteId).toList();
        Map<String, Campetto> esistenti = new HashMap<>();
        for (Campetto c : campetti.findByFonteAndFonteIdIn(FONTE, ids)) {
            esistenti.put(c.getFonteId(), c);
        }
        for (Candidata candidata : blocco) {
            Campetto esistente = esistenti.get(candidata.fonteId());
            if (esistente == null) {
                Campetto nuovo = new Campetto();
                nuovo.setAutore(admin);
                nuovo.setFonte(FONTE);
                nuovo.setFonteId(candidata.fonteId());
                CampettoService.applica(candidata.dto(), nuovo);
                esistenti.put(candidata.fonteId(), campetti.save(nuovo));
                conteggi[0]++;
            } else if (!candidata.dto().equals(comeRichiesta(esistente))) {
                CampettoService.applica(candidata.dto(), esistente);
                campetti.save(esistente);
                conteggi[1]++;
            }
        }
    }

    /** Il campetto salvato nella forma del DTO, per confrontarlo con la riga dell'export */
    private static CampettoRequestDTO comeRichiesta(Campetto c) {
        return new CampettoRequestDTO(c.getNome(), c.getIndirizzo(), c.getCitta(), c.getLat(), c.getLng(), c.getSuperficie(),
                (int) c.getCanestri(), c.isIlluminato(), c.isCoperto(), c.isGratuito(), c.isRetine(), c.isLinee(),
                c.isFontanella(), c.getStato(), c.getNote(), null);
    }

    /* ── Valori predefiniti ── */

    private static String testoOppure(String valore, String predefinito) {
        if (Testo.ripulito(valore).isEmpty()) return predefinito;
        return valore.trim();
    }

    private static Integer numeroOppure(Integer valore, int predefinito) {
        if (valore == null) return predefinito;
        return valore;
    }

    private static boolean vero(Boolean b) {
        return Boolean.TRUE.equals(b);
    }
}
