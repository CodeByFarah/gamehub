package com.gamehub.config;

import com.gamehub.api.security.JwtAuthenticationFilter;
import com.gamehub.config.properties.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * HTTP security.
 *
 * <p>Stateless. No sessions, no CSRF tokens, no server-side login state. That
 * is what allows any instance to serve any request, which is the requirement
 * behind horizontal scaling.
 */
@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final SecurityProperties properties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF disabled, and this is safe only because the API is
            // stateless and authenticates with an Authorization header. CSRF
            // attacks rely on the browser attaching an ambient credential such
            // as a cookie; a header a site cannot set is not ambient. If this
            // API ever issues a session cookie, CSRF must come back.
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Health probes, unauthenticated so a load balancer can
                    // reach them. Deliberately narrower than /actuator/**,
                    // which would expose metrics and configuration.
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    // Prometheus scrape. In Cloud Run this is reachable only
                    // from inside the VPC; locally it is open for convenience.
                    .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                    .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh")
                        .permitAll()
                    // Browsing the catalogue needs no account. Anything that
                    // writes to it does.
                    .requestMatchers(HttpMethod.GET, "/api/games", "/api/games/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/games").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/games/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/games/**").hasRole("ADMIN")
                    // Everything else requires a valid access token. Default
                    // deny: a new endpoint is protected unless someone
                    // deliberately opens it, which is the correct direction
                    // for a mistake to fail in.
                    .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /**
     * BCrypt at the configured cost.
     *
     * <p>Cost 12 rather than the Spring default of 10. Each increment doubles
     * the work per guess; at 12 a hash costs roughly a quarter second of CPU,
     * which is tolerable on a login path and expensive at offline-cracking
     * scale. Higher would start to make login itself a denial-of-service
     * vector against our own API.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(properties.getBcryptStrength());
    }

    /**
     * CORS, closed by default.
     *
     * <p>allowedOrigins is empty unless configured. The Android client does
     * not use CORS at all, so the permissive wildcard that usually appears
     * here would be pure attack surface with no consumer.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
