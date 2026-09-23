package com.fitbook.repository;

import com.fitbook.dto.ProviderSummaryDTO;

import java.util.List;

public interface ProviderRepository {
    List<ProviderSummaryDTO> findAll();
}
