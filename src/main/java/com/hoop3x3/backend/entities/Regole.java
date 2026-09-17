package com.hoop3x3.backend.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

/** Regole FIBA 3x3 della tappa, salvate come colonne regole_* di `tappe`. */
@Embeddable
@Getter
@Setter
public class Regole {

    /** punteggio vittoria (FIBA 3x3: 21) */
    @Column(name = "regole_target", nullable = false)
    private int target = 21;

    /** durata in minuti (FIBA 3x3: 10) */
    @Column(name = "regole_durata", nullable = false)
    private int durata = 10;

    /** punti per vincere il supplementare (FIBA 3x3: 2) */
    @Column(name = "regole_ot", nullable = false)
    private int ot = 2;

    /** secondi di possesso (FIBA 3x3: 12) */
    @Column(name = "regole_shot", nullable = false)
    private int shot = 12;
}
