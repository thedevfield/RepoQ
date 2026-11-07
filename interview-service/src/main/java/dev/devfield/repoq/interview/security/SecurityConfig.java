package dev.devfield.repoq.interview.security;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, DeletionCredentials credentials) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/internal/projects/*/interviews")
                        .access((auth, context) -> new AuthorizationDecision(credentials.permitsProject(context.getRequest())))
                        .requestMatchers(HttpMethod.DELETE, "/internal/users/*/data")
                        .access((auth, context) -> new AuthorizationDecision(credentials.permitsAuth(context.getRequest())))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${devfield.interview.auth.jwks-uri}") String jwksUri,
                          @Value("${devfield.interview.auth.issuer}") String issuer,
                          @Value("${devfield.interview.auth.audience}") String audience) {
        if (jwksUri.isBlank() || issuer.isBlank() || audience.isBlank())
            return token -> { throw new BadJwtException("Interview JWT verification unavailable"); };
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
}
