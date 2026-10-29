package dev.devfield.repoq.project.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> jsonError(response, 401, "invalid_token"))
                        .accessDeniedHandler((request, response, error) -> jsonError(response, 403, "forbidden")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/projects", "/api/projects/*",
                                "/api/projects/*/repositories", "/api/projects/*/repositories/*").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${devfield.project.auth.jwks-uri}") String jwksUri,
                          @Value("${devfield.project.auth.issuer}") String issuer,
                          @Value("${devfield.project.auth.audience}") String audience) {
        if (jwksUri.isBlank() || issuer.isBlank() || audience.isBlank())
            throw new IllegalArgumentException("Project JWT configuration is required");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(jwt -> {
            var result = standard.validate(jwt);
            if (result.hasErrors()) return result;
            try {
                UUID.fromString(jwt.getSubject());
                UUID.fromString(jwt.getClaimAsString("sid"));
                if (!jwt.getAudience().contains(audience) || jwt.getIssuedAt() == null ||
                        jwt.getNotBefore() == null || jwt.getExpiresAt() == null ||
                        jwt.getId() == null || jwt.getId().isBlank() ||
                        !"JWT".equals(jwt.getHeaders().get("typ"))) throw new IllegalArgumentException();
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException e) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
        });
        return decoder;
    }

    private static void jsonError(HttpServletResponse response, int status, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
