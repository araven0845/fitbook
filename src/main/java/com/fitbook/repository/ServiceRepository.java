package com.fitbook.repository;

import com.fitbook.dto.ServiceDTO;

import java.util.List;

public interface ServiceRepository {
    List<ServiceDTO> findAll();
}
