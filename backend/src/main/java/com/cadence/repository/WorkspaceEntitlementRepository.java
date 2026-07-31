package com.cadence.repository;

import com.cadence.domain.WorkspaceEntitlement;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * 032 -- entitlement lookups. Binding is insert-only (unique indexes); lifecycle transitions are
 * findAndModify CAS in BillingService, never via save() here.
 */
public interface WorkspaceEntitlementRepository extends MongoRepository<WorkspaceEntitlement, String> {

    Optional<WorkspaceEntitlement> findByWorkspaceId(String workspaceId);

    Optional<WorkspaceEntitlement> findByFsLicenseId(String fsLicenseId);
}
