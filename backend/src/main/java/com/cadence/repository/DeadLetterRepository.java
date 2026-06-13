package com.cadence.repository;

import com.cadence.domain.DeadLetterRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DeadLetterRepository extends MongoRepository<DeadLetterRecord, String> {
}
