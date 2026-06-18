package com.cadence.repository;

import com.cadence.domain.AtsConnection;
import com.cadence.domain.AtsConnectionStatus;
import com.cadence.integration.AtsProvider;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * F40/F41 ATS connection store. A workspace holds one connection per provider (F41 unique
 * {@code {workspaceId, provider}} index, ChangeUnit019). {@code findByWorkspaceId} returns ALL of a
 * workspace's connections (for the both-providers status surface); {@code findByWorkspaceIdAndProvider}
 * resolves the single per-(workspace,provider) connection; {@code findByStatus} backs the poll's
 * CONNECTED-connection iteration (across providers).
 */
public interface AtsConnectionRepository extends MongoRepository<AtsConnection, String> {

    List<AtsConnection> findByWorkspaceId(String workspaceId);

    Optional<AtsConnection> findByWorkspaceIdAndProvider(String workspaceId, AtsProvider provider);

    List<AtsConnection> findByStatus(AtsConnectionStatus status);
}
