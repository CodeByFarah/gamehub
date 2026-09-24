package com.gamehub.api.security;

import com.gamehub.config.properties.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies JWTs.
 *
 * <h2>Why two token types</h2>
 * Access tokens are short-lived and not revocable; refresh tokens are
 * long-lived and are revocable because they are checked against the user
 * record on use. That pairing is the whole point. A single long-lived token
 * would be both unrevocable and valuable for a long time, which is the worst
 * of both.
 *
 * <p>The token type is a claim, and it is checked on every verification.
 * Without that check a refresh token would be accepted as an access token,
 * which would hand an attacker a 30-day API credential instead of a
 * 15-minute one.
 *
 * <h2>Why HS256 and not RS256</h2>
 * There is one service issuing and one service verifying, so there is no third
 * party that needs a public key. Asymmetric signing buys nothing here and adds
 * key distribution. This becomes the wrong answer the moment a second service
 * needs to verify tokens without being able to mint them, and that is recorded
 * in docs/security.md rather than left as an assumption.
 */
@Slf4j
@Service
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_ROLES = "roles";

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey signingKey;
    private final SecurityProperties properties;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        // Throws if the configured secret is too short for HS256. Failing here
        // means a weak key stops the instance starting rather than silently
        // producing forgeable tokens.
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(UUID userId, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())))
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLES, roles)
                // A unique id per token, so a future revocation list has
                // something to key on without invalidating every token a user
                // holds.
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    /**
     * Refresh tokens deliberately carry no roles or username.
     *
     * <p>They are exchanged for an access token, and that exchange re-reads
     * the user record. Baking roles into a 30-day token would mean a
     * revoked administrator kept their privileges for a month.
     */
    public String issueRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getRefreshTokenTtl())))
                .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact();
    }

    public Optional<AuthenticatedUser> verifyAccessToken(String token) {
        return parse(token, TYPE_ACCESS).map(claims -> new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                readRoles(claims)));
    }

    public Optional<UUID> verifyRefreshToken(String token) {
        return parse(token, TYPE_REFRESH).map(claims -> UUID.fromString(claims.getSubject()));
    }

    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    /**
     * Parses and validates, returning empty for every failure mode.
     *
     * <p>Every failure is treated identically on purpose. Distinguishing
     * "expired" from "bad signature" from "wrong issuer" in the response would
     * tell an attacker which part of a forged token to fix next. The
     * distinction is kept in the log, where only we can see it.
     */
    private Optional<Claims> parse(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // The check that stops a refresh token being used as an access
            // token. Without it the type claim would be decorative.
            if (!expectedType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                log.debug("rejected a token of the wrong type");
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ExpiredJwtException e) {
            log.debug("rejected an expired token");
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            // Includes signature failures, which are the interesting case.
            log.debug("rejected an invalid token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        return raw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of("ROLE_USER");
    }

    /** The authenticated principal, carrying only what authorisation needs. */
    public record AuthenticatedUser(UUID userId, String username, List<String> roles) {
    }
}
