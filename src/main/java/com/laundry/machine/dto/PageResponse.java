package com.laundry.machine.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic page envelope for admin list endpoints. Spring's {@code Page<T>}
 * is not safe to serialize directly (it depends on the configured
 * {@code PageImpl} serializer and can leak Pageable internals).
 *
 * <p>Kept intentionally minimal — admin UIs typically only need a few fields.
 * Extend if additional metadata is required.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private int page;
    private int pageSize;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;
}
