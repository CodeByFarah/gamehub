package com.gamehub.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Authentication payloads, grouped because they are only ever used together.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * @param password minimum 12 characters, with no composition rules.
     *                 Length dominates character-class requirements for real
     *                 resistance to guessing, and composition rules push people
     *                 towards predictable substitutions. Upper bound is there
     *                 to stop a multi-megabyte password turning BCrypt into a
     *                 denial-of-service vector.
     */
    public record RegisterRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_.-]{3,32}$",
                     message = "username must be 3 to 32 characters of letters, digits, dot, underscore or hyphen")
            String username,

            @NotBlank @Email(message = "a valid email is required")
            @Size(max = 254, message = "email is too long")
            String email,

            @NotBlank
            @Size(min = 12, max = 128, message = "password must be between 12 and 128 characters")
            String password,

            @NotBlank @Size(max = 48) String displayName,

            @NotBlank String region) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank @Size(max = 128) String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /**
     * @param expiresInSeconds lifetime of the access token, so the client can
     *                         refresh proactively rather than waiting for a
     *                         401 and retrying every in-flight request
     */
    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            UserProfileResponse profile) {

        public static AuthResponse bearer(String access, String refresh,
                                          long expiresInSeconds, UserProfileResponse profile) {
            return new AuthResponse(access, refresh, "Bearer", expiresInSeconds, profile);
        }
    }
}
