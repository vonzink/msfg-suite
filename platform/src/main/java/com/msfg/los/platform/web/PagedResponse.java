package com.msfg.los.platform.web;
import org.springframework.data.domain.Page;
import java.util.List;
public record PagedResponse<T>(List<T> items, int page, int size, long total, int totalPages) {
    public static <T> PagedResponse<T> from(Page<T> p) {
        return new PagedResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
