package com.cadence.repository;

import com.cadence.domain.Session;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SessionRepository extends MongoRepository<Session, String> {

    List<Session> findByMemberId(String memberId);
}
