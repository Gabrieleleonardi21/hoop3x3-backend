package com.hoop3x3.backend;

import com.hoop3x3.backend.dto.CampettoRequestDTO;
import com.hoop3x3.backend.dto.GiocatoreRequestDTO;
import com.hoop3x3.backend.dto.LoginRequestDTO;
import com.hoop3x3.backend.dto.NuovaLegaDTO;
import com.hoop3x3.backend.dto.PatchLegaDTO;
import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.dto.SquadraRequestDTO;
import com.hoop3x3.backend.dto.TappaDTO;
import jakarta.validation.constraints.Size;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/**
 * La tabella dei campi di testo con un tetto di lunghezza: per ogni campo di un DTO di richiesta, in quali colonne del database
 * finisce. Il tetto non è scritto qui: si legge dal @Size del componente del record, quindi la fonte è una sola, il DTO.
 * LimitiColonneIT lo confronta con la lunghezza vera delle colonne, e CampiDiTestoTest controlla che nessun campo con un @Size
 * manchi dalla tabella. Che il tetto sia applicato (un carattere oltre dà 400) lo prova ValidazioneWebTest su un endpoint per ogni
 * DTO; che ogni endpoint con un corpo lo validi lo garantisce AccessoEndpointIT:ogniCorpoDiUnaRichiestaVieneValidato.
 */
public final class CampiDiTesto {

    private CampiDiTesto() {}

    /**
     * Un campo di un DTO e le colonne («tabella.colonna») in cui il suo valore viene salvato. Nessuna colonna: il valore non si
     * salva così com'è (la password finisce nell'hash). Una colonna TEXT non ha un limite nel database: il tetto è dell'API.
     */
    public record Campo(Class<?> dto, String componente, List<String> colonne) {

        /** Il tetto che il DTO dichiara: il più basso dei suoi @Size (la password ne ha due, uno per il minimo e uno per il massimo) */
        public int tetto() {
            RecordComponent campo = Arrays.stream(dto.getRecordComponents())
                    .filter(c -> c.getName().equals(componente)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(this + ": il DTO non ha questo campo"));
            // @Size non si applica al componente del record ma ai suoi metodi, campi e parametri: si legge dal metodo di accesso
            return Arrays.stream(campo.getAccessor().getAnnotationsByType(Size.class)).mapToInt(Size::max).min()
                    .orElseThrow(() -> new IllegalStateException(this + ": il campo non ha un @Size"));
        }

        @Override
        public String toString() {
            return dto.getSimpleName() + "." + componente;
        }
    }

    public static final List<Campo> TUTTI = List.of(
            // Il nome della lega finisce anche nelle pubblicazioni, che ne tengono una copia
            campo(NuovaLegaDTO.class, "nome", "leghe.nome", "archivio_tappe.lega_nome"),
            campo(PatchLegaDTO.class, "nome", "leghe.nome", "archivio_tappe.lega_nome"),
            // Nome e luogo della tappa finiscono anche nelle colonne dell'elenco dell'archivio (V6)
            campo(TappaDTO.class, "nome", "tappe.nome", "archivio_tappe.nome"),
            campo(TappaDTO.class, "luogo", "tappe.luogo", "archivio_tappe.luogo"),
            campo(GiocatoreRequestDTO.class, "nome", "anagrafe_giocatori.nome"),
            campo(GiocatoreRequestDTO.class, "cognome", "anagrafe_giocatori.cognome"),
            campo(GiocatoreRequestDTO.class, "soprannome", "anagrafe_giocatori.soprannome"),
            campo(GiocatoreRequestDTO.class, "nascita", "anagrafe_giocatori.nascita"),
            campo(GiocatoreRequestDTO.class, "citta", "anagrafe_giocatori.citta"),
            campo(GiocatoreRequestDTO.class, "nazionalita", "anagrafe_giocatori.nazionalita"),
            campo(GiocatoreRequestDTO.class, "altezza", "anagrafe_giocatori.altezza"),
            campo(GiocatoreRequestDTO.class, "peso", "anagrafe_giocatori.peso"),
            campo(GiocatoreRequestDTO.class, "ruolo", "anagrafe_giocatori.ruolo"),
            campo(GiocatoreRequestDTO.class, "numero", "anagrafe_giocatori.numero"),
            campo(GiocatoreRequestDTO.class, "squadra", "anagrafe_giocatori.squadra"),
            campo(GiocatoreRequestDTO.class, "esperienza", "anagrafe_giocatori.esperienza"),
            campo(GiocatoreRequestDTO.class, "note", "anagrafe_giocatori.note"),
            campo(SquadraRequestDTO.class, "nome", "anagrafe_squadre.nome"),
            campo(SquadraRequestDTO.class, "citta", "anagrafe_squadre.citta"),
            campo(SquadraRequestDTO.class, "anno", "anagrafe_squadre.anno"),
            campo(SquadraRequestDTO.class, "rank", "anagrafe_squadre.rank"),
            campo(SquadraRequestDTO.class, "referente", "anagrafe_squadre.referente"),
            campo(SquadraRequestDTO.class, "logo", "anagrafe_squadre.logo"),
            campo(SquadraRequestDTO.class, "website", "anagrafe_squadre.website"),
            campo(SquadraRequestDTO.class, "instagram", "anagrafe_squadre.instagram"),
            campo(SquadraRequestDTO.class, "note", "anagrafe_squadre.note"),
            // Superficie e stato non sono qui: hanno un @Pattern con i valori ammessi, non un @Size
            campo(CampettoRequestDTO.class, "nome", "campetti.nome"),
            campo(CampettoRequestDTO.class, "indirizzo", "campetti.indirizzo"),
            campo(CampettoRequestDTO.class, "citta", "campetti.citta"),
            campo(CampettoRequestDTO.class, "note", "campetti.note"),
            campo(RegisterRequestDTO.class, "name", "utenti.nome"),
            campo(RegisterRequestDTO.class, "email", "utenti.email"),
            // La password non si salva: in tabella c'è solo l'hash BCrypt, e il tetto è il limite di BCrypt (72)
            campo(RegisterRequestDTO.class, "password"),
            // Il login non salva niente, ma cerca l'email nella colonna: una più lunga non può esistere
            campo(LoginRequestDTO.class, "email", "utenti.email"));

    private static Campo campo(Class<?> dto, String componente, String... colonne) {
        return new Campo(dto, componente, List.of(colonne));
    }
}
