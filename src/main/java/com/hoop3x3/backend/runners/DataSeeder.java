package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.UtenteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea l'utente ADMIN iniziale al primo avvio: la registrazione assegna sempre USER,
 * quindi senza seeder nessuno potrebbe amministrare. Credenziali in env.properties;
 * senza email il seeder non fa nulla; con la password mancante o debole non crea l'admin e lo scrive nei log.
 */
@Component
@Order(1) // prima di DemoSeeder, che intesta i dati di prova all'admin
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int LUNGHEZZA_MINIMA_PASSWORD = 8;
    /** La password che il vecchio env.properties.example proponeva: è pubblica, quindi non vale come password dell'admin */
    private static final String PASSWORD_DI_ESEMPIO = "admin123";

    private final UtenteRepository utenteRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.admin.email:}")
    private String adminEmail;
    @Value("${seed.admin.password:}")
    private String adminPassword;

    public DataSeeder(UtenteRepository utenteRepository, PasswordEncoder passwordEncoder) {
        this.utenteRepository = utenteRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        // Senza email il seeder è spento (nessun admin voluto): resta in silenzio, come nei test di integrazione
        if (adminEmail.isBlank()) return;
        String email = UtenteService.normalizza(adminEmail);
        if (utenteRepository.existsByEmail(email)) return;
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
