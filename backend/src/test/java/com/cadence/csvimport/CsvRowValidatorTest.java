package com.cadence.csvimport;

import com.cadence.config.ImportProperties;
import com.cadence.domain.CsvRowFailureReason;
import com.cadence.service.CsvRow;
import com.cadence.service.CsvRowValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** F42 unit: pure per-row validation produces value-free reasons (FR-009/FR-010). */
class CsvRowValidatorTest {

    private final CsvRowValidator validator = new CsvRowValidator(new ImportProperties());

    private static CsvRow row(String name, String email) {
        return new CsvRow(2, name, email, null, null, null);
    }

    @Test
    void validRow_passes() {
        assertThat(validator.validate(row("Ada", "ada@example.com"))).isEmpty();
    }

    @Test
    void missingName_isMissingRequired() {
        assertThat(validator.validate(row("  ", "a@example.com"))).contains(CsvRowFailureReason.MISSING_REQUIRED);
        assertThat(validator.failingField(row("  ", "a@example.com"))).isEqualTo("name");
    }

    @Test
    void missingEmail_isMissingRequired() {
        assertThat(validator.validate(row("Ada", ""))).contains(CsvRowFailureReason.MISSING_REQUIRED);
        assertThat(validator.failingField(row("Ada", ""))).isEqualTo("email");
    }

    @Test
    void malformedEmail_isInvalidEmail() {
        assertThat(validator.validate(row("Ada", "not-an-email"))).contains(CsvRowFailureReason.INVALID_EMAIL);
        assertThat(validator.failingField(row("Ada", "not-an-email"))).isEqualTo("email");
    }

    @Test
    void overLongField_isFieldTooLong() {
        ImportProperties props = new ImportProperties();
        props.setMaxFieldLength(5);
        CsvRowValidator v = new CsvRowValidator(props);
        assertThat(v.validate(row("WayTooLongName", "a@example.com")))
            .contains(CsvRowFailureReason.FIELD_TOO_LONG);
    }
}
