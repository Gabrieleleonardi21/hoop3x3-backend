package com.hoop3x3.backend.security;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // attiva @PreAuthorize sui controller (endpoint solo ADMIN)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // AuthController lo inietta per verificare email + password al login
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // Orologio di LimiteRichiesteFilter: è un bean perché i test lo sostituiscono con uno che si sposta a comando e provano
    // le finestre di un minuto e di un giorno senza aspettare
    @Bean
    public Clock orologio() {
        return Clock.systemUTC();
    }

    // JwtFilter e LimiteRichiesteFilter sono @Component: senza queste due registrazioni disattivate Spring Boot li metterebbe
    // anche tra i filtri del container, e ogni richiesta li incontrerebbe due volte (la seconda non fa danni solo perché sono
    // OncePerRequestFilter). Devono girare solo nella catena di sicurezza qui sotto, come raccomanda Spring Security
    @Bean
    public FilterRegistrationBean<JwtFilter> jwtFilterSoloNellaCatena(JwtFilter filtro) {
        return registrazioneDisattivata(filtro);
    }

    @Bean
    public FilterRegistrationBean<LimiteRichiesteFilter> limiteFilterSoloNellaCatena(LimiteRichiesteFilter filtro) {
        return registrazioneDisattivata(filtro);
    }

    private static <T extends Filter> FilterRegistrationBean<T> registrazioneDisattivata(T filtro) {
        FilterRegistrationBean<T> registrazione = new FilterRegistrationBean<>(filtro);
        registrazione.setEnabled(false);
        return registrazione;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtFilter jwtFilter, LimiteRichiesteFilter limiteFilter,
                                                   JsonAuthEntryPoint entryPoint) throws Exception {
        http
                // usa il bean CorsConfigurationSource di CorsConfig e risponde da solo al preflight OPTIONS
                .cors(Customizer.withDefaults())
                // API stateless: le richieste autenticate usano il Bearer; l'unico cookie (refresh token) è
                // SameSite=Lax e lo leggono solo /api/auth/refresh e /api/auth/logout, quindi il CSRF non si applica
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // richiesta anonima su endpoint protetto → 401 JSON (non il 403 di default)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(authz -> authz
                        // refresh e logout si autenticano con il cookie httpOnly, non con il Bearer
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        // anagrafe e archivio sono pubblici in lettura: l'ospite (senza account) li consulta
                        .requestMatchers(HttpMethod.GET, "/api/anagrafe/**", "/api/archivio/**").permitAll()
                        // controlli di salute: /actuator/health (con il database, lo aspetta il frontend all'avvio) e
                        // /actuator/health/liveness (solo il processo, per Render). Rispondono {"status":"UP"} o
                        // {"status":"DOWN"}, senza dettagli
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness").permitAll()
                        // il forward interno verso /error dopo un sendError va lasciato passare, altrimenti 403 vuoto
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                // il JwtFilter deve girare PRIMA del controllo di autorizzazione
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // limiti di frequenza: subito dopo il JwtFilter (il perché, in LimiteRichiesteFilter)
                .addFilterAfter(limiteFilter, JwtFilter.class);
        return http.build();
    }
}
