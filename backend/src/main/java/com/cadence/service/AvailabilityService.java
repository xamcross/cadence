package com.cadence.service;

import com.cadence.config.CalendarApiProperties;
import com.cadence.domain.AvailabilityStatus;
import com.cadence.domain.BusyInterval;
import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.ConnectionStatus;
import com.cadence.domain.MemberAvailability;
import com.cadence.integration.CalendarApiException;
import com.cadence.integration.CalendarNotConnectedException;
import com.cadence.integration.CalendarProviderClient;
import com.cadence.integration.CalendarReconnectRequiredException;
import com.cadence.repository.CalendarConnectionRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads availability for one or more members (F10, research D2/D3/D4). Per-member free/busy via the
 * connection-selected {@link CalendarProviderClient}; a panel fans out on the bounded executor (SC-001).
 * A non-connected / needs-reconnection / transiently-failing member is reported with a DISTINCT
 * {@link AvailabilityStatus} — never silently "free" (FR-004).
 *
 * <p><strong>Privileged internal primitive (plan-review Security M4)</strong>: {@link #query} accepts an
 * arbitrary member-id list and performs NO caller authorization. It MUST NOT be exposed on any endpoint
 * without an F13-level role gate; F10 calls it only from the self-scoped preview (1 member) and from F13.
 */
@Service
public class AvailabilityService {

    private final CalendarConnectionRepository connections;
    private final Map<CalendarProvider, CalendarProviderClient> clients;
    private final ExecutorService fanout;
    private final CalendarApiProperties props;

    public AvailabilityService(CalendarConnectionRepository connections,
                               List<CalendarProviderClient> clientList,
                               @Qualifier("calendarFanoutExecutor") ExecutorService fanout,
                               CalendarApiProperties props) {
        this.connections = connections;
        this.clients = clientList.stream().collect(Collectors.toMap(CalendarProviderClient::id, Function.identity()));
        this.fanout = fanout;
        this.props = props;
    }

    /** Per-member availability over a bounded window. Single member runs inline; a panel fans out. */
    public List<MemberAvailability> query(String workspaceId, Instant windowStart, Instant windowEnd,
                                          List<String> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        Instant end = clampWindow(windowStart, windowEnd);
        if (memberIds.size() == 1) {
            return List.of(queryOne(workspaceId, memberIds.get(0), windowStart, end));
        }
        return fanOut(workspaceId, windowStart, end, memberIds);
    }

    private List<MemberAvailability> fanOut(String workspaceId, Instant start, Instant end,
                                            List<String> memberIds) {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<Future<MemberAvailability>> futures = new ArrayList<>();
        for (String memberId : memberIds) {
            Callable<MemberAvailability> task = () -> {
                Map<String, String> prev = MDC.getCopyOfContextMap();
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    return queryOne(workspaceId, memberId, start, end);
                } finally {
                    if (prev != null) {
                        MDC.setContextMap(prev);
                    } else {
                        MDC.clear();
                    }
                }
            };
            futures.add(fanout.submit(task));
        }
        long deadlineMs = props.getReadTimeout().toMillis() + 2_000; // join margin over the per-call timeout
        List<MemberAvailability> out = new ArrayList<>(memberIds.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get(deadlineMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException | ExecutionException e) {
                futures.get(i).cancel(true);
                out.add(new MemberAvailability(memberIds.get(i), AvailabilityStatus.TEMPORARILY_UNAVAILABLE, List.of()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.add(new MemberAvailability(memberIds.get(i), AvailabilityStatus.TEMPORARILY_UNAVAILABLE, List.of()));
            }
        }
        return out;
    }

    private MemberAvailability queryOne(String workspaceId, String memberId, Instant start, Instant end) {
        Resolved resolved = resolve(workspaceId, memberId);
        if (resolved.status != null) {
            return new MemberAvailability(memberId, resolved.status, List.of());
        }
        if (!end.isAfter(start)) {
            return new MemberAvailability(memberId, AvailabilityStatus.DATA, List.of()); // empty window
        }
        try {
            List<BusyInterval> busy = resolved.client.queryFreeBusy(workspaceId, memberId, start, end);
            return new MemberAvailability(memberId, AvailabilityStatus.DATA, busy);
        } catch (CalendarReconnectRequiredException e) {
            return new MemberAvailability(memberId, AvailabilityStatus.NEEDS_RECONNECTION, List.of());
        } catch (CalendarNotConnectedException e) {
            return new MemberAvailability(memberId, AvailabilityStatus.NOT_CONNECTED, List.of());
        } catch (CalendarApiException e) {
            return new MemberAvailability(memberId, AvailabilityStatus.TEMPORARILY_UNAVAILABLE, List.of());
        }
    }

    /** Choose a client-supported connection for the member; otherwise an unavailable status. */
    private Resolved resolve(String workspaceId, String memberId) {
        List<CalendarConnection> cs = connections.findByWorkspaceIdAndMemberId(workspaceId, memberId);
        CalendarConnection chosen = null;
        for (CalendarConnection c : cs) {
            if (!clients.containsKey(c.getProvider())) {
                continue; // e.g. a MICROSOFT connection with no F10 client (pre-F11)
            }
            if (c.getStatus() == ConnectionStatus.CONNECTED) {
                chosen = c;
                break;
            }
            if (chosen == null) {
                chosen = c; // remember a NEEDS_RECONNECTION one in case there is no CONNECTED
            }
        }
        if (chosen == null) {
            return Resolved.status(AvailabilityStatus.NOT_CONNECTED);
        }
        if (chosen.getStatus() == ConnectionStatus.NEEDS_RECONNECTION) {
            return Resolved.status(AvailabilityStatus.NEEDS_RECONNECTION);
        }
        return Resolved.client(clients.get(chosen.getProvider()));
    }

    private Instant clampWindow(Instant start, Instant end) {
        if (end == null || start == null) {
            return end;
        }
        Instant max = start.plus(props.getMaxWindow());
        return end.isAfter(max) ? max : end;
    }

    private record Resolved(CalendarProviderClient client, AvailabilityStatus status) {
        static Resolved client(CalendarProviderClient c) { return new Resolved(c, null); }
        static Resolved status(AvailabilityStatus s) { return new Resolved(null, s); }
    }

    /** Convenience for the single-member self-preview (D11): empty/unconnected handled by {@link #query}. */
    public MemberAvailability previewSelf(String workspaceId, String memberId, Instant start, Instant end) {
        return query(workspaceId, start, end, List.of(memberId)).get(0);
    }

    public Optional<CalendarProvider> providerFor(String workspaceId, String memberId) {
        Resolved r = resolve(workspaceId, memberId);
        return r.client == null ? Optional.empty() : Optional.of(r.client.id());
    }
}
