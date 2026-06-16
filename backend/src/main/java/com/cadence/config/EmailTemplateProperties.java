package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code email.template.*} block (F21). Bounds on a single template so authoring content
 * cannot exhaust storage nor amplify render-time substitution work (FR-020/D11). Empty subject/body is
 * rejected at save. Auto-registers via the existing {@code @ConfigurationPropertiesScan}. No secrets.
 */
@ConfigurationProperties(prefix = "email.template")
public class EmailTemplateProperties {

    private int maxSubjectLength = 200;
    private int maxBodyLength = 10000;
    private int maxTokensPerTemplate = 50;
    private int maxVariantsPerType = 20;

    public int getMaxSubjectLength() { return maxSubjectLength; }
    public void setMaxSubjectLength(int v) { this.maxSubjectLength = v; }

    public int getMaxBodyLength() { return maxBodyLength; }
    public void setMaxBodyLength(int v) { this.maxBodyLength = v; }

    public int getMaxTokensPerTemplate() { return maxTokensPerTemplate; }
    public void setMaxTokensPerTemplate(int v) { this.maxTokensPerTemplate = v; }

    public int getMaxVariantsPerType() { return maxVariantsPerType; }
    public void setMaxVariantsPerType(int v) { this.maxVariantsPerType = v; }
}
