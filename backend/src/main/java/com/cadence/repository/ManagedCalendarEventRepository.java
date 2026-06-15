package com.cadence.repository;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ManagedCalendarEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Persistence for {@link ManagedCalendarEvent} (F10). Keyed by the booking + participant natural key. */
public interface ManagedCalendarEventRepository extends MongoRepository<ManagedCalendarEvent, String> {

    List<ManagedCalendarEvent> findByWorkspaceIdAndBookingRef(String workspaceId, String bookingRef);

    Optional<ManagedCalendarEvent> findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(
        String workspaceId, String bookingRef, String memberId, CalendarProvider provider);
}
