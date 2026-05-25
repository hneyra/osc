package dev.osc.persistence;

/**
 * Simple pagination parameters.
 * offset-based paging; cursor-based can be added in Phase 2.
 */
public record PageRequest(int page, int size) {

    public static final PageRequest DEFAULT = new PageRequest(0, 20);

    public PageRequest {
        if (page < 0)  throw new IllegalArgumentException("page must be >= 0");
        if (size < 1)  throw new IllegalArgumentException("size must be >= 1");
        if (size > 500) throw new IllegalArgumentException("size must be <= 500");
    }

    public int offset() {
        return page * size;
    }
}
