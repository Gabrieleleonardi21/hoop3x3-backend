package com.hoop3x3.backend.services;

import com.hoop3x3.backend.entities.TokenCambioEmail;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.exceptions.ForbiddenException;
import com.hoop3x3.backend.exceptions.TroppeRichiesteException;
import com.hoop3x3.backend.repositories.TokenCambioEmailRepository;
import com.hoop3x3.backend.repositories.TokenRecuperoPasswordRepository;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.support.Tempo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.hoop3x3.backend.services.LogSupport.mascheraEmail;
import static com.hoop3x3.backend.services.RecuperoPasswordService.DURATA_MINUTI;
import static com.hoop3x3.backend.services.RecuperoPasswordService.FINESTRA_MINUTI;
import static com.hoop3x3.backend.services.RecuperoPasswordService.LINK_NON_VALIDO;
import static com.hoop3x3.backend.services.RecuperoPasswordService.MASSIMO_LINK;
import static com.hoop3x3.backend.services.RecuperoPasswordService.PULIZIA_DOPO_GIORNI;
import static com.hoop3x3.backend.services.RefreshTokenService.nuovoToken;
import static com.hoop3x3.backend.services.RefreshTokenService.sha256;

/**
 * Il profilo dell'account: chi è dentro cambia il suo nome, la sua password e la sua email, ed elimina l'account (vedi il
 * README, «Profilo dell'account»).
 * <ul>
 *   <li>Le operazioni delicate chiedono la password attuale anche con un JWT valido: un JWT rubato da solo non basta. Se è
 *       sbagliata la risposta è 400 e non 401: un 401 farebbe tentare al client il rinnovo della sessione e, se fallisse,
 *       lo farebbe uscire. I tentativi a raffica li ferma il limite per utente di LimiteRichiesteFilter.</li>
 *   <li>Cambiare la password, dal profilo o con il recupero, chiude tutte le sessioni dell'account e i link ancora aperti
 *       (chiudiTuttoDopoIlCambioPassword, la regola in un punto solo): il controller del profilo riapre la sessione per il
 *       solo browser che ha fatto la richiesta.</li>
 *   <li>L'email cambia solo quando il link mandato al NUOVO indirizzo viene aperto e confermato: un errore di battitura non
 *       lascia l'account con un indirizzo che nessuno legge. Il link ha le regole di quello del recupero password (token di 32
 *       byte, nel database solo lo SHA-256, 30 minuti, una volta sola, al massimo 3 ogni 15 minuti per account).</li>
 *   <li>L'ADMIN non cambia email e non si elimina dall'app: lo riconosce il DataSeeder da ADMIN_EMAIL, e con un'email
 *       cambiata (o senza la sua riga) al riavvio ne nascerebbe un altro.</li>
 * </ul>
 * Nei log solo l'id dell'utente e l'email mascherata: mai le password né i token, e mai il nome, che lo scrive l'utente.
 */
@Slf4j
@Service
@EnableConfigurationProperties(AppProperties.class)
public class ProfiloService {

    public static final String PASSWORD_ATTUALE_ERRATA = "La password attuale non è corretta";
    public static final String PASSWORD_UGUALE = "La nuova password è uguale a quella attuale";
    public static final String EMAIL_UGUALE = "È già l'email del tuo account";
    // TroppeRichiesteException aggiunge da sé «: riprova tra 15 minuti»
    public static final String TROPPI_LINK = "Hai già richiesto " + MASSIMO_LINK + " link in " + FINESTRA_MINUTI + " minuti";
    public static final String ADMIN_DALLA_CONFIGURAZIONE =
            "L'account ADMIN si gestisce dalla configurazione del server (ADMIN_EMAIL)";
    public static final String RISPOSTA_LINK_INVIATO =
            "Ti abbiamo inviato un link al nuovo indirizzo: aprilo entro " + DURATA_MINUTI + " minuti per confermare il cambio.";
    public static final String RISPOSTA_EMAIL_CAMBIATA = "Email aggiornata: da ora accedi con il nuovo indirizzo.";

    private final UtenteRepository utenti;
    private final RefreshTokenService sessioni;
    private final PasswordEncoder encoder;
    private final UtenteService utenteService;
    private final TokenCambioEmailRepository tokens;
    private final TokenRecuperoPasswordRepository tokenDelRecupero;
    private final EmailSender email;
    private final String frontendUrl;

    public ProfiloService(UtenteRepository utenti, RefreshTokenService sessioni, PasswordEncoder encoder,
                          UtenteService utenteService, TokenCambioEmailRepository tokens,
                          TokenRecuperoPasswordRepository tokenDelRecupero, EmailSender email, AppProperties proprieta) {
        this.utenti = utenti;
        this.sessioni = sessioni;
        this.encoder = encoder;
        this.utenteService = utenteService;
        this.tokens = tokens;
        this.tokenDelRecupero = tokenDelRecupero;
        this.email = email;
        // Senza la barra finale: «https://sito/» darebbe un link con «//conferma-email»
        this.frontendUrl = proprieta.frontendUrl().replaceAll("/+$", "");
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
        chiudiTuttoDopoIlCambioPassword(utente);
        log.info("Password cambiata dal profilo: utente {}", utente.getId());
        return salvato;
    }

    /**
     * Dopo un cambio della password (dal profilo o con il link del recupero, RecuperoPasswordService): chi la cambia perché
     * qualcuno l'ha scoperta chiude anche ciò che quel qualcuno potrebbe ancora usare senza password. Nell'ordine: tutte le
     * sessioni, i link di cambio email ancora aperti (uno chiesto verso l'indirizzo di chi conosceva la password vecchia
     * varrebbe altri 30 minuti, e la conferma è pubblica) e i link di recupero ancora aperti. I JWT già emessi restano validi
     * fino alla loro scadenza.
     */
    @Transactional
    public void chiudiTuttoDopoIlCambioPassword(Utente utente) {
        LocalDateTime adesso = Tempo.adesso();
        sessioni.revocaTutte(utente);
        tokens.invalidaDellUtente(utente.getId(), adesso);
        tokenDelRecupero.invalidaDellUtente(utente.getId(), adesso);
    }

