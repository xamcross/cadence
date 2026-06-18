package com.cadence.service;

/**
 * F42 normalized CSV row (the seam between the Commons-CSV parser, confined to {@link CsvImportProcessor}, and
 * the rest of the service layer). {@code rowNumber} is the logical (record) number, not the physical line, so a
 * quoted multi-line field yields a deterministic number (FR-009). All values are verbatim cell content (the
 * injection neutralization happens only at the export boundary — {@link CsvInjectionEscaper}).
 */
public record CsvRow(int rowNumber, String name, String email, String phone,
                     String stage, String requisition) {
}
