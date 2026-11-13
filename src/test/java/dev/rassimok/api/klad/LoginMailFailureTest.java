package dev.rassimok.api.klad;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.vertx.ext.mail.SMTPException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

// JVM only: a native test cannot see the binary's log from here.
@QuarkusTest
@WithTestResource(RefusingSmtpServer.class)
class LoginMailFailureTest {

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clearSendLog() throws SQLException {
        TestSql.execute(dataSource, "delete from login_mail");
    }

    @Test
    void shouldAnswerErrorAndLogNoAddress_whenRequestCode_givenServerRefusesRecipient() throws SQLException {
        // given
        String email = TestPlayers.uniqueEmail("refused-recipient");
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(collector);

        // when
        ValidatableResponse response;
        try {
            response = given().contentType(ContentType.JSON).body(Map.of("email", email))
                    .when().post("/login/code").then();
        } finally {
            root.removeHandler(collector);
        }

        // then
        response.statusCode(500).body("error", equalTo("internal_server_error"));
        List<String> loginMessages = records.stream()
                .filter(record -> LoginResource.class.getName().equals(record.getLoggerName()))
                .map(LogRecord::getMessage)
                .toList();
        assertThat(loginMessages, equalTo(List.of("Login code mail not sent: " + SMTPException.class.getName()
                + ", reply " + RefusingSmtpServer.REFUSED_REPLY_CODE)));
        assertThat(records.stream().filter(record -> null != record.getThrown()).toList(), empty());
        assertThat(records.stream().filter(record -> textOf(record).contains(email)).toList(), empty());
        assertThat(TestSql.count(dataSource, "select count(*) from login_mail where email_hash = ?",
                Sessions.hash(email)), equalTo(1L));
    }

    private static String textOf(LogRecord record) {
        StringBuilder text = new StringBuilder(String.valueOf(record.getMessage()));
        text.append(Arrays.toString(record.getParameters()));
        for (Throwable cause = record.getThrown(); null != cause; cause = cause.getCause()) {
            text.append(cause);
        }
        return text.toString();
    }
}
