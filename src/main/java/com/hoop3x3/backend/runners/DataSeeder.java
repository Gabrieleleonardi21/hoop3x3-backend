package com.hoop3x3.backend.runners;

import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.services.UtenteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea l'utente ADMIN iniziale al primo avvio: la registrazione assegna sempre USER,
 * quindi senza seeder nessuno potrebbe amministrare. Credenziali in env.properties;
 * se mancano il seeder non fa nulla.
 */
@Component
@Order(1) // prima di DemoSeeder, che intesta i dati di prova all'admin
public class DataSeeder implements CommandLineRunner {

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
        if (adminEmail.isBlank() || adminPassword.isBlank()) return;
        String email = UtenteService.normalizza(adminEmail);
        if (utenteRepository.existsByEmail(email)) return;
        utenteRepository.save(new Utente(email, passwordEncoder.encode(adminPassword), "Admin", Ruolo.ADMIN));
    }
}
