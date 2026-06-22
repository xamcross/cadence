package com.cadence.domain;

/** F51 requisition lifecycle. OPEN requisitions and their candidates appear in the default pipeline; CLOSED are
 * excluded from the default view (revealed via the include-closed filter) and from Hiring-Manager scoping. */
public enum RequisitionStatus {
    OPEN,
    CLOSED
}
