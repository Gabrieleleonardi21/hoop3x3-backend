package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.TokenRecuperoPassword;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.repositories.TokenRecuperoPasswordRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.support.Tempo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.hoop3x3.backend.services.LogSupport.mascheraEmail;
import static com.hoop3x3.backend.services.RefreshTokenService.nuovoToken;
import static com.hoop3x3.backend.services.RefreshTokenService.sha256;

/**
 * Recupero password via email: chi l'ha dimenticata chiede un link, e con il token del link sceglie una password nuova.
 * <ul>
 *   <li>Il token è come un refresh token (32 byte casuali, nel database solo il suo SHA-256), vale 30 minuti e una volta sola.
 *       Un link nuovo invalida quelli precedenti dello stesso utente.</li>
 *   <li>Chi chiede il link riceve sempre la stessa risposta, registrato o no: l'endpoint non dice quali email hanno un
 *       account. L'email parte su un altro thread (EmailSenderBrevo), quindi nemmeno i tempi lo dicono in modo evidente.</li>
 *   <li>Al massimo 3 link ogni 15 minuti per account, contati sui token del database: oltre, nessuna email e la stessa risposta.
 *       Il limite per indirizzo IP è di LimiteRichiesteFilter.</li>
 *   <li>La password nuova ha le regole della registrazione, e chi la cambia chiude tutte le sessioni dell'account.</li>
 * </ul>
 * Nei log mai il token, mai la password, e l'email solo mascherata (LogSupport.mascheraEmail).
 */
@Slf4j
@Service
@EnableConfigurationProperties(AppProperties.class)
public class RecuperoPasswordService {

    public static final String RISPOSTA_RICHIESTA = "Se l'indirizzo è registrato, ti abbiamo inviato un link per reimpostare la password.";
    public static final String RISPOSTA_REIMPOSTATA = "Password aggiornata. Accedi con la nuova password.";
    /** Un solo messaggio per token sconosciuto, già usato o scaduto: chi prova a indovinare non impara niente */
    public static final String LINK_NON_VALIDO = "Il link non è valido o è scaduto. Richiedine uno nuovo.";

    private static final int DURATA_MINUTI = 30;
    private static final int MASSIMO_LINK = 3;
    private static final int FINESTRA_MINUTI = 15;
    private static final int PULIZIA_DOPO_GIORNI = 1;

    private final UtenteRepository utenti;
    private final TokenRecuperoPasswordRepository tokens;
    private final RefreshTokenService sessioni;
    private final PasswordEncoder encoder;
    private final EmailSender email;
    private final String frontendUrl;

    public RecuperoPasswordService(UtenteRepository utenti, TokenRecuperoPasswordRepository tokens, RefreshTokenService sessioni,
                                   PasswordEncoder encoder, EmailSender email, AppProperties proprieta) {
        this.utenti = utenti;
        this.tokens = tokens;
        this.sessioni = sessioni;
        this.encoder = encoder;
        this.email = email;
        // Senza la barra finale: «https://sito/» darebbe un link con «//reimposta-password»
        this.frontendUrl = proprieta.frontendUrl().replaceAll("/+$", "");
    }

    /** Manda il link se l'email è di un account e non ha superato il limite; altrimenti non fa niente, senza dirlo a chi chiama */
    @Transactional
    public void richiedi(String indirizzo) {
        String normalizzato = UtenteService.normalizza(indirizzo);
        Optional<Utente> trovato = utenti.findByEmail(normalizzato);
        if (trovato.isEmpty()) {
            log.info("Recupero password per un'email non registrata: {}", mascheraEmail(normalizzato));
            return;
        }
        Utente utente = trovato.get();
        LocalDateTime adesso = Tempo.adesso();
        if (tokens.contaCreatiDopo(utente.getId(), adesso.minusMinutes(FINESTRA_MINUTI)) >= MASSIMO_LINK) {
            log.warn("Recupero password: già {} link in {} minuti per {}, nessuna nuova email", MASSIMO_LINK, FINESTRA_MINUTI,
                    mascheraEmail(utente.getEmail()));
            return;
        }
        // Pulizia pigra, come per i refresh token: i token di più di un giorno fa sono scaduti da ore e non contano più nel
        // limite dei 15 minuti, quindi si cancellano senza cambiare il conteggio
        tokens.eliminaCreatiPrima(utente.getId(), adesso.minusDays(PULIZIA_DOPO_GIORNI));
        tokens.invalidaDellUtente(utente.getId(), adesso);
        String token = nuovoToken();
        tokens.save(new TokenRecuperoPassword(utente, sha256(token), adesso.plusMinutes(DURATA_MINUTI)));
        // L'email parte dopo il salvataggio; anche se la transazione fallisse, il link porterebbe solo a «link non valido»
        ModelloEmail.ContenutoEmail contenuto =
                EmailRecuperoPassword.componi(frontendUrl, frontendUrl + "/reimposta-password?token=" + token, DURATA_MINUTI);
        email.invia(utente.getEmail(), contenuto.oggetto(), contenuto.testo(), contenuto.html());
        log.info("Recupero password: link inviato a {}", mascheraEmail(utente.getEmail()));
    }

    /** Cambia la password con il token del link: 400 LINK_NON_VALIDO se il token non c'è, è già usato o è scaduto */
    @Transactional
    public void reimposta(String token, String nuovaPassword) {
        TokenRecuperoPassword salvato = tokens.findByTokenHash(sha256(token))
                .filter(TokenRecuperoPassword::isUtilizzabile)
                .orElseThrow(() -> new BadRequestException(LINK_NON_VALIDO));
        // Le regole della registrazione (byte di BCrypt, admin123 vietata). Dopo il token, così chi ha il link scaduto non
        // corregge la password per niente; prima di usarlo, così chi deve cambiarla può riprovare con lo stesso link
        UtenteService.controllaPassword(nuovaPassword);
        LocalDateTime adesso = Tempo.adesso();
        // Due richieste insieme con lo stesso link: lo segna solo la prima, l'altra non cambia la password
        if (tokens.segnaUsato(salvato.getId(), adesso) == 0) throw new BadRequestException(LINK_NON_VALIDO);
        Utente utente = salvato.getUtente();
        tokens.invalidaDellUtente(utente.getId(), adesso);
        utente.setPassword(encoder.encode(nuovaPassword));
        utenti.save(utente);
        // Chi ha cambiato la password perché qualcuno gliel'ha rubata chiude anche le sessioni di quel qualcuno
        sessioni.revocaTutte(utente);
        log.info("Password reimpostata con il link di recupero: utente {}", utente.getId());
    }
}
