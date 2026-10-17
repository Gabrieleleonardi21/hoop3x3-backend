package com.hoop3x3.backend.services;

import java.util.Base64;

/**
 * La foto di un foglio già controllata da FoglioService: i byte decodificati e il loro tipo (image/jpeg, image/png,
 * image/webp). Vive solo per la durata della richiesta: non si salva e non si logga.
 */
record ImmagineFoglio(byte[] dati, String mime) {

    /** I byte in base64, come li vogliono i fornitori */
    String base64() {
        return Base64.getEncoder().encodeToString(dati);
    }

    /** Il toString() automatico non scrive i byte, ma un'eccezione o un log con il record non devono dire nulla di più */
    @Override
    public String toString() {
        return "ImmagineFoglio[" + mime + ", " + dati.length + " byte]";
    }
}
