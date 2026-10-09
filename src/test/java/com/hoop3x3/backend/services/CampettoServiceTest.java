package com.hoop3x3.backend.services;

import com.hoop3x3.backend.UtenteDiProva;
import com.hoop3x3.backend.dto.CampettoDTO;
import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.entities.Campetto;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.NotFoundException;
import com.hoop3x3.backend.repositories.CampettoRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Il servizio dei campetti senza contesto Spring: repository e guardia simulati (la guardia vera la prova AccessGuardTest, il
 * giro con il database CampettoIT). Le scritture: autore, proprietà, versione e la riga dell'ADMIN come per l'anagrafe. Le
 * letture: il ritaglio per raggio (riquadro di coordinate al database, poi la distanza vera con Haversine, ordinati per
 * distanza e al massimo 200) e la ricerca per testo.
 */
class CampettoServiceTest {

    /** Il centro di Torino, da cui partono le ricerche di prova */
    private static final double LAT_TORINO = 45.07;
    private static final double LNG_TORINO = 7.68;
    /** Un grado di latitudine in chilometri: la larghezza del riquadro si ricava da qui */
    private static final double KM_PER_GRADO = 111.32;

    private final CampettoRepository campetti = mock(CampettoRepository.class);
    private final AccessGuard guard = mock(AccessGuard.class);
    private final CampettoService servizio = new CampettoService(campetti, guard);

    private final Utente mario = UtenteDiProva.conId("mario@test.it");
    private final Utente admin = UtenteDiProva.conId("admin@test.it", Ruolo.ADMIN);
    private final UUID id = UUID.randomUUID();

    /* ── Scritture ── */

    @Test
    void crea_intestaIlCampettoAllAutore_eNormalizzaICampi() {
        when(campetti.save(any(Campetto.class))).thenAnswer(chiamata -> conDataDiModifica(chiamata.getArgument(0)));

        CampettoDTO creato = servizio.crea(mario, richiesta("  Parco Dora  ", null));

        ArgumentCaptor<Campetto> salvato = ArgumentCaptor.forClass(Campetto.class);
        verify(campetti).save(salvato.capture());
        Campetto c = salvato.getValue();
        assertThat(c.getAutore()).isSameAs(mario);
        assertThat(c.getNome()).isEqualTo("Parco Dora");
        assertThat(c.getIndirizzo()).as("un campo facoltativo assente si salva vuoto").isEmpty();
        assertThat(c.getCitta()).isEqualTo("Torino");
        assertThat(c.getTipo()).as("le creazioni dall'app sono sempre campetti").isEqualTo("campetto");
        assertThat(c.getCanestri()).isEqualTo((short) 4);
        assertThat(c.isIlluminato()).isTrue();
        assertThat(c.isCoperto()).as("un booleano assente nella richiesta vale false").isFalse();
        assertThat(c.getFonte()).isNull();
        assertThat(creato.autore()).isEqualTo("Nome");
        assertThat(creato.autoreId()).isEqualTo(mario.getId());
        assertThat(creato.tipo()).isEqualTo("campetto");
    }

    // Prima si decide chi può scrivere (checkOwner), poi che cosa (la versione), e la riga dell'ADMIN arriva subito prima di scrivere
    @Test
    void aggiorna_controllaProprietaEVersione_poiTracciaESalvaConFlush() {
        Campetto c = campettoDiMario();

        CampettoDTO aggiornato = servizio.aggiorna(admin, id, richiesta("Parco Dora — Le Arcate", 0L));

        InOrder ordine = inOrder(guard, campetti);
        ordine.verify(guard).checkOwner(admin, mario.getId(), "questa scheda campetto");
        ordine.verify(guard).tracciaModifica(admin, mario.getId(), "campetto", id);
        ordine.verify(campetti).saveAndFlush(c); // flush: il ts della risposta è quello di adesso, come per l'anagrafe
        assertThat(aggiornato.nome()).isEqualTo("Parco Dora — Le Arcate");
        assertThat(aggiornato.autoreId()).isEqualTo(mario.getId());
    }

