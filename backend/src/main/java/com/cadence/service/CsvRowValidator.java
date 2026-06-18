package com.cadence.service;

import com.cadence.config.ImportProperties;
import com.cadence.domain.CsvRowFailureReason;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * F42 pure per-row validation (FR-007/FR-010). Returns a VALUE-FREE {@link CsvRowFailureReason} (the field +
 * rule, never the cell value — the F12 lesson) or empty when the row is valid. A row missing a required field
 * (name/email), carrying a malformed email, or with an over-long field is a per-row failure.
 *
 * <p>The failing-field name is returned via {@link #failingField(CsvRow)} so the caller can record it on the
 * row result without the validator holding state.
 */
@Component
public class CsvRowValidator {

    // Pragmatic, conservative email shape (not a full RFC 5322 grammar — that over-accepts and is a footgun).
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ImportProperties props;

    public CsvRowValidator(ImportProperties props) {
        this.props = props;
    }

    /** @return empty if valid, else the value-free failure reason. */
    public Optional<CsvRowFailureReason> validate(CsvRow row) {
        int max = props.getMaxFieldLength();
        if (tooLong(row.name(), max) || tooLong(row.email(), max) || tooLong(row.phone(), max)
            || tooLong(row.stage(), max) || tooLong(row.requisition(), max)) {
            return Optional.of(CsvRowFailureReason.FIELD_TOO_LONG);
        }
        if (isBlank(row.name()) || isBlank(row.email())) {
            return Optional.of(CsvRowFailureReason.MISSING_REQUIRED);
        }
        if (!EMAIL.matcher(row.email().trim()).matches()) {
            return Optional.of(CsvRowFailureReason.INVALID_EMAIL);
        }
        return Optional.empty();
    }

    /** The field a row failed on (for the value-free row result); null when valid. */
    public String failingField(CsvRow row) {
        int max = props.getMaxFieldLength();
        if (tooLong(row.name(), max)) return "name";
        if (tooLong(row.email(), max)) return "email";
        if (tooLong(row.phone(), max)) return "phone";
        if (tooLong(row.stage(), max)) return "stage";
        if (tooLong(row.requisition(), max)) return "requisition";
        if (isBlank(row.name())) return "name";
        if (isBlank(row.email())) return "email";
        if (!EMAIL.matcher(row.email().trim()).matches()) return "email";
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean tooLong(String s, int max) {
        return s != null && s.length() > max;
    }
}
