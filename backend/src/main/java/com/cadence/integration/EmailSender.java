package com.cadence.integration;

import java.util.Map;

public interface EmailSender {

    void sendEmail(String toInternalId, String templateId, Map<String, String> mergeFields);

    void sendSystemAlert(String taskName, String errorSummary);
}