    // Il client ha letto il campetto alla versione 3 ma il database è alla 0 (o viceversa): 409 e niente salvato
    @Test
    void aggiorna_conUnaVersioneDiversa_risponde409ENonSalvaNulla() {
        campettoDiMario();

        assertThatThrownBy(() -> servizio.aggiorna(mario, id, richiesta("Altro nome", 3L)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(campetti, never()).saveAndFlush(any());
        verify(guard, never()).tracciaModifica(any(), any(), any(), any());
    }

    @Test
    void aggiorna_senzaVersioneNelCorpo_salvaComeSempre() {
        campettoDiMario();

        servizio.aggiorna(mario, id, richiesta("Altro nome", null));

        verify(campetti).saveAndFlush(any(Campetto.class));
    }

    @Test
    void aggiorna_unCampettoCheNonEsiste_risponde404() {
        when(campetti.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servizio.aggiorna(mario, id, richiesta("Nome", null)))
                .isInstanceOf(NotFoundException.class);

        verify(guard, never()).checkOwner(any(), any(), any());
    }

    @Test
    void elimina_controllaLaProprieta_tracciaEdElimina() {
        Campetto c = campettoDiMario();

        servizio.elimina(admin, id);

        InOrder ordine = inOrder(guard, campetti);
        ordine.verify(guard).checkOwner(admin, mario.getId(), "questa scheda campetto");
        ordine.verify(guard).tracciaEliminazione(admin, mario.getId(), "campetto", id);
        ordine.verify(campetti).delete(c);
    }

    /* ── L'autore eliminato: il campetto resta, senza chi l'ha creato ── */

    // autore_id è ON DELETE SET NULL: il DTO non ha nessun autore e il controllo di proprietà riceve null, che nessun utente
    // eguaglia (passa solo un ADMIN, lo decide AccessGuard)
    @Test
    void senzaAutore_ilDtoHaAutoreVuotoEAutoreIdNull_eLaProprietaSiControllaConNull() {
        Campetto orfano = campetto("Campo Vanchiglia", 45.07047, 7.71690);
        orfano.setAutore(null);
        when(campetti.findById(id)).thenReturn(Optional.of(orfano));
        when(campetti.saveAndFlush(orfano)).thenReturn(orfano);
        when(campetti.perTesto(any(), any())).thenReturn(List.of(orfano));

        CampettoDTO letto = servizio.cercaPerTesto("vanchiglia", null, null).getFirst();
        servizio.aggiorna(admin, id, richiesta("Campo Vanchiglia", null));

        assertThat(letto.autore()).isEmpty();
        assertThat(letto.autoreId()).isNull();
        verify(guard).checkOwner(admin, null, "questa scheda campetto");
        verify(guard).tracciaModifica(admin, null, "campetto", id);
    }

    /* ── Ricerca per raggio: riquadro al database, Haversine in Java ── */

    // Il riquadro è il ritaglio grossolano: ha gli angoli fuori dal cerchio, quindi un campetto nel riquadro può essere oltre il
    // raggio e va tolto; l'ordine del database non conta, vince la distanza
    @Test
    void cercaPerRaggio_chiedeIlRiquadroAlDatabase_tieneSoloQuelliEntroIlRaggio_eLiOrdinaPerDistanza() {
        double raggioKm = 5;
        Campetto aUnKm = campetto("A un km", LAT_TORINO + 1 / KM_PER_GRADO, LNG_TORINO);
        Campetto aQuattroKm = campetto("A quattro km", LAT_TORINO - 4 / KM_PER_GRADO, LNG_TORINO);
        // Nell'angolo del riquadro: 4 km a nord e 4 km a est, cioè 5,7 km in linea d'aria
        Campetto nellAngolo = campetto("Nell'angolo", LAT_TORINO + 4 / KM_PER_GRADO,
                LNG_TORINO + 4 / (KM_PER_GRADO * Math.cos(Math.toRadians(LAT_TORINO))));
        when(campetti.nelRiquadro(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(aQuattroKm, nellAngolo, aUnKm));

        List<CampettoDTO> trovati = servizio.cercaPerRaggio(LAT_TORINO, LNG_TORINO, raggioKm);

        assertThat(trovati).extracting(CampettoDTO::nome).containsExactly("A un km", "A quattro km");
        // Il riquadro: ±raggio in latitudine, e in longitudine ±raggio diviso il coseno della latitudine (un grado di
        // longitudine a Torino misura 79 km, non 111)
        ArgumentCaptor<Double> lati = ArgumentCaptor.forClass(Double.class);
        verify(campetti).nelRiquadro(lati.capture(), lati.capture(), lati.capture(), lati.capture());
        double mezzaLatitudine = raggioKm / KM_PER_GRADO;
        double mezzaLongitudine = mezzaLatitudine / Math.cos(Math.toRadians(LAT_TORINO));
        assertThat(lati.getAllValues().get(0)).isCloseTo(LAT_TORINO - mezzaLatitudine, within(0.0005));
        assertThat(lati.getAllValues().get(1)).isCloseTo(LAT_TORINO + mezzaLatitudine, within(0.0005));
        assertThat(lati.getAllValues().get(2)).isCloseTo(LNG_TORINO - mezzaLongitudine, within(0.0005));
        assertThat(lati.getAllValues().get(3)).isCloseTo(LNG_TORINO + mezzaLongitudine, within(0.0005));
    }

    // Vicino al polo o all'antimeridiano il riquadro in longitudine non ha senso (sforerebbe i ±180 o diventerebbe infinito): si
    // allarga a tutta la longitudine e la latitudine resta nei suoi limiti; a scegliere è poi la distanza vera
    @Test
    void cercaPerRaggio_vicinoAlPoloOAllAntimeridiano_ilRiquadroRestaDentroICoordinate() {
        when(campetti.nelRiquadro(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());

        servizio.cercaPerRaggio(89.9, 10, 100);
        servizio.cercaPerRaggio(0, 179.99, 50);

        ArgumentCaptor<Double> lati = ArgumentCaptor.forClass(Double.class);
        verify(campetti, org.mockito.Mockito.times(2)).nelRiquadro(lati.capture(), lati.capture(), lati.capture(), lati.capture());
        List<Double> alPolo = lati.getAllValues().subList(0, 4);
        List<Double> allAntimeridiano = lati.getAllValues().subList(4, 8);
        assertThat(alPolo.get(1)).as("latitudine massima al polo").isEqualTo(90);
        assertThat(alPolo.subList(2, 4)).as("longitudine al polo").containsExactly(-180.0, 180.0);
        assertThat(allAntimeridiano.subList(2, 4)).as("longitudine all'antimeridiano").containsExactly(-180.0, 180.0);
    }

    @Test
    void cercaPerRaggio_rispondeAlMassimo200Campetti_iPiuVicini() {
        List<Campetto> molti = new ArrayList<>();
        for (int i = 250; i >= 1; i--) { // dal più lontano al più vicino, a 10 metri l'uno dall'altro
            molti.add(campetto("A " + i * 10 + " m", LAT_TORINO + i * 0.01 / KM_PER_GRADO, LNG_TORINO));
        }
        when(campetti.nelRiquadro(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(molti);

        List<CampettoDTO> trovati = servizio.cercaPerRaggio(LAT_TORINO, LNG_TORINO, 20);

        assertThat(trovati).hasSize(200);
        assertThat(trovati.getFirst().nome()).isEqualTo("A 10 m");
        assertThat(trovati.getLast().nome()).isEqualTo("A 2000 m");
    }

    /* ── Ricerca per testo ── */

    // Il testo diventa un like come sottostringa, con i caratteri speciali del like protetti (un «%» scritto dall'utente è un
    // carattere, non un jolly); il database ordina per città e nome, e al massimo 200 righe
    @Test
    void cercaPerTesto_cercaComeSottostringa_proteggeICaratteriDelLike_eChiedeAlMassimo200Righe() {
        Campetto dora = campetto("Parco Dora — Le Arcate", 45.08972, 7.66669);
        when(campetti.perTesto("%do\\_ra\\%\\\\%", Limit.of(200))).thenReturn(List.of(dora));

        List<CampettoDTO> trovati = servizio.cercaPerTesto("do_ra%\\", null, null);

        assertThat(trovati).extracting(CampettoDTO::nome).containsExactly("Parco Dora — Le Arcate");
    }

    // Con un punto (lat e lng senza raggioKm) i risultati del testo si ordinano per distanza da lì
    @Test
    void cercaPerTesto_conUnPunto_ordinaPerDistanzaDaQuelPunto() {
        Campetto lontano = campetto("Lontano", LAT_TORINO + 0.1, LNG_TORINO);
        Campetto vicino = campetto("Vicino", LAT_TORINO + 0.01, LNG_TORINO);
        when(campetti.perTesto(eq("%campo%"), any())).thenReturn(List.of(lontano, vicino));

        List<CampettoDTO> senzaPunto = servizio.cercaPerTesto("campo", null, null);
        List<CampettoDTO> conPunto = servizio.cercaPerTesto("campo", LAT_TORINO, LNG_TORINO);

        assertThat(senzaPunto).extracting(CampettoDTO::nome).containsExactly("Lontano", "Vicino");
        assertThat(conPunto).extracting(CampettoDTO::nome).containsExactly("Vicino", "Lontano");
    }

    /* ── Dati di prova ── */

    /** Un campetto di mario con quell'id, già alla versione 0, che il repository trova e salva */
    private Campetto campettoDiMario() {
        Campetto c = campetto("Parco Dora", 45.08972, 7.66669);
        when(campetti.findById(id)).thenReturn(Optional.of(c));
        when(campetti.saveAndFlush(c)).thenReturn(c);
        return c;
    }

    /** Un campetto di mario in quel punto, con l'id e la data di modifica che gli darebbe il database */
    private Campetto campetto(String nome, double lat, double lng) {
        Campetto c = new Campetto();
        ReflectionTestUtils.setField(c, "id", id);
        c.setNome(nome);
        c.setLat(lat);
        c.setLng(lng);
        c.setSuperficie("Asfalto");
        c.setCanestri((short) 2);
        c.setStato("buono");
        c.setAutore(mario);
        return conDataDiModifica(c);
    }

    private static Campetto conDataDiModifica(Campetto c) {
        c.setModificatoIl(LocalDateTime.now());
        return c;
    }

    /** Una richiesta valida a Torino con quel nome e quella versione: illuminato sì, gli altri booleani assenti */
    private static CampettoRequestDTO richiesta(String nome, Long versione) {
        return new CampettoRequestDTO(nome, null, "Torino", 45.08972, 7.66669, "Sintetico", 4,
                true, null, null, null, null, null, "buono", null, versione);
    }
}
