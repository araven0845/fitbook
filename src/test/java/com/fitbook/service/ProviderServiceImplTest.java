package com.fitbook.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderServiceImplTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 10, 7, 12, 0).atZone(ZONE).toInstant(), ZONE);

    private static final long USER_ID = 20;
    private static final long PROVIDER_ID = 2;

    @Mock
    private ProviderRepository providerRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private AppointmentRepository appointmentRepository;

    private ProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProviderServiceImpl(providerRepository, slotRepository, serviceRepository, appointmentRepository, CLOCK);
    }

    private void providerProfileExists() {
        when(providerRepository.findIdByUserId(USER_ID)).thenReturn(Optional.of(PROVIDER_ID));
    }

    private SlotRecord slot(long providerId, SlotStatus status) {
        return new SlotRecord(9, providerId, 1, "2026-10-09 09:00", "2026-10-09 10:00", status, 0);
    }

    // ---- createSlot ----

    @Test
    void createSlot_derivesEndTimeFromServiceDuration() {
        providerProfileExists();
        when(serviceRepository.findById(3)).thenReturn(Optional.of(new ServiceDTO(3, "Yoga", 45, 30.0, "d")));
        when(slotRepository.existsOverlap(PROVIDER_ID, "2026-10-08 09:00", "2026-10-08 09:45")).thenReturn(false);
        when(slotRepository.insert(PROVIDER_ID, 3, "2026-10-08 09:00", "2026-10-08 09:45")).thenReturn(99L);
        AvailabilitySlotDTO created = new AvailabilitySlotDTO(99, PROVIDER_ID, "Coach", 3, "Yoga", "2026-10-08 09:00", "2026-10-08 09:45", "OPEN");
        when(slotRepository.findDetailById(99)).thenReturn(Optional.of(created));

        assertEquals(created, service.createSlot(USER_ID, new CreateSlotRequest(3L, "2026-10-08T09:00")));
    }

    @Test
    void createSlot_startInThePast_throwsBadRequest() {
        providerProfileExists();

        assertThrows(BadRequestException.class, () -> service.createSlot(USER_ID, new CreateSlotRequest(3L, "2026-10-07T09:00")));
        verify(slotRepository, never()).insert(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void createSlot_impossibleDate_throwsBadRequest() {
        providerProfileExists();

        assertThrows(BadRequestException.class, () -> service.createSlot(USER_ID, new CreateSlotRequest(3L, "2026-02-31T09:00")));
    }

    @Test
    void createSlot_unknownService_throwsNotFound() {
        providerProfileExists();
        when(serviceRepository.findById(3)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.createSlot(USER_ID, new CreateSlotRequest(3L, "2026-10-08T09:00")));
    }

    @Test
    void createSlot_overlappingExistingSlot_throwsConflict() {
        providerProfileExists();
        when(serviceRepository.findById(3)).thenReturn(Optional.of(new ServiceDTO(3, "Yoga", 60, 30.0, "d")));
        when(slotRepository.existsOverlap(PROVIDER_ID, "2026-10-08 09:00", "2026-10-08 10:00")).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.createSlot(USER_ID, new CreateSlotRequest(3L, "2026-10-08T09:00")));
        verify(slotRepository, never()).insert(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void createSlot_accountWithoutProviderProfile_throwsForbidden() {
        when(providerRepository.findIdByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ForbiddenException.class, () -> service.createSlot(USER_ID, new CreateSlotRequest(3L, "2026-10-08T09:00")));
    }

    // ---- removeSlot ----

    @Test
    void removeSlot_ownOpenSlot_cancelsIt() {
        providerProfileExists();
        when(slotRepository.findById(9)).thenReturn(Optional.of(slot(PROVIDER_ID, SlotStatus.OPEN)));
        when(slotRepository.cancelOpen(9)).thenReturn(1);

        service.removeSlot(USER_ID, 9);

        verify(slotRepository).cancelOpen(9);
    }

    @Test
    void removeSlot_otherProvidersSlot_throwsForbidden() {
        providerProfileExists();
        when(slotRepository.findById(9)).thenReturn(Optional.of(slot(PROVIDER_ID + 1, SlotStatus.OPEN)));

        assertThrows(ForbiddenException.class, () -> service.removeSlot(USER_ID, 9));
        verify(slotRepository, never()).cancelOpen(anyLong());
    }

    @Test
    void removeSlot_bookedSlot_throwsConflict() {
        providerProfileExists();
        when(slotRepository.findById(9)).thenReturn(Optional.of(slot(PROVIDER_ID, SlotStatus.BOOKED)));

        assertThrows(ConflictException.class, () -> service.removeSlot(USER_ID, 9));
        verify(slotRepository, never()).cancelOpen(anyLong());
    }

    @Test
    void removeSlot_bookedJustBeforeRemoval_throwsConflict() {
        providerProfileExists();
        when(slotRepository.findById(9)).thenReturn(Optional.of(slot(PROVIDER_ID, SlotStatus.OPEN)));
        when(slotRepository.cancelOpen(9)).thenReturn(0);

        assertThrows(ConflictException.class, () -> service.removeSlot(USER_ID, 9));
    }

    @Test
    void removeSlot_unknownSlot_throwsNotFound() {
        providerProfileExists();
        when(slotRepository.findById(9)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.removeSlot(USER_ID, 9));
    }
}
