package com.cadence.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * An "any N of pool" panel rule (F12, FR-010). A slot is eligible for this pool only when at least
 * {@code n} DISTINCT pool members are positively-known-free across the slot and its buffers; an
 * unknown-availability member does not count toward the quorum (the fail-safe, FR-014). Holds member
 * id references only — never PII.
 */
public class PoolRule {

    private List<String> memberIds = new ArrayList<>();
    private int n;

    public PoolRule() {}

    public PoolRule(List<String> memberIds, int n) {
        this.memberIds = memberIds == null ? new ArrayList<>() : new ArrayList<>(memberIds);
        this.n = n;
    }

    public List<String> getMemberIds() { return memberIds; }
    public void setMemberIds(List<String> memberIds) { this.memberIds = memberIds; }

    public int getN() { return n; }
    public void setN(int n) { this.n = n; }
}
