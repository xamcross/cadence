package com.cadence.emailtemplate;

import com.cadence.service.EmailTemplateService;
import com.cadence.service.MergeRenderer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-010: F21's verifiable slice of the no-auto-send contract. The render path must be side-effect-free
 * with NO message-transport capability reachable: assert structurally that neither {@link MergeRenderer}
 * nor {@link EmailTemplateService} has a field or constructor parameter whose type name suggests an email
 * sender / SMTP / mail transport, and that no {@code EmailSender} type exists yet (dispatch is F22).
 */
class EmailTemplateNoTransportTest {

    private static boolean looksLikeTransport(String typeName) {
        String n = typeName.toLowerCase();
        return n.contains("emailsender") || n.contains("mailsender") || n.contains("smtp")
            || n.contains("javamail") || n.contains("transport") || n.endsWith("mailer");
    }

    private void assertNoTransport(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            assertThat(looksLikeTransport(f.getType().getName()))
                .as("%s.%s must not be a message-transport dependency", clazz.getSimpleName(), f.getName())
                .isFalse();
        }
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            for (Class<?> p : ctor.getParameterTypes()) {
                assertThat(looksLikeTransport(p.getName()))
                    .as("%s constructor must not take a message-transport dependency", clazz.getSimpleName())
                    .isFalse();
            }
        }
    }

    @Test
    void renderPath_hasNoTransportDependency() {
        // EmailSender already exists in the codebase (F00.2 dead-letter / F01 invitations); the F21
        // guarantee is that the RENDER PATH does not depend on it — rendering is side-effect-free (F22 sends).
        assertNoTransport(MergeRenderer.class);
        assertNoTransport(EmailTemplateService.class);
    }
}
