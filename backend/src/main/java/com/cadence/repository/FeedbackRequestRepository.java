package com.cadence.repository;

import com.cadence.domain.FeedbackRequest;
import com.cadence.domain.FeedbackRequestStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * F32 feedback-request store. Insert + {@code DuplicateKeyException} de-dup (the unique
 * {@code {interviewEventId, interviewerMemberId}} index) lives in the service. The reminder-due finder is an
 * explicit {@code @Query} {@code Pageable} range on {@code {status, nextReminderDueAt}} (the F12
 * {@code InvalidMongoDbApiUsageException} lesson — never a derived two-criteria-on-one-field method).
 */
public interface FeedbackRequestRepository extends MongoRepository<FeedbackRequest, String> {

    Optional<FeedbackRequest> findByTokenHash(String tokenHash);

    List<FeedbackRequest> findByWorkspaceIdAndInterviewEventId(String workspaceId, String interviewEventId);

    boolean existsByInterviewEventIdAndInterviewerMemberId(String interviewEventId, String interviewerMemberId);

    List<FeedbackRequest> findByWorkspaceIdAndCandidateIdAndStatus(
        String workspaceId, String candidateId, FeedbackRequestStatus status);

    List<FeedbackRequest> findByWorkspaceIdAndStatus(String workspaceId, FeedbackRequestStatus status, Pageable pageable);

    /** Reminder scan: PENDING requests whose next reminder is due. Backed by {status, nextReminderDueAt}. */
    @Query("{ 'status': ?0, 'nextReminderDueAt': { $lte: ?1 } }")
    List<FeedbackRequest> findReminderDue(FeedbackRequestStatus status, Instant now, Pageable pageable);
}
