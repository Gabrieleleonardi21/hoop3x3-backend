package com.hoop3x3.backend.services;

import com.hoop3x3.backend.dto.RegisterRequestDTO;
import com.hoop3x3.backend.entities.Utente;
import com.hoop3x3.backend.exceptions.BadRequestException;
import com.hoop3x3.backend.repositories.UtenteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Repository e PasswordEncoder sono simulati: si controllano le regole di register, non il database. */
class UtenteServiceTest {

    private final UtenteRepository repository = mock(UtenteRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UtenteService service = new UtenteService(repository, encoder);

    static Stream<String> passwordOltre72Byte() {
        return Stream.of(
                "è".repeat(40),       // 80 byte: il caso del piano, 40 caratteri per il DTO
                "è".repeat(36) + "x", // 73 byte: appena oltre il limite
                "😀".repeat(19));     // 76 byte: ogni emoji ne occupa 4 e per il DTO sono 2 caratteri
    }

    @ParameterizedTest
    @MethodSource("passwordOltre72Byte")
    void passwordOltre72Byte_siRifiutaConUnMessaggioDedicatoSenzaToccareDatabaseEPasswordEncoder(String password) {
        RegisterRequestDTO dto = new RegisterRequestDTO("Mario", "mario@x.it", password);

        assertThatThrownBy(() -> service.register(dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("72 byte");
        verifyNoInteractions(repository, encoder);
    }

    @Test
    void passwordDiEsattamente72Byte_siAccetta() {
        String password = "è".repeat(36); // 72 byte
        when(encoder.encode(password)).thenReturn("hash");
        when(repository.save(any(Utente.class))).thenAnswer(chiamata -> chiamata.getArgument(0));

        Utente utente = service.register(new RegisterRequestDTO("Mario", "mario@x.it", password));

        assertThat(utente.getPassword()).isEqualTo("hash");
    }

    // In turco la «I» maiuscola diventa «ı» (senza puntino): con toLowerCase() senza Locale la stessa email si
    // normalizzerebbe in modo diverso a seconda della lingua della macchina su cui gira il server
    @Test
    void normalizzaNonDipendeDallaLinguaDellaMacchina() {
        Locale linguaIniziale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(UtenteService.normalizza("  INFO@Hoop3x3.IT ")).isEqualTo("info@hoop3x3.it");
        } finally {
            Locale.setDefault(linguaIniziale); // la lingua è dell'intera JVM: si rimette com'era per gli altri test
        }
    }
}
