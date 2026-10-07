package com.fitbook.repository;

import com.fitbook.dto.ServiceDTO;

import java.util.List;
import java.util.Optional;

public interface ServiceRepository {
    List<ServiceDTO> findAll();

    Optional<ServiceDTO> findById(long id);
}
