package com.cadence.calendar;

import com.cadence.domain.CalendarConnection;
import com.cadence.domain.CalendarProvider;
import com.cadence.domain.Member;
import com.cadence.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** SC-002 cold path: a fresh converter (as after a restart) decrypts the stored refresh token. */
class CalendarRestartPersistenceTest extends CalendarItBase {

    @Test
    void coldTemplate_decryptsRefreshTokenToOriginal() {
        Member m = member("alex@x.com", Role.RECRUITER);
        CalendarConnection c = connect(m, CalendarProvider.GOOGLE, "alex@example.com");

        MongoTemplate cold = coldTemplate();
        CalendarConnection reloaded = cold.findById(c.getId(), CalendarConnection.class);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getRefreshToken()).isEqualTo("refresh-google");
        assertThat(reloaded.getProviderAccountId()).isEqualTo("alex@example.com");
    }
}
