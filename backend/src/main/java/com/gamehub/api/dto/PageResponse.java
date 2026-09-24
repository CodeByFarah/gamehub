package com.gamehub.api.dto;

import java.util.List;

/**
 * Envelope for every paged response.
 *
 * <p>Deliberately not Spring Data Page. That type serialises a large, unstable
 * structure (pageable, sort, numberOfElements, first, last) that is an
 * implementation detail of the server persistence layer, and its JSON shape
 * has changed between Spring versions. Pinning our own shape means a Spring
 * upgrade cannot break every mobile client in the field.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext) {

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
        return new PageResponse<>(items, page, size, totalItems, totalPages, page + 1 < totalPages);
    }

    /**
     * For sources that cannot cheaply produce a total, such as a Redis ZSET
     * page. hasNext is inferred from whether a full page came back, which
     * costs nothing and is what the UI actually needs to decide about an
     * infinite scroll.
     */
    public static <T> PageResponse<T> unbounded(List<T> items, int page, int size) {
        return new PageResponse<>(items, page, size, -1L, -1, items.size() == size);
    }
}
