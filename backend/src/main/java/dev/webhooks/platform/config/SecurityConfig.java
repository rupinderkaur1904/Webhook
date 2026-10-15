package dev.webhooks.platform.config;

import dev.webhooks.platform.auth.ApiKeyAuthenticationFilter;
import dev.webhooks.platform.auth.ApiKeyRepository;
import dev.webhooks.platform.auth.ProblemAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 1 security: stateless API-key authentication for every {@code /api/**} route. Dashboard
 * login with server-side sessions and CSRF protection is added in the security-hardening stage.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyRepository apiKeys, ObjectMapper objectMapper)
            throws Exception {
        var entryPoint = new ProblemAuthenticationEntryPoint(objectMapper);
        http
                .csrf(csrf -> csrf.disable()) // no cookies are issued: API keys travel in a header only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys, entryPoint), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
