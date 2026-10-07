package com.hoop3x3.backend.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Un campo di testo facoltativo come si salva: le colonne sono NOT NULL DEFAULT '' */
class TestoTest {

    @Test
    void unCampoAssenteDiventaVuoto() {
        assertThat(Testo.ripulito(null)).isEmpty();
    }

    @Test
    void gliSpaziInTestaEInCodaSiTolgono() {
        assertThat(Testo.ripulito("  Roma  ")).isEqualTo("Roma");
        assertThat(Testo.ripulito("   ")).isEmpty();
    }
}
