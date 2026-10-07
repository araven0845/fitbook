package com.fitbook.service;

import com.fitbook.dto.AppointmentDTO;
import com.fitbook.dto.AvailabilitySlotDTO;
import com.fitbook.dto.CreateSlotRequest;

import java.util.List;

/** Provider-side use cases. The `userId` is the logged-in user's id; the provider profile is looked up from it. */
public interface ProviderService {

    List<AvailabilitySlotDTO> listSlots(long userId);

    List<AppointmentDTO> listAppointments(long userId);

    AvailabilitySlotDTO createSlot(long userId, CreateSlotRequest request);

    void removeSlot(long userId, long slotId);
}
