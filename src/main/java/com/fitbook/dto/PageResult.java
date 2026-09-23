package com.fitbook.dto;

import java.util.List;

/**
 * Generic pagination envelope for list endpoints (pagination is implemented
 * with hand-written SQL LIMIT/OFFSET in the repository layer, per spec).
 */
public record PageResult<T>(List<T> items, int page, int size, long totalCount) {}
