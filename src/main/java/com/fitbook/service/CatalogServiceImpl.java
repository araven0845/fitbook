package com.fitbook.service;

import com.fitbook.dto.ProviderSummaryDTO;
import com.fitbook.dto.ServiceDTO;
import com.fitbook.repository.ProviderRepository;
import com.fitbook.repository.ServiceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CatalogServiceImpl implements CatalogService {

    private final ServiceRepository serviceRepository;
    private final ProviderRepository providerRepository;

    public CatalogServiceImpl(ServiceRepository serviceRepository, ProviderRepository providerRepository) {
        this.serviceRepository = serviceRepository;
        this.providerRepository = providerRepository;
    }

    @Override
    public List<ServiceDTO> listServices() {
        return serviceRepository.findAll();
    }

    @Override
    public List<ProviderSummaryDTO> listProviders() {
        return providerRepository.findAll();
    }
}
