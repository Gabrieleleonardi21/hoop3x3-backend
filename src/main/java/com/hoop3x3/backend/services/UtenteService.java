package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Service
public class UtenteService implements UserDetailsService {

    /**
     * BCrypt legge al massimo 72 byte: oltre, il PasswordEncoder lancia IllegalArgumentException (un 500 al client, e al
     * DataSeeder un avvio fallito). Pubblico perché lo controlla anche il DataSeeder
     */
    public static final int PASSWORD_MAX_BYTE = 72;

    /** La password pubblica dell'esempio (vedi controllaPassword) */
    private static final String PASSWORD_DI_ESEMPIO = "admin123";

    private final UtenteRepository utenteRepository;
    private final PasswordEncoder passwordEncoder;

    public UtenteService(UtenteRepository utenteRepository, PasswordEncoder passwordEncoder) {
        this.utenteRepository = utenteRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // Usato dall'AuthenticationManager al login: lo username è l'email
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return utenteRepository.findByEmail(normalizza(email))
                .orElseThrow(() -> new UsernameNotFoundException("Utente non trovato: " + email));
    }

    /** La registrazione assegna sempre USER: l'ADMIN nasce solo dal seeder */
    public Utente register(RegisterRequestDTO dto) {
        controllaPassword(dto.password());
        String email = normalizza(dto.email());
        controllaEmailLibera(email);
        Utente utente = new Utente(email, passwordEncoder.encode(dto.password()), dto.name().trim(), Ruolo.USER);
        return utenteRepository.save(utente);
    }

    /**
     * 409 se l'email (già normalizzata) è di un account: un testo solo per la registrazione e per il cambio email dal profilo
     * (ProfiloService, alla richiesta e alla conferma)
     */
    public void controllaEmailLibera(String emailNormalizzata) {
        if (utenteRepository.existsByEmail(emailNormalizzata)) throw emailGiaRegistrata(emailNormalizzata);
    }

    /** Il 409 dell'email già registrata, con il suo testo: lo usa anche ProfiloService quando l'UNIQUE scatta alla flush */
    public static ConflictException emailGiaRegistrata(String emailNormalizzata) {
        return new ConflictException("Email " + emailNormalizzata + " già registrata");
    }

    /**
     * Le regole della password di un account che la validazione del DTO non può vedere, in un posto solo: alla registrazione e
     * alla nuova password del recupero (RecuperoPasswordService), prima di toccare il database e con gli stessi messaggi.
     * <ul>
     *   <li>Il DTO conta i caratteri, BCrypt i byte: una lettera accentata ne occupa 2 e un emoji 4, quindi 40 lettere accentate
     *       (80 byte) passano la validazione ma non l'encoder.</li>
     *   <li>admin123 è la password che il vecchio env.properties.example proponeva per l'admin: è pubblica, e non vale per
     *       nessun account. Per l'admin la stessa regola la applica il DataSeeder, a ADMIN_PASSWORD.</li>
     * </ul>
     */
    public static void controllaPassword(String password) {
        if (superaIlLimiteDiBcrypt(password)) {
            throw new BadRequestException("La password supera i " + PASSWORD_MAX_BYTE
                    + " byte: accorciala (le lettere accentate occupano 2 byte, gli emoji 4)");
        }
        if (PASSWORD_DI_ESEMPIO.equals(password)) {
            throw new BadRequestException("Questa password non è consentita: scegline un'altra");
        }
    }

    /** La password in UTF-8 supera i 72 byte di BCrypt: il PasswordEncoder la rifiuterebbe con un'eccezione */
    public static boolean superaIlLimiteDiBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTE;
    }

    public List<Utente> findAll() {
        return utenteRepository.findAll();
    }

    /**
     * Email sempre minuscola e senza spazi: evita doppioni tipo "Mario@x.it" / "mario@x.it".
     * Locale.ROOT: senza, con la lingua del server impostata sul turco la «I» diventerebbe «ı» e la stessa email
     * si salverebbe in modo diverso a seconda della macchina.
     */
    public static String normalizza(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
