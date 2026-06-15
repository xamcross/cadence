package com.cadence.repository;

import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Persistence for {@link CalendarConnection}. Lookups are by the natural key only (research D3). */
public interface CalendarConnectionRepository extends MongoRepository<CalendarConnection, String> {

    List<CalendarConnection> findByWorkspaceIdAndMemberId(String workspaceId, String memberId);

    Optional<CalendarConnection> findByWorkspaceIdAndMemberIdAndProvider(
        String workspaceId, String memberId, CalendarProvider provider);

    void deleteByWorkspaceIdAndMemberId(String workspaceId, String memberId);
}
