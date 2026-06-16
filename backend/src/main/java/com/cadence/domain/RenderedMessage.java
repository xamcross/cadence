package com.cadence.domain;

import java.util.List;

/**
 * The transient output of {@code MergeRenderer} (F21, data-model §4). Never persisted. {@code subject}
 * is control/line-separator-stripped plain text; {@code bodyText} is the plain-text part; {@code bodyHtml}
 * is the HTML-escaped part with values entity-encoded and URL tokens as {@code href==text} anchors;
 * {@code missingFields} lists (in first-occurrence order) the tokens whose value was absent or empty,
 * each rendered in place as {@code [[missing:<token>]]} (FR-014).
 */
public record RenderedMessage(String subject, String bodyText, String bodyHtml, List<String> missingFields) {}