    /**
     * Manda al nuovo indirizzo il link di conferma: 403 per l'ADMIN, 400 se la password è sbagliata o l'email è già quella
     * dell'account, 409 se è di un altro account, 429 dopo 3 link in 15 minuti. L'email dell'account non cambia qui.
     */
    @Transactional
    public void richiediCambioEmail(Utente utente, String nuovaEmail, String password) {
        // Prima di tutto: per l'ADMIN la risposta non dipende dalla password
        if (utente.isAdmin()) throw new ForbiddenException(ADMIN_DALLA_CONFIGURAZIONE);
        verificaPassword(utente, password);
        String normalizzata = UtenteService.normalizza(nuovaEmail);
        if (normalizzata.equals(utente.getEmail())) throw new BadRequestException(EMAIL_UGUALE);
        utenteService.controllaEmailLibera(normalizzata);
        LocalDateTime adesso = Tempo.adesso();
        // Il limite si conta sui token del database, come per il recupero. Qui chi chiede è autenticato e ha dato la
        // password giusta: può sapere che il limite c'è, quindi 429 e non una risposta uguale alle altre
        if (tokens.contaCreatiDopo(utente.getId(), adesso.minusMinutes(FINESTRA_MINUTI)) >= MASSIMO_LINK) {
            throw new TroppeRichiesteException(TROPPI_LINK, FINESTRA_MINUTI * 60L);
        }
        // Pulizia pigra (i token di più di un giorno fa non contano più nel limite) e un solo link valido per volta
        tokens.eliminaCreatiPrima(utente.getId(), adesso.minusDays(PULIZIA_DOPO_GIORNI));
        tokens.invalidaDellUtente(utente.getId(), adesso);
        String token = nuovoToken();
        tokens.save(new TokenCambioEmail(utente, normalizzata, sha256(token), adesso.plusMinutes(DURATA_MINUTI)));
        // L'email parte dopo il salvataggio; anche se la transazione fallisse, il link porterebbe solo a «link non valido»
        ModelloEmail.ContenutoEmail contenuto =
                EmailConfermaEmail.componi(frontendUrl, frontendUrl + "/conferma-email?token=" + token, DURATA_MINUTI);
        email.invia(normalizzata, contenuto.oggetto(), contenuto.testo(), contenuto.html());
        log.info("Cambio email richiesto: utente {}, nuovo indirizzo {}", utente.getId(), mascheraEmail(normalizzata));
    }

    /**
     * Conferma il cambio con il token del link: 400 LINK_NON_VALIDO se il token non c'è, è già usato, sostituito o scaduto;
     * 409 se nel frattempo l'indirizzo è stato registrato da un altro. In quel caso il token resta consumato (noRollbackFor):
     * il link non vale più, e chi lo vuole ne chiede uno per un altro indirizzo. Le sessioni restano aperte: il JWT
     * identifica l'utente per id, e l'email nuova non lo rende meno suo.
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public Utente confermaEmail(String token) {
        TokenCambioEmail salvato = tokens.findByTokenHash(sha256(token))
                .filter(TokenCambioEmail::isUtilizzabile)
                .orElseThrow(() -> new BadRequestException(LINK_NON_VALIDO));
        LocalDateTime adesso = Tempo.adesso();
        // Due richieste insieme con lo stesso link: lo segna solo la prima, l'altra non cambia l'email
        if (tokens.segnaUsato(salvato.getId(), adesso) == 0) throw new BadRequestException(LINK_NON_VALIDO);
        Utente utente = salvato.getUtente();
        tokens.invalidaDellUtente(utente.getId(), adesso);
        utenteService.controllaEmailLibera(salvato.getEmailNuova());
        utente.setEmail(salvato.getEmailNuova());
        Utente aggiornato = utenti.save(utente);
        log.info("Email cambiata: utente {}", utente.getId());
        return aggiornato;
    }

    /**
     * Elimina l'account con la password attuale: 403 per l'ADMIN (nasce da ADMIN_EMAIL: il DataSeeder lo ricreerebbe e i dati
     * di prova intestati a lui resterebbero senza proprietario), 400 se la password è sbagliata. Basta la DELETE della riga
     * di utenti, perché Utente non ha collezioni mappate: il resto lo fa il database con le sue chiavi esterne, nella stessa
     * transazione. Vanno via sessioni e token (ON DELETE CASCADE), le leghe con le loro tappe e le pubblicazioni; i campetti
     * restano senza autore (ON DELETE SET NULL, V8). Vedi il README, «Profilo dell'account».
     */
    @Transactional
    public void elimina(Utente utente, String password) {
        if (utente.isAdmin()) throw new ForbiddenException(ADMIN_DALLA_CONFIGURAZIONE);
        verificaPassword(utente, password);
        utenti.deleteById(utente.getId());
        // Solo l'id: l'email non esiste più e non deve restare nei log
        log.info("Account eliminato: utente {}", utente.getId());
    }

    /** La password attuale dell'account: 400 PASSWORD_ATTUALE_ERRATA se non è quella giusta (mai 401, vedi la classe) */
    private void verificaPassword(Utente utente, String password) {
        if (!encoder.matches(password, utente.getPassword())) throw new BadRequestException(PASSWORD_ATTUALE_ERRATA);
    }
}
