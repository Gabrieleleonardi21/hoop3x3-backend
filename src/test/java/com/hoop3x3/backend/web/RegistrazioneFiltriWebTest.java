package com.hoop3x3.backend.web;

import com.hoop3x3.backend.controllers.AuthController;
import com.hoop3x3.backend.exceptions.ExceptionsHandler;
import com.hoop3x3.backend.repositories.UtenteRepository;
import com.hoop3x3.backend.security.AuthCookies;
import com.hoop3x3.backend.security.CorsConfig;
import com.hoop3x3.backend.security.JwtTools;
import com.hoop3x3.backend.security.JsonAuthEntryPoint;
import com.hoop3x3.backend.security.JwtFilter;
import com.hoop3x3.backend.security.LimiteDimensioneFilter;
import com.hoop3x3.backend.security.LimiteRichiesteFilter;
import com.hoop3x3.backend.security.SecurityConfig;
import com.hoop3x3.backend.services.RefreshTokenService;
import com.hoop3x3.backend.services.UtenteService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtFilter e LimiteRichiesteFilter sono {@code @Component}: Spring Boot registra ogni filtro che trova tra i bean anche nel
 * container, quindi senza una {@code FilterRegistrationBean} disattivata (SecurityConfig) questi due girerebbero due volte per
 * richiesta, una nella catena di sicurezza e una nel container. Oggi la seconda esecuzione non fa danni solo perché sono
 * {@code OncePerRequestFilter}: l'effetto non si vede dall'esterno, quindi non c'è una richiesta che lo provi.
 * <p>
 * Si chiede allora a {@code ServletContextInitializerBeans}, la classe con cui Boot decide che cosa registrare nel container,
 * quali filtri registrerebbe davvero. LimiteDimensioneFilter è il controllo: non sta nella catena di sicurezza e deve restare
 * registrato nel container, altrimenti il test non vedrebbe niente e passerebbe a vuoto.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, CorsConfig.class, JwtFilter.class, JwtTools.class, JsonAuthEntryPoint.class,
        AuthCookies.class, ExceptionsHandler.class, LimiteDimensioneFilter.class})
@TestPropertySource(properties = {"jwt.secret=0123456789abcdef0123456789abcdef", "cors.origins=http://localhost:5173"})
class RegistrazioneFiltriWebTest {

    @Autowired ListableBeanFactory contesto;
    // Dipendenze di AuthController e di JwtFilter
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean UtenteService utenteService;
    @MockitoBean RefreshTokenService refreshTokenService;
    @MockitoBean UtenteRepository utenteRepository;

    @Test
    void iFiltriDiSicurezzaNonSonoRegistratiAncheNelContainer() {
        List<Filter> registrati = new ArrayList<>();
        // Boot adatta a una registrazione ogni filtro senza una sua; una registrazione disattivata non arriva al container
        for (ServletContextInitializer iniziatore : new ServletContextInitializerBeans(contesto)) {
            if (iniziatore instanceof FilterRegistrationBean<?> registrazione && registrazione.isEnabled()) {
                registrati.add(registrazione.getFilter());
            }
        }

        assertThat(registrati).anyMatch(LimiteDimensioneFilter.class::isInstance);
        assertThat(registrati).noneMatch(JwtFilter.class::isInstance);
        assertThat(registrati).noneMatch(LimiteRichiesteFilter.class::isInstance);
    }
}
