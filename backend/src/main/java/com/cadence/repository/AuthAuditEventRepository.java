package com.cadence.repository;

import com.cadence.domain.AuthAuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuthAuditEventRepository extends MongoRepository<AuthAuditEvent, String> {

    List<AuthAuditEvent> findByMemberIdOrderByOccurredAtDesc(String memberId);
}
