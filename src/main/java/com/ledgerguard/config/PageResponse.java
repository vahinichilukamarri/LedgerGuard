package com.ledgerguard.config;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A page of results at the API boundary.
 *
 * <p>Spring Data's {@code Page} is not serialized directly: its JSON shape is an
 * implementation detail of {@code PageImpl} and Spring Boot logs a warning against
 * relying on it. This is the stable equivalent, in the same DTO-record style as
 * every other response in this API.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
