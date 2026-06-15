package com.cadence.service;

import com.cadence.domain.AuthEventType;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.EventDetails;
import com.cadence.domain.EventStatus;
import com.cadence.domain.ManagedCalendarEvent;
import com.cadence.domain.PanelBookingResult;
import com.cadence.domain.PanelBookingResult.MemberEventResult;
import com.cadence.domain.PanelBookingResult.MemberOutcome;
import com.cadence.domain.PanelBookingResult.PanelOutcome;
import com.cadence.domain.Participant;
import com.cadence.integration.CalendarProviderClient;
import com.cadence.integration.CalendarNotConnectedException;
import com.cadence.integration.CalendarReconnectRequiredException;
import com.cadence.repository.CalendarConnectionRepository;
import com.cadence.repository.ManagedCalendarEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Writes Cadence interview events to participants' calendars (F10, research D6/D10). Idempotent create
 * (claim-before-insert on the unique index + the provider's deterministic-id 409-success), in-place
 * update, idempotent cancel, and the compensating-delete saga: on a mid-panel failure the already-created
 * events are deleted (zero orphans); a compensating delete that itself fails is surfaced+audited as
 * {@code CLEANUP_INCOMPLETE}, never a silent clean report (FR-012/FR-016a). The atomic slot reservation
 * that invokes this is F13; F10 ships the primitives. NO event content (title/location) is ever persisted
 * or logged (FR-017a) — log only provider/booking/member ids as Strings (never enums to {@code kv}).
 */
@Service
public class CalendarEventService {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventService.class);

    private final ManagedCalendarEventRepository events;
    private final CalendarConnectionRepository connections;
    private final MongoTemplate mongo;
    private final AuthAuditService audit;
    private final Clock clock;
    private final Map<CalendarProvider, CalendarProviderClient> clients;

    public CalendarEventService(ManagedCalendarEventRepository events, CalendarConnectionRepository connections,
                                MongoTemplate mongo, AuthAuditService audit, Clock clock,
                                List<CalendarProviderClient> clientList) {
        this.events = events;
        this.connections = connections;
        this.mongo = mongo;
        this.audit = audit;
        this.clock = clock;
        this.clients = clientList.stream().collect(Collectors.toMap(CalendarProviderClient::id, Function.identity()));
    }

    /** Create an event for every participant; roll back (compensating delete) on any failure (D10). */
    public PanelBookingResult createPanelEvents(String workspaceId, String bookingRef,
                                                List<Participant> participants, EventDetails details) {
        LinkedHashMap<String, MemberEventResult> results = new LinkedHashMap<>();
        List<Created> created = new ArrayList<>();
        for (Participant p : participants) {
            try {
                Created c = createForParticipant(workspaceId, bookingRef, p, details);
                results.put(p.memberId(), new MemberEventResult(p.memberId(), MemberOutcome.CREATED, c.eventId()));
                created.add(c);
            } catch (RuntimeException e) {
                MemberOutcome fo = e instanceof CalendarReconnectRequiredException
                    ? MemberOutcome.NEEDS_RECONNECTION : MemberOutcome.FAILED;
                results.put(p.memberId(), new MemberEventResult(p.memberId(), fo, null));
                boolean clean = rollback(workspaceId, bookingRef, created, results);
                return new PanelBookingResult(
                    clean ? PanelOutcome.ROLLED_BACK : PanelOutcome.CLEANUP_INCOMPLETE,
                    new ArrayList<>(results.values()));
            }
        }
        return new PanelBookingResult(PanelOutcome.CREATED, new ArrayList<>(results.values()));
    }

    /** Update each participant's event in place (e.g. reschedule). Idempotent at the provider. */
    public void updatePanelEvents(String workspaceId, String bookingRef,
                                  List<Participant> participants, EventDetails details) {
        for (Participant p : participants) {
            CalendarProviderClient client = clientFor(workspaceId, p.memberId());
            EventDetails perDetails = withZone(details, p);
            client.updateEvent(workspaceId, bookingRef, p.memberId(), perDetails);
            touchTimes(workspaceId, bookingRef, p.memberId(), client.id(), perDetails);
            audit.record(AuthEventType.CALENDAR_EVENT_UPDATED, workspaceId, p.memberId(), "updated", null);
            log.info("calendar event updated {} {} {}",
                kv("bookingRef", bookingRef), kv("memberId", p.memberId()), kv("provider", client.id().name()));
        }
    }

    /**
     * Cancel a booking: idempotently delete every event recorded for it. A transient delete failure for one
     * participant does NOT abort the others (plan-review M2) — that participant is marked CLEANUP_INCOMPLETE
     * and audited so its orphan is reconcilable, and the loop continues. Returns true iff every delete cleaned.
     */
    public boolean cancelBooking(String workspaceId, String bookingRef) {
        boolean clean = true;
        for (ManagedCalendarEvent row : events.findByWorkspaceIdAndBookingRef(workspaceId, bookingRef)) {
            if (row.getStatus() == EventStatus.DELETED) {
                continue;
            }
            CalendarProviderClient client = clients.get(row.getProvider());
            if (client == null) {
                continue;
            }
            try {
                client.deleteEvent(workspaceId, bookingRef, row.getMemberId());
                markStatus(workspaceId, bookingRef, row.getMemberId(), row.getProvider(), EventStatus.DELETED);
                audit.record(AuthEventType.CALENDAR_EVENT_DELETED, workspaceId, row.getMemberId(), "deleted", null);
            } catch (RuntimeException e) {
                markStatus(workspaceId, bookingRef, row.getMemberId(), row.getProvider(), EventStatus.CLEANUP_INCOMPLETE);
                audit.record(AuthEventType.CALENDAR_EVENT_CLEANUP_INCOMPLETE, workspaceId, row.getMemberId(),
                    "cleanup_incomplete", null);
                log.warn("calendar cancel cleanup incomplete (orphan may remain) {} {} {}",
                    kv("bookingRef", bookingRef), kv("memberId", row.getMemberId()), kv("provider", row.getProvider().name()));
                clean = false;
            }
        }
        return clean;
    }

    // --- internals -------------------------------------------------------------------------------

    private Created createForParticipant(String workspaceId, String bookingRef, Participant p, EventDetails details) {
        CalendarProviderClient client = clientFor(workspaceId, p.memberId());
        CalendarProvider provider = client.id();
        String eventId = com.cadence.integration.GoogleEventId.of(bookingRef, p.memberId());

        // Idempotent fast path: an already-recorded CREATED event needs no provider call (avoids a
        // redundant insert on a sequential retry).
        ManagedCalendarEvent existing = events
            .findByWorkspaceIdAndBookingRefAndMemberIdAndProvider(workspaceId, bookingRef, p.memberId(), provider)
            .orElse(null);
        if (existing != null && existing.getStatus() == EventStatus.CREATED) {
            return new Created(p.memberId(), provider, existing.getProviderEventId());
        }

        // Provider-FIRST (plan-review fix): create at the provider before recording. The provider create is
        // idempotent (deterministic id -> 409 == success), so two concurrent creates yield exactly one
        // event. If this throws, NO row is written -> no partial state (FR-014), nothing to roll back.
        EventDetails perDetails = withZone(details, p);
        client.createEvent(workspaceId, bookingRef, p.memberId(), perDetails);

        // Record idempotently; the unique index makes exactly one writer the inserter (-> exactly one audit).
        boolean inserted = recordCreated(workspaceId, bookingRef, p.memberId(), provider, eventId, perDetails);
        if (inserted) {
            audit.record(AuthEventType.CALENDAR_EVENT_CREATED, workspaceId, p.memberId(), "created", null);
            log.info("calendar event created {} {} {}",
                kv("bookingRef", bookingRef), kv("memberId", p.memberId()), kv("provider", provider.name()));
        }
        return new Created(p.memberId(), provider, eventId);
    }

    private boolean rollback(String workspaceId, String bookingRef, List<Created> created,
                             Map<String, MemberEventResult> results) {
        boolean clean = true;
        for (Created c : created) {
            CalendarProviderClient client = clients.get(c.provider());
            try {
                client.deleteEvent(workspaceId, bookingRef, c.memberId());
                markStatus(workspaceId, bookingRef, c.memberId(), c.provider(), EventStatus.DELETED);
                results.put(c.memberId(), new MemberEventResult(c.memberId(), MemberOutcome.ROLLED_BACK, c.eventId()));
            } catch (RuntimeException e) {
                markStatus(workspaceId, bookingRef, c.memberId(), c.provider(), EventStatus.CLEANUP_INCOMPLETE);
                audit.record(AuthEventType.CALENDAR_EVENT_CLEANUP_INCOMPLETE, workspaceId, c.memberId(),
                    "cleanup_incomplete", null);
                log.warn("calendar event cleanup incomplete (orphan may remain) {} {} {}",
                    kv("bookingRef", bookingRef), kv("memberId", c.memberId()), kv("provider", c.provider().name()));
                results.put(c.memberId(), new MemberEventResult(c.memberId(), MemberOutcome.CLEANUP_INCOMPLETE, c.eventId()));
                clean = false;
            }
        }
        return clean;
    }

    /**
     * Idempotently record a created event. Upsert on the unique key so a concurrent pair yields exactly one
     * row; returns true only for the inserter (so exactly one {@code CALENDAR_EVENT_CREATED} audit). A
     * DuplicateKey race (both insert simultaneously) is the non-inserter -> false.
     */
    private boolean recordCreated(String workspaceId, String bookingRef, String memberId, CalendarProvider provider,
                                  String eventId, EventDetails details) {
        Instant now = Instant.now(clock);
        Update update = new Update()
            .set("status", EventStatus.CREATED)
            .set("providerEventId", eventId)
            .set("startAt", details.startAt())
            .set("endAt", details.endAt())
            .set("updatedAt", now)
            .setOnInsert("createdAt", now);
        try {
            return mongo.upsert(keyQuery(workspaceId, bookingRef, memberId, provider), update, ManagedCalendarEvent.class)
                .getUpsertedId() != null;
        } catch (DuplicateKeyException e) {
            return false; // a concurrent insert won the unique index — the event is recorded
        }
    }

    private void markStatus(String workspaceId, String bookingRef, String memberId, CalendarProvider provider,
                            EventStatus status) {
        mongo.updateFirst(keyQuery(workspaceId, bookingRef, memberId, provider),
            new Update().set("status", status).set("updatedAt", Instant.now(clock)), ManagedCalendarEvent.class);
    }

    private void touchTimes(String workspaceId, String bookingRef, String memberId, CalendarProvider provider,
                            EventDetails details) {
        mongo.updateFirst(keyQuery(workspaceId, bookingRef, memberId, provider),
            new Update().set("startAt", details.startAt()).set("endAt", details.endAt())
                .set("updatedAt", Instant.now(clock)), ManagedCalendarEvent.class);
    }

    private static Query keyQuery(String workspaceId, String bookingRef, String memberId, CalendarProvider provider) {
        return new Query(Criteria.where("workspaceId").is(workspaceId).and("bookingRef").is(bookingRef)
            .and("memberId").is(memberId).and("provider").is(provider));
    }

    /** Choose a CONNECTED, client-supported connection for the member; else throw a typed failure. */
    private CalendarProviderClient clientFor(String workspaceId, String memberId) {
        List<CalendarConnection> cs = connections.findByWorkspaceIdAndMemberId(workspaceId, memberId);
        CalendarConnection chosen = null;
        for (CalendarConnection c : cs) {
            if (!clients.containsKey(c.getProvider())) {
                continue;
            }
            if (c.getStatus() == ConnectionStatus.CONNECTED) {
                chosen = c;
                break;
            }
            if (chosen == null) {
                chosen = c;
            }
        }
        if (chosen == null) {
            throw new CalendarNotConnectedException();
        }
        if (chosen.getStatus() == ConnectionStatus.NEEDS_RECONNECTION) {
            throw new CalendarReconnectRequiredException();
        }
        return clients.get(chosen.getProvider());
    }

    private static EventDetails withZone(EventDetails details, Participant p) {
        if (p.timeZone() == null || p.timeZone().equals(details.timeZone())) {
            return details;
        }
        return new EventDetails(details.title(), details.location(), details.startAt(), details.endAt(), p.timeZone());
    }

    private record Created(String memberId, CalendarProvider provider, String eventId) {}
}
