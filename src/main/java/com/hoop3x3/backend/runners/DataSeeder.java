package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.UtenteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea l'utente ADMIN iniziale al primo avvio: la registrazione assegna sempre USER,
 * quindi senza seeder nessuno potrebbe amministrare. Credenziali in env.properties.
 * Quando non crea l'admin lo scrive sempre nei log, con il motivo: una riga INFO se non è un errore (email non impostata,
 * admin già presente), un avviso se la configurazione è sbagliata (password mancante o debole, password senza email).
 */
@Slf4j
@Component
@Order(1) // prima di DemoSeeder, che intesta i dati di prova all'admin
@EnableConfigurationProperties(SeedProperties.class)
public class DataSeeder implements CommandLineRunner {

    private static final int LUNGHEZZA_MINIMA_PASSWORD = 8;
    /** La password che il vecchio env.properties.example proponeva: è pubblica, quindi non vale come password dell'admin */
    private static final String PASSWORD_DI_ESEMPIO = "admin123";

    private final UtenteRepository utenteRepository;
    private final PasswordEncoder passwordEncoder;

    // ADMIN_EMAIL e ADMIN_PASSWORD (seed.admin.*)
    private final String adminEmail;
    private final String adminPassword;

    public DataSeeder(UtenteRepository utenteRepository, PasswordEncoder passwordEncoder, SeedProperties proprieta) {
        this.utenteRepository = utenteRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = proprieta.admin().email();
        this.adminPassword = proprieta.admin().password();
    }

    @Override
    public void run(String... args) {
        // Senza email il seeder è spento (nessun admin voluto, come nei test di integrazione): non è un errore. Una password
        // senza email invece è quasi certamente un'email dimenticata: l'admin non nascerebbe e nessuna riga direbbe perché
        if (adminEmail.isBlank()) {
            if (adminPassword.isBlank()) {
                log.info("Admin non creato: ADMIN_EMAIL non è impostata");
            } else {
                log.warn("Admin non creato: ADMIN_PASSWORD è impostata ma ADMIN_EMAIL è vuota");
            }
            return;
        }
        String email = UtenteService.normalizza(adminEmail);
        if (utenteRepository.existsByEmail(email)) {
            log.info("Admin non creato: esiste già un utente con l'email di ADMIN_EMAIL");
            return;
        }
        // L'admin ha pieni poteri: con una password mancante (è lo stato di env.properties.example appena copiato),
        // corta o uguale a quella dell'esempio pubblico non lo si crea. Nel log mai il valore della password, solo la regola
        if (adminPassword.isBlank() || adminPassword.length() < LUNGHEZZA_MINIMA_PASSWORD
                || PASSWORD_DI_ESEMPIO.equals(adminPassword)) {
            log.warn("Admin non creato: ADMIN_PASSWORD deve avere almeno {} caratteri ed essere diversa dalla password d'esempio",
                    LUNGHEZZA_MINIMA_PASSWORD);
            return;
        }
        utenteRepository.save(new Utente(email, passwordEncoder.encode(adminPassword), "Admin", Ruolo.ADMIN));
    }
}
