package com.cadence.repository;

import com.cadence.domain.Member;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends MongoRepository<Member, String> {

    List<Member> findByWorkspaceId(String workspaceId);

    Optional<Member> findByWorkspaceIdAndEmailHash(String workspaceId, String emailHash);

    Optional<Member> findBySsoProviderAndSsoSubject(String ssoProvider, String ssoSubject);

    boolean existsByWorkspaceIdAndEmailHash(String workspaceId, String emailHash);
}
