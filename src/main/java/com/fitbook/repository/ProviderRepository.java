package com.fitbook.repository;

import com.fitbook.dto.ProviderSummaryDTO;

import java.util.List;
import java.util.Optional;

public interface ProviderRepository {
    List<ProviderSummaryDTO> findAll();

    /** The provider profile id for a login account, empty if the user has no provider profile. */
    Optional<Long> findIdByUserId(long userId);
}
