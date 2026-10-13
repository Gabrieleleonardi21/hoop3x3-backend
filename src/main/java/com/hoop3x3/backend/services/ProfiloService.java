package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Il profilo dell'account: chi è dentro cambia il suo nome e la sua password (vedi il README, «Profilo dell'account»).
 * <ul>
 *   <li>Le operazioni delicate chiedono la password attuale anche con un JWT valido: un JWT rubato da solo non basta. Se è
 *       sbagliata la risposta è 400 e non 401: un 401 farebbe tentare al client il rinnovo della sessione e, se fallisse,
 *       lo farebbe uscire. I tentativi a raffica li ferma il limite per utente di LimiteRichiesteFilter.</li>
 *   <li>Cambiare la password chiude tutte le sessioni dell'account, come il recupero: la riapre il controller per il solo
 *       browser che ha fatto la richiesta.</li>
 * </ul>
 * Nei log solo l'id dell'utente: mai le password, e mai il nome, che lo scrive l'utente.
 */
@Slf4j
@Service
public class ProfiloService {

    public static final String PASSWORD_ATTUALE_ERRATA = "La password attuale non è corretta";
    public static final String PASSWORD_UGUALE = "La nuova password è uguale a quella attuale";

    private final UtenteRepository utenti;
    private final RefreshTokenService sessioni;
    private final PasswordEncoder encoder;

    public ProfiloService(UtenteRepository utenti, RefreshTokenService sessioni, PasswordEncoder encoder) {
        this.utenti = utenti;
        this.sessioni = sessioni;
        this.encoder = encoder;
    }

    /** Il nome mostrato nell'app (l'«autore» delle schede): i limiti di lunghezza li ha già controllati il DTO */
    @Transactional
    public Utente cambiaNome(Utente utente, String nome) {
        utente.setNome(nome.trim());
        Utente salvato = utenti.save(utente);
        log.info("Nome utente cambiato: utente {}", utente.getId());
        return salvato;
    }

    /** Cambia la password con quella attuale: 400 se l'attuale è sbagliata, se la nuova è uguale o non rispetta le regole */
    @Transactional
    public Utente cambiaPassword(Utente utente, String attuale, String nuova) {
        verificaPassword(utente, attuale);
        // L'attuale è appena stata verificata: se il testo è lo stesso, la password non cambierebbe
        if (nuova.equals(attuale)) throw new BadRequestException(PASSWORD_UGUALE);
        // Le regole della registrazione (72 byte di BCrypt, admin123 vietata), prima di calcolare l'hash
        UtenteService.controllaPassword(nuova);
        utente.setPassword(encoder.encode(nuova));
        Utente salvato = utenti.save(utente);
        // Chi cambia la password perché qualcuno l'ha scoperta chiude anche le sessioni di quel qualcuno
        sessioni.revocaTutte(utente);
        log.info("Password cambiata dal profilo: utente {}", utente.getId());
        return salvato;
    }

    /** La password attuale dell'account: 400 PASSWORD_ATTUALE_ERRATA se non è quella giusta (mai 401, vedi la classe) */
    private void verificaPassword(Utente utente, String password) {
        if (!encoder.matches(password, utente.getPassword())) throw new BadRequestException(PASSWORD_ATTUALE_ERRATA);
    }
}
