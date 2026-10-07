package com.hoop3x3.backend.entities;

import com.hoop3x3.backend.support.Tempo;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Il segno che un seed è stato eseguito (tabella seed_eseguiti, migrazione V3): il nome del seed e quando. Serve perché i
 * dati che un seed inserisce possono sparire senza che il seed debba ripartire: eliminando la lega demo spariscono le sue
 * tappe e l'archivio, ma i giocatori e le squadre demo restano (hanno id generati), e senza il segno al riavvio il seed li
 * inserirebbe una seconda volta. Il nome è quello dell'operazione («demo»), non dei dati: regge anche se i dati cambiano.
 * È l'unico punto in cui il perché è scritto per intero: DemoSeeder, la migrazione V3, il README e i test rimandano qui.
 */
@Entity
@Table(name = "seed_eseguiti")
@Getter
public class SeedEseguito {

    @Id
    private String nome;

    @Column(name = "eseguito_il", nullable = false)
    private LocalDateTime eseguitoIl;

    public SeedEseguito() {}

    public SeedEseguito(String nome) {
        this.nome = nome;
        this.eseguitoIl = Tempo.adesso();
    }
}
