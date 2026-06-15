package com.cadence.repository;

import com.cadence.domain.CalendarProvider;
import com.cadence.domain.EventStatus;
import com.cadence.domain.ManagedCalendarEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence for {@link ManagedCalendarEvent} (F10). Keyed by the booking + participant natural key. */
public interface ManagedCalendarEventRepository extends MongoRepository<ManagedCalendarEvent, String> {

    List<ManagedCalendarEvent> findByWorkspaceIdAndBookingRef(String workspaceId, String bookingRef);

    Optional<ManagedCalendarEvent> findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(
        String workspaceId, String bookingRef, String memberId, CalendarProvider provider);

    /**
     * F12 daily-cap read (D5): the member's live Cadence-managed events over a HALF-OPEN absolute window
     * {@code [from, to)}, EXCLUDING rolled-back/orphaned rows (pass {@code {DELETED, CLEANUP_INCOMPLETE}}).
     * The rule engine reads ONCE per required member over the whole compute window and buckets by
     * zone-relative civil day in memory (one read per participant — backed by the
     * {workspaceId,memberId,startAt} index, ChangeUnit008). An explicit {@code @Query} is used (NOT a
     * derived {@code …StartAtGreaterThanEqualAndStartAtLessThan}) because the derived form builds two
     * Criteria on the same {@code startAt} field, which MongoDB rejects (InvalidMongoDbApiUsageException);
     * {@code $gte}/{@code $lt} gives the half-open range in a single criterion (and avoids the
     * inclusive-Between next-midnight double-count).
     */
    @Query("{ 'workspaceId': ?0, 'memberId': ?1, 'status': { $nin: ?2 }, 'startAt': { $gte: ?3, $lt: ?4 } }")
    List<ManagedCalendarEvent> findLiveForCap(
        String workspaceId, String memberId, Collection<EventStatus> excluded, Instant from, Instant to);
}
