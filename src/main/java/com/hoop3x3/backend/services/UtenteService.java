package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.entities.Ruolo;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.ConflictException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UtenteService implements UserDetailsService {

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
        String email = normalizza(dto.email());
        if (utenteRepository.existsByEmail(email)) {
            throw new ConflictException("Email " + email + " già registrata");
        }
        Utente utente = new Utente(email, passwordEncoder.encode(dto.password()), dto.name().trim(), Ruolo.USER);
        return utenteRepository.save(utente);
    }

    public List<Utente> findAll() {
        return utenteRepository.findAll();
    }

    /** Email sempre minuscola e senza spazi: evita doppioni tipo "Mario@x.it" / "mario@x.it" */
    public static String normalizza(String email) {
        return email.trim().toLowerCase();
    }
}
