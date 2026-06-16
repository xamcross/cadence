package com.cadence.repository;

import com.cadence.domain.RecruiterNotification;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** F22 recruiter-notification store (T044). Value-free rows; the pipeline read surface is F51. */
public interface RecruiterNotificationRepository extends MongoRepository<RecruiterNotification, String> {

    List<RecruiterNotification> findByWorkspaceIdAndCandidateId(String workspaceId, String candidateId);
}
