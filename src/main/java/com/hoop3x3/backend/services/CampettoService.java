package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.CampettoDTO;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.CampettoRepository;
import com.hoop3x3.backend.support.Testo;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * I campetti geolocalizzati: lettura pubblica (per raggio o per testo), scrittura di autore o ADMIN. La proprietà e la
 * versione seguono le regole dell'anagrafe (AccessGuard, controllaVersione); l'autore può non esserci più (autore_id ON
 * DELETE SET NULL) e allora scrive solo un ADMIN, perché nessun utente eguaglia un proprietario nullo.
 */
@Service
public class CampettoService {

    /** Quante righe al massimo risponde una ricerca, per raggio o per testo */
    public static final int MASSIMO_RIGHE = 200;
    private static final double RAGGIO_TERRA_KM = 6371;
    /** Un grado di latitudine in chilometri (un grado di longitudine misura questo per il coseno della latitudine) */
    private static final double KM_PER_GRADO = 111.32;

    private final CampettoRepository campetti;
    private final AccessGuard guard;

    public CampettoService(CampettoRepository campetti, AccessGuard guard) {
        this.campetti = campetti;
        this.guard = guard;
    }

    /* ── Letture ── */

    /**
     * I campetti entro `raggioKm` dal punto, dal più vicino, al massimo {@link #MASSIMO_RIGHE}. Al database si chiede un
     * riquadro di coordinate (usa l'indice su lat e lng), che ha gli angoli fuori dal cerchio: la distanza vera, Haversine,
     * si calcola qui e scarta ciò che sta oltre il raggio
     */
    @Transactional(readOnly = true)
    public List<CampettoDTO> cercaPerRaggio(double lat, double lng, double raggioKm) {
        double mezzaLatitudine = raggioKm / KM_PER_GRADO;
        // La longitudine si restringe verso i poli: al polo il coseno è 0 e il riquadro diventerebbe infinito, e vicino
        // all'antimeridiano sforerebbe i ±180. In entrambi i casi si prende tutta la longitudine e sceglie la distanza vera
        double mezzaLongitudine = mezzaLatitudine / Math.cos(Math.toRadians(lat));
        double lngMin = -180;
        double lngMax = 180;
        if (lng - mezzaLongitudine >= -180 && lng + mezzaLongitudine <= 180) {
            lngMin = lng - mezzaLongitudine;
            lngMax = lng + mezzaLongitudine;
        }
        List<Campetto> nelRiquadro = campetti.nelRiquadro(Math.max(-90, lat - mezzaLatitudine),
                Math.min(90, lat + mezzaLatitudine), lngMin, lngMax);
        return nelRiquadro.stream()
                .filter(c -> distanzaKm(lat, lng, c.getLat(), c.getLng()) <= raggioKm)
                .sorted(perDistanzaDa(lat, lng))
                .limit(MASSIMO_RIGHE)
                .map(CampettoDTO::from)
                .toList();
    }

    /**
     * I campetti con `q` nel nome o nella città (sottostringa, senza distinzione di maiuscole), al massimo
     * {@link #MASSIMO_RIGHE}, ordinati per città e nome dal database; se c'è un punto (lat e lng), per distanza da lì
     */
    @Transactional(readOnly = true)
    public List<CampettoDTO> cercaPerTesto(String q, Double lat, Double lng) {
        // I caratteri speciali del like scritti dall'utente sono caratteri, non jolly: si proteggono con la «\» che il
        // repository dichiara come escape
        String filtro = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Stream<Campetto> trovati = campetti.perTesto(filtro, Limit.of(MASSIMO_RIGHE)).stream();
        if (lat != null && lng != null) trovati = trovati.sorted(perDistanzaDa(lat, lng));
        return trovati.map(CampettoDTO::from).toList();
    }

    /* ── Scritture ── */

    @Transactional
    public CampettoDTO crea(Utente utente, CampettoRequestDTO dto) {
        Campetto c = new Campetto();
        c.setAutore(utente);
        applica(dto, c);
        return CampettoDTO.from(campetti.save(c));
    }

    @Transactional
    public CampettoDTO aggiorna(Utente utente, UUID id, CampettoRequestDTO dto) {
        Campetto c = trova(id);
        guard.checkOwner(utente, autoreId(c), "questa scheda campetto");
        AnagrafeService.controllaVersione(dto.versione(), c.getVersione(), Campetto.class, id);
        applica(dto, c);
        guard.tracciaModifica(utente, autoreId(c), "campetto", id);
        // saveAndFlush: la data di modifica la scrive Hibernate al flush, e il DTO deve portare il ts di adesso (come per l'anagrafe)
        return CampettoDTO.from(campetti.saveAndFlush(c));
    }

    @Transactional
    public void elimina(Utente utente, UUID id) {
        Campetto c = trova(id);
        guard.checkOwner(utente, autoreId(c), "questa scheda campetto");
        guard.tracciaEliminazione(utente, autoreId(c), "campetto", id);
        campetti.delete(c);
    }

    /* ── Helper ── */

    /** La distanza in chilometri tra due punti sulla sfera terrestre (formula di Haversine). Pubblica: i test la usano per l'atteso */
    public static double distanzaKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * RAGGIO_TERRA_KM * Math.asin(Math.sqrt(a));
    }

    private static Comparator<Campetto> perDistanzaDa(double lat, double lng) {
        return Comparator.comparingDouble(c -> distanzaKm(lat, lng, c.getLat(), c.getLng()));
    }

    /** L'id dell'autore, o null se l'autore non esiste più */
    private static UUID autoreId(Campetto c) {
        if (c.getAutore() == null) return null;
        return c.getAutore().getId();
    }

    private Campetto trova(UUID id) {
        return campetti.findById(id).orElseThrow(() -> new NotFoundException("Campetto non trovato: " + id));
    }

    /** I campi del DTO sul campetto, nella forma che si salva. Pubblico perché lo usa anche ImportCampetti: una regola sola */
    public static void applica(CampettoRequestDTO d, Campetto c) {
        c.setNome(d.nome().trim());
        c.setIndirizzo(Testo.ripulito(d.indirizzo()));
        c.setCitta(Testo.ripulito(d.citta()));
        c.setLat(d.lat());
        c.setLng(d.lng());
        c.setSuperficie(d.superficie());
        c.setCanestri(d.canestri().shortValue());
        c.setIlluminato(vero(d.illuminato()));
        c.setCoperto(vero(d.coperto()));
        c.setGratuito(vero(d.gratuito()));
        c.setRetine(vero(d.retine()));
        c.setLinee(vero(d.linee()));
        c.setFontanella(vero(d.fontanella()));
        c.setStato(d.stato());
        c.setNote(Testo.ripulito(d.note()));
    }

    /** Un booleano assente nella richiesta vale false */
    private static boolean vero(Boolean b) {
        return Boolean.TRUE.equals(b);
    }
}
