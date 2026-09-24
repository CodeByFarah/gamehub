package com.gamehub.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Authentication and rate-limiting configuration, bound from the
 * gamehub.security prefix.
 *
 * <h2>Why the secret has no default</h2>
 * {@link #jwtSecret} is @NotBlank with a minimum length and no fallback value.
 * A development default would inevitably reach production, and a JWT signing
 * key that is public in a git history is equivalent to no authentication at
 * all: anyone can mint a token for any user id. Refusing to start is the only
 * safe behaviour, so a missing JWT_SECRET is a startup failure with a readable
 * message rather than a silently insecure boot.
 *
 * <p>The 32-character floor is the HS256 requirement. A key shorter than the
 * hash output weakens the MAC, and jjwt rejects it at signing time; catching it
 * at startup turns a runtime 500 on the first login into a clear boot error.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gamehub.security")
public class SecurityProperties {

    /**
     * HS256 signing key, injected from the JWT_SECRET environment variable.
     * No default. See the class comment.
     */
    @NotBlank
    @Size(min = 32, message = "jwtSecret must be at least 32 characters for HS256")
    private String jwtSecret;

    /**
     * Access token lifetime.
     *
     * <p>Short, because access tokens are not revocable: the only thing
     * limiting the damage of a stolen token is how quickly it expires. Refresh
     * tokens are long-lived but revocable, which is the opposite trade and the
     * reason the pair exists.
     */
    @NotNull
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    @NotNull
    private Duration refreshTokenTtl = Duration.ofDays(30);

    @NotBlank
    private String issuer = "gamehub";

    /**
     * BCrypt cost factor.
     *
     * <p>12 rather than the Spring default of 10. Each increment doubles the
     * work an attacker must do per guess, and at 12 a hash costs roughly a
     * quarter-second of CPU, which is tolerable on a login path and painful at
     * offline-cracking scale. Raising it further would start to make login a
     * denial-of-service vector against our own API.
     */
    @Min(4)
    private int bcryptStrength = 12;

    /** Requests per window per authenticated principal. */
    @Min(1)
    private int rateLimitRequestsPerWindow = 300;

    /**
     * Tighter budget for the AI endpoints.
     *
     * <p>Separate from the general limit because these calls cost real money
     * per request and are far slower than the rest of the API. One limit for
     * both would have to be set at the generous end for normal traffic and
     * would leave the expensive endpoints unprotected.
     */
    @Min(1)
    private int aiRateLimitRequestsPerWindow = 20;

    /**
     * CORS origins permitted to call the API.
     *
     * <p>Empty by default. The Android client does not need CORS at all, so the
     * permissive wildcard that usually creeps in here has no justification;
     * origins are added explicitly when a web client actually exists.
     */
    private List<String> allowedOrigins = List.of();

    /**
     * Never let this object print the signing key.
     */
    @Override
    public String toString() {
        return "SecurityProperties(issuer=" + issuer
                + ", accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtl=" + refreshTokenTtl
                + ", bcryptStrength=" + bcryptStrength
                + ", jwtSecret=<redacted>)";
    }
}
