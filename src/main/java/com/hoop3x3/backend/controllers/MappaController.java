package com.hoop3x3.backend.controllers;

import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.services.MappaCampiService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * La mappa di un punto per i campetti: pubblica, come la GET dei campetti (vedi SecurityConfig). Il client sceglie solo il
 * punto e lo zoom: la dimensione e la scala le fissa il server. I controlli stanno qui, prima del servizio, così un parametro
 * sbagliato non arriva mai a Google né alla chiave.
 */
@RestController
@RequestMapping("/api/mappa-campetti")
public class MappaController {

    /** Il limite di latitudine della proiezione Mercator, quello che la mappa di Google accetta */
    static final double LATITUDINE_MASSIMA = 85.0511;
    static final int ZOOM_MINIMO = 1;
    static final int ZOOM_MASSIMO = 18;
    /** Il browser e le reti tengono l'immagine un giorno: la stessa posizione non torna a Google */
    private static final Duration CACHE_DEL_BROWSER = Duration.ofDays(1);

    private final MappaCampiService mappa;

    public MappaController(MappaCampiService mappa) {
        this.mappa = mappa;
    }

    /** GET /api/mappa-campetti?lat=&lng=&zoom=: l'immagine PNG della mappa del punto */
    @GetMapping
    public ResponseEntity<byte[]> mappa(@RequestParam(required = false) Double lat,
                                        @RequestParam(required = false) Double lng,
                                        @RequestParam(required = false) Integer zoom) {
        if (lat == null || lng == null || zoom == null) {
            throw new BadRequestException("Indica lat, lng e zoom (la mappa di un punto)");
        }
        // I confronti negati respingono anche NaN, che passerebbe un «lat < -85 || lat > 85»
        if (!(lat >= -LATITUDINE_MASSIMA && lat <= LATITUDINE_MASSIMA)) {
            throw new BadRequestException("lat: deve essere tra -85.0511 e 85.0511");
        }
        if (!(lng >= -180 && lng <= 180)) throw new BadRequestException("lng: deve essere tra -180 e 180");
        if (zoom < ZOOM_MINIMO || zoom > ZOOM_MASSIMO) {
            throw new BadRequestException("zoom: deve essere un numero intero tra 1 e 18");
        }
        byte[] png = mappa.immagine(lat, lng, zoom);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=" + CACHE_DEL_BROWSER.toSeconds())
                .body(png);
    }
}
