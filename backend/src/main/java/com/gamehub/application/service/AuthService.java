package com.gamehub.application.service;

import com.gamehub.api.dto.AuthDtos.AuthResponse;
import com.gamehub.api.dto.AuthDtos.LoginRequest;
import com.gamehub.api.dto.AuthDtos.RefreshRequest;
import com.gamehub.api.dto.AuthDtos.RegisterRequest;
import com.gamehub.api.dto.UserProfileResponse;
import com.gamehub.api.error.ErrorCode;
import com.gamehub.api.security.JwtService;
import com.gamehub.application.exception.GameHubException;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.application.exception.ValidationException;
import com.gamehub.domain.common.Region;
import com.gamehub.infrastructure.persistence.entity.UserEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import com.gamehub.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Registration, login and token refresh. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * A real BCrypt hash of a value nobody knows, used only to burn comparable
     * CPU on the unknown-user path as on the wrong-password path.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.3Ur5vQMH6YYq3ZTKDQ7Nn9eJmWTQbDy";

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Creates a user and its profile in one transaction.
     *
     * <p>Both or neither. A user with no profile would authenticate
     * successfully and then fail on every gameplay request, which is a far
     * more confusing failure than not being registered at all.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Region region = parseRegion(request.region());

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        UserEntity saved;
        try {
            saved = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // The unique indexes on username and email are what actually
            // prevent duplicates under concurrent signup. A pre-check would
            // race; this does not.
            //
            // The message deliberately does not name the colliding field:
            // that would turn registration into an account-enumeration oracle.
            throw new DuplicateAccountException();
        }

        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(saved.getId());
        profile.setDisplayName(request.displayName());
        profile.setRegion(region);
        profiles.save(profile);

        log.info("registered user {}", saved.getId());
        return issueTokens(saved, profile);
    }

    /**
     * Verifies credentials and issues tokens.
     *
     * <p>A missing user still costs a password comparison. Returning early
     * would make an unknown username measurably faster than a wrong password,
     * a timing side channel that leaks which accounts exist. The dummy hash
     * keeps both paths on the same order of cost.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        UserEntity user = users.findByUsername(request.username()).orElse(null);

        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (user.getStatus() != UserEntity.UserStatus.ACTIVE) {
            // Same generic error. Telling a suspended account that it is
            // suspended also confirms the credentials were correct.
            throw new InvalidCredentialsException();
        }

        user.setLastLoginAt(Instant.now());
        UserProfileEntity profile = profiles.findById(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("profile", user.getId()));

        return issueTokens(user, profile);
    }

    /**
     * Exchanges a refresh token for a new access token.
     *
     * <p>The user record is re-read rather than trusted from the token. That
     * is what makes refresh tokens revocable: a suspended account stops
     * receiving new access tokens at its next refresh, even though the refresh
     * token is still cryptographically valid.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        UUID userId = jwtService.verifyRefreshToken(request.refreshToken())
                .orElseThrow(InvalidCredentialsException::new);

        UserEntity user = users.findById(userId).orElseThrow(InvalidCredentialsException::new);
        if (user.getStatus() != UserEntity.UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        UserProfileEntity profile = profiles.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("profile", userId));

        return issueTokens(user, profile);
    }

    private AuthResponse issueTokens(UserEntity user, UserProfileEntity profile) {
        String access = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), user.getRoles());
        String refresh = jwtService.issueRefreshToken(user.getId());

        return AuthResponse.bearer(access, refresh,
                jwtService.accessTokenTtlSeconds(), toProfileResponse(user, profile));
    }

    static UserProfileResponse toProfileResponse(UserEntity user, UserProfileEntity profile) {
        return new UserProfileResponse(
                profile.getUserId(), user.getUsername(), profile.getDisplayName(),
                profile.getAvatarUrl(), profile.getRegion().name(), profile.getSkillRating(),
                profile.getLevel(), profile.getXp(), profile.getGamesPlayed(),
                profile.getGamesWon(), profile.winRate(),
                profile.getTotalPlaytimeSeconds(), profile.getCreatedAt());
    }

    private Region parseRegion(String raw) {
        try {
            return Region.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "region must be one of " + Arrays.toString(Region.values()));
        }
    }

    /** 401 for every credential failure, with one indistinguishable message. */
    public static class InvalidCredentialsException extends GameHubException {
        public InvalidCredentialsException() {
            super(401, ErrorCode.UNAUTHORIZED, "invalid username or password");
        }
    }

    /** 409 naming no field, so registration cannot enumerate accounts. */
    public static class DuplicateAccountException extends GameHubException {
        public DuplicateAccountException() {
            super(409, ErrorCode.CONFLICT, "an account with those details already exists");
        }
    }
}
