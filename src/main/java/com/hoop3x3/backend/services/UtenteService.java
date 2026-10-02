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

@Service
public class UtenteService implements UserDetailsService {

    /** BCrypt legge al massimo 72 byte: oltre, il PasswordEncoder lancia IllegalArgumentException (un 500 al client) */
    private static final int PASSWORD_MAX_BYTE = 72;

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
        // Il DTO conta i caratteri, BCrypt i byte: una lettera accentata ne occupa 2 e un emoji 4, quindi 40 lettere
        // accentate (80 byte) passano la validazione ma non l'encoder. Si controlla prima di toccare il database
        if (dto.password().getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTE) {
            throw new BadRequestException("La password supera i " + PASSWORD_MAX_BYTE
                    + " byte: accorciala (le lettere accentate occupano 2 byte, gli emoji 4)");
        }
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
