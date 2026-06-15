package com.cadence.repository;

import com.cadence.domain.OAuthFlowState;
import org.springframework.data.repository.Repository;

/**
 * Narrow repository for {@link OAuthFlowState}: {@code save} ONLY. The single-use consume is an atomic
 * {@code mongoTemplate.findAndRemove} in the service — a plain {@code findById} is deliberately NOT
 * exposed, since reading without deleting reintroduces a TOCTOU replay window (Backend #3 / Security #5).
 */
public interface OAuthFlowStateRepository extends Repository<OAuthFlowState, String> {

    OAuthFlowState save(OAuthFlowState state);
}
