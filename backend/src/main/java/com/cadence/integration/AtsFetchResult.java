package com.cadence.integration;

import java.util.List;

/**
 * The result of one inbound fetch (F40, contract A): the normalized records plus an opaque cursor the next
 * poll passes back for incremental sync ({@code null} when there is no more to page / no cursor support).
 */
public record AtsFetchResult(List<AtsCandidateRecord> records, String nextCursor) {
}
