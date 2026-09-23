package com.fitbook.service;

import com.fitbook.dto.ProviderSummaryDTO;
import com.fitbook.dto.ServiceDTO;

import java.util.List;

public interface CatalogService {
    List<ServiceDTO> listServices();
    List<ProviderSummaryDTO> listProviders();
}
