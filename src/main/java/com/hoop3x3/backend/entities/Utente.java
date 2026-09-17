package com.hoop3x3.backend.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "utenti")
@Getter
@Setter
public class Utente implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    @JsonIgnore
    private String password;

    /** Nome utente mostrato nell'app (es. "Gabriele"): il frontend lo usa come "autore" delle voci. */
    @Column(nullable = false)
    private String nome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Ruolo ruolo;

    @Column(name = "creato_il", nullable = false, updatable = false)
    private LocalDateTime creatoIl;

    @Column(name = "modificato_il", nullable = false)
    private LocalDateTime modificatoIl;

    @PrePersist
    private void onCreazione() {
        this.creatoIl = LocalDateTime.now();
        this.modificatoIl = LocalDateTime.now();
    }

    @PreUpdate
    private void onModifica() {
        this.modificatoIl = LocalDateTime.now();
    }

    public Utente() {}

    public Utente(String email, String password, String nome, Ruolo ruolo) {
        this.email = email;
        this.password = password;
        this.nome = nome;
        this.ruolo = ruolo;
    }

    public boolean isAdmin() {
        return this.ruolo == Ruolo.ADMIN;
    }

    // Il prefisso "ROLE_" è obbligatorio: hasRole('ADMIN') cerca l'authority "ROLE_ADMIN"
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + this.ruolo.name()));
    }

    @Override
    public String getUsername() {
        return this.email;
    }
}
