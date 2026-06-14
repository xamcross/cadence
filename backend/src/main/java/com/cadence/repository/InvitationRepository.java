package com.cadence.repository;

import com.cadence.domain.Invitation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface InvitationRepository extends MongoRepository<Invitation, String> {

    Optional<Invitation> findByTokenHash(String tokenHash);
}
