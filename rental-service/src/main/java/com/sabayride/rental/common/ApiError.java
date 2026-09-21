package com.sabayride.rental.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** The single error shape every endpoint returns. Matches the API contract. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        String field,
        Instant timestamp,
        String traceId
) {
    public static ApiError of(String code, String message, String field) {
        return new ApiError(code, message, field, Instant.now(), null);
    }
}
