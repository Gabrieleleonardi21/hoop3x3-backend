package com.hoop3x3.backend.dto;

import com.hoop3x3.backend.entities.Regole;
import jakarta.validation.constraints.Min;

public record RegoleDTO(@Min(1) int target, @Min(1) int durata, @Min(1) int ot, @Min(1) int shot) {
    public static RegoleDTO from(Regole r) {
        return new RegoleDTO(r.getTarget(), r.getDurata(), r.getOt(), r.getShot());
    }

    public Regole toEntity() {
        Regole r = new Regole();
        r.setTarget(target);
        r.setDurata(durata);
        r.setOt(ot);
        r.setShot(shot);
        return r;
    }
}
