package com.cadence.service;

import org.springframework.stereotype.Component;

/**
 * F42 CSV/formula-injection neutralization at the EXPORT boundary (FR-018/SC-006). Cell values are stored
 * VERBATIM (so a legitimate {@code +44...} phone or a {@code -}-led name is never corrupted); this escaper is
 * the single sink any future export into a spreadsheet/CSV context MUST call. Ingestion never evaluates a cell.
 *
 * <p>Neutralization: a value whose first significant character (after stripping a UTF-8 BOM and leading
 * whitespace) is a formula trigger ({@code = + - @ |}, tab, or CR) is prefixed with a single quote so a
 * spreadsheet treats it as literal text; the value is then RFC-4180 field-quoted if it contains a delimiter,
 * quote, or newline. A value with no trigger is returned unchanged unless it needs RFC-4180 quoting.
 */
@Component
public class CsvInjectionEscaper {

    private static final char BOM = '﻿';

    /** @return the value safe to emit into a spreadsheet/CSV cell; verbatim values are never mutated in storage. */
    public String escapeForSpreadsheet(String value) {
        if (value == null) {
            return null;
        }
        String neutralized = needsFormulaPrefix(value) ? "'" + value : value;
        return rfc4180Quote(neutralized);
    }

    private static boolean needsFormulaPrefix(String value) {
        int i = 0;
        // Skip a leading BOM and leading SPACES only (a "  =cmd" payload is still dangerous). Do NOT skip tab
        // or CR — those are themselves injection triggers, so a "\t=cmd" / "\rcmd" value must be neutralized.
        while (i < value.length() && (value.charAt(i) == BOM || value.charAt(i) == ' ')) {
            i++;
        }
        if (i >= value.length()) {
            return false;
        }
        char c = value.charAt(i);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '|' || c == '\t' || c == '\r';
    }

    private static String rfc4180Quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
            && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
