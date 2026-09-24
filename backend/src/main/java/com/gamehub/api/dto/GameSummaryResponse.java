package com.gamehub.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Catalogue row. Trimmed for list rendering; the long description is absent. */
public record GameSummaryResponse(
        UUID id,
        String slug,
        String title,
        String shortDescription,
        String genre,
        List<String> tags,
        String iconUrl,
        BigDecimal ratingAvg,
        int ratingCount,
        int popularityScore,
        boolean supportsMultiplayer,
        boolean supportsCloudSave,
        int avgSessionMinutes) {
}
