package com.sabayride.rental.common;

import java.util.List;

/**
 * The single list shape every paginated endpoint returns.
 *
 * One envelope everywhere means the Flutter and dashboard teams write ONE
 * pagination widget instead of one per screen. Endpoints that invent their own
 * shape are the reason client code fills up with special cases.
 */
public record PageEnvelope<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageEnvelope<T> of(List<T> all, int page, int size) {
        int from = Math.min(page * size, all.size());
        int to   = Math.min(from + size, all.size());
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) all.size() / size);
        return new PageEnvelope<>(all.subList(from, to), page, size, all.size(), totalPages);
    }
}
