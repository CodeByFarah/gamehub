package com.gamehub.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GameDetailResponse(
        UUID id,
        String slug,
        String title,
        String shortDescription,
        String description,
        String genre,
        List<String> tags,
        int minPlayers,
        int maxPlayers,
        int avgSessionMinutes,
        boolean supportsMultiplayer,
        boolean supportsCloudSave,
        String iconUrl,
        String bannerUrl,
        BigDecimal ratingAvg,
        int ratingCount,
        int popularityScore,
        LocalDate releasedAt) {
}
