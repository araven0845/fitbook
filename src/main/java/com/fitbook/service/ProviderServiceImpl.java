package com.fitbook.service;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.CreateSlotRequest;
import com.fitbook.dto.ServiceDTO;
import com.fitbook.exception.BadRequestException;
import com.fitbook.exception.ConflictException;
import com.fitbook.exception.ForbiddenException;
import com.fitbook.exception.NotFoundException;
import com.fitbook.model.SlotRecord;
import com.fitbook.model.SlotStatus;
import com.fitbook.repository.AppointmentRepository;
import com.fitbook.repository.ProviderRepository;
import com.fitbook.repository.ServiceRepository;
import com.fitbook.repository.SlotRepository;
import com.fitbook.util.SlotTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class ProviderServiceImpl implements ProviderService {

    private final ProviderRepository providerRepository;
    private final SlotRepository slotRepository;
    private final ServiceRepository serviceRepository;
    private final AppointmentRepository appointmentRepository;
    private final Clock clock;

    public ProviderServiceImpl(ProviderRepository providerRepository, SlotRepository slotRepository,
                               ServiceRepository serviceRepository, AppointmentRepository appointmentRepository,
                               Clock clock) {
        this.providerRepository = providerRepository;
        this.slotRepository = slotRepository;
        this.serviceRepository = serviceRepository;
        this.appointmentRepository = appointmentRepository;
        this.clock = clock;
    }

    @Override
    public List<AvailabilitySlotDTO> listSlots(long userId) {
        return slotRepository.findByProvider(providerIdFor(userId), SlotTime.now(clock));
    }

    @Override
    public List<AppointmentDTO> listAppointments(long userId) {
        return appointmentRepository.findByProvider(providerIdFor(userId), SlotTime.now(clock));
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AvailabilitySlotDTO createSlot(long userId, CreateSlotRequest request) {
        long providerId = providerIdFor(userId);

        LocalDateTime start;
        try {
            start = LocalDateTime.parse(request.startTime());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("startTime is not a valid date and time");
        }
        if (!start.isAfter(LocalDateTime.now(clock))) {
            throw new BadRequestException("A slot must start in the future");
        }
        ServiceDTO service = serviceRepository.findById(request.serviceId())
                .orElseThrow(() -> new NotFoundException("Service not found"));

        String startText = SlotTime.format(start);
        String endText = SlotTime.format(start.plusMinutes(service.durationMinutes()));
        if (slotRepository.existsOverlap(providerId, startText, endText)) {
            throw new ConflictException("You already have a slot overlapping that time");
        }

        long slotId;
        try {
            slotId = slotRepository.insert(providerId, service.id(), startText, endText);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("You already have a slot starting at that time");
        }
        return slotRepository.findDetailById(slotId)
                .orElseThrow(() -> new IllegalStateException("Slot " + slotId + " vanished after insert"));
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeSlot(long userId, long slotId) {
        long providerId = providerIdFor(userId);

        SlotRecord slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("Slot not found"));
        if (slot.providerId() != providerId) {
            throw new ForbiddenException("You can only remove your own slots");
        }
        if (slot.status() == SlotStatus.CANCELLED) {
            throw new NotFoundException("Slot not found");
        }
        if (slot.status() == SlotStatus.BOOKED) {
            throw new ConflictException("This slot has a booking - the customer has to cancel it first");
        }
        // Guarded by status = 'OPEN', so a booking that sneaks in first makes this affect zero rows.
        if (slotRepository.cancelOpen(slotId) == 0) {
            throw new ConflictException("This slot was just booked and can no longer be removed");
        }
    }

    private long providerIdFor(long userId) {
        return providerRepository.findIdByUserId(userId)
                .orElseThrow(() -> new ForbiddenException("This account has no provider profile"));
    }
}
