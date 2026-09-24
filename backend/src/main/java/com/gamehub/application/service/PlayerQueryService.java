package com.gamehub.application.service;

import com.gamehub.api.dto.AchievementResponse;
import com.gamehub.api.dto.GameSummaryResponse;
import com.gamehub.api.dto.RecommendationResponse;
import com.gamehub.api.dto.UserProfileResponse;
import com.gamehub.application.exception.ResourceNotFoundException;
import com.gamehub.infrastructure.persistence.entity.AchievementEntity;
import com.gamehub.infrastructure.persistence.entity.RecommendationEntity;
import com.gamehub.infrastructure.persistence.entity.UserAchievementEntity;
import com.gamehub.infrastructure.persistence.entity.UserEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.AchievementRepository;
import com.gamehub.infrastructure.persistence.repository.RecommendationRepository;
import com.gamehub.infrastructure.persistence.repository.UserAchievementRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import com.gamehub.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-side queries for a player: profile, achievements, recommendations.
 *
 * <p>Separate from the services that write. These three are pure reads with no
 * events, no cache invalidation and no transactions worth the name, and mixing
 * them into the write services would blur where side effects live.
 */
@Service
@RequiredArgsConstructor
public class PlayerQueryService {

    private static final int MAX_RECOMMENDATIONS = 20;

    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final AchievementRepository achievements;
    private final UserAchievementRepository unlocked;
    private final RecommendationRepository recommendations;
    private final GameCatalogService catalogue;

    @Transactional(readOnly = true)
    public UserProfileResponse profile(UUID userId) {
        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user", userId));
        UserProfileEntity profile = profiles.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("profile", userId));

        return AuthService.toProfileResponse(user, profile);
    }

    /**
     * Achievements for a game, both unlocked and still locked.
     *
     * <p>Locked ones are included deliberately: an achievement list that only
     * shows what you already have is a trophy cabinet, not a goal list, and
     * the goals are the part that drives play.
     */
    @Transactional(readOnly = true)
    public List<AchievementResponse> achievementsFor(UUID userId, UUID gameId) {
        List<AchievementEntity> applicable = achievements.findApplicableTo(gameId);

        // One query for the unlock state of the whole list, rather than a
        // lookup per achievement.
        Map<UUID, UserAchievementEntity> unlockedById =
                unlocked.findByUserIdOrderByUnlockedAtDesc(userId).stream()
                        .collect(Collectors.toMap(
                                UserAchievementEntity::getAchievementId, Function.identity()));

        return applicable.stream()
                .map(achievement -> toResponse(achievement, unlockedById.get(achievement.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AchievementResponse> allUnlocked(UUID userId) {
        Map<UUID, UserAchievementEntity> unlockedById =
                unlocked.findByUserIdOrderByUnlockedAtDesc(userId).stream()
                        .collect(Collectors.toMap(
                                UserAchievementEntity::getAchievementId, Function.identity()));

        if (unlockedById.isEmpty()) {
            return List.of();
        }

        return achievements.findAllById(unlockedById.keySet()).stream()
                .map(achievement -> toResponse(achievement, unlockedById.get(achievement.getId())))
                .toList();
    }

    /**
     * Precomputed recommendations, hydrated with catalogue detail.
     *
     * <p>Two queries regardless of list length: one for the scored ids, one
     * for the games. Resolving each game individually would be an N+1 on the
     * Home screen, which is the first thing a player sees.
     */
    @Transactional(readOnly = true)
    public List<RecommendationResponse> recommendationsFor(UUID userId, int limit) {
        List<RecommendationEntity> scored = recommendations.findByUserIdOrderByScoreDesc(
                userId, PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_RECOMMENDATIONS)));

        if (scored.isEmpty()) {
            // Empty rather than an error. A new player has no recommendations
            // yet, and the UI falls back to the popularity listing.
            return List.of();
        }

        Map<UUID, GameSummaryResponse> gamesById = catalogue
                .byIds(scored.stream().map(RecommendationEntity::getGameId).toList())
                .stream()
                .collect(Collectors.toMap(GameSummaryResponse::id, Function.identity()));

        return scored.stream()
                // A recommendation can outlive the game being delisted. Drop
                // it rather than returning a null game the client must guard.
                .filter(row -> gamesById.containsKey(row.getGameId()))
                .map(row -> new RecommendationResponse(
                        gamesById.get(row.getGameId()),
                        row.getScore().doubleValue(),
                        row.getReason(),
                        row.getAlgorithm()))
                .toList();
    }

    private AchievementResponse toResponse(AchievementEntity achievement,
                                           UserAchievementEntity unlock) {
        return new AchievementResponse(
                achievement.getId(),
                achievement.getCode(),
                achievement.getName(),
                achievement.getDescription(),
                achievement.getIconUrl(),
                achievement.getPoints(),
                achievement.getRarity().name(),
                achievement.getGameId(),
                unlock != null,
                unlock == null ? null : unlock.getUnlockedAt());
    }
}
