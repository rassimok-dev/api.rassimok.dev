package dev.rassimok.api.klad;

import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.vertx.core.impl.NoStackTraceThrowable;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogRecord;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

// JVM only: the log handler and the injected data source need the
// application in this JVM.
@QuarkusTest
@WithTestResource(value = RefusingSmtpServer.class, initArgs = @ResourceArg(name = RefusingSmtpServer.KEY, value = ""))
class LoginMailWithoutKeyTest {

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clearSendLog() throws SQLException {
        TestSql.execute(dataSource, "delete from login_mail");
    }

    // Reply 0 and no SMTPException: the mailer gave up before the recipient
    // command, where the stub would have answered 550.
    @Test
    void shouldFailBeforeSendingAddress_whenRequestCode_givenNoMailKey() {
        // given
        String email = TestPlayers.uniqueEmail("no-key");
        List<LogRecord> records = new CopyOnWriteArrayList<>();

        // when
        ValidatableResponse response = LoginMailFailureTest.requestCodeCollectingLogs(email, records);

        // then
        response.statusCode(500).body("error", equalTo("internal_server_error"));
        assertThat(LoginMailFailureTest.loginMessages(records),
                equalTo(List.of("Login code mail not sent: " + NoStackTraceThrowable.class.getName() + ", reply 0")));
        assertThat(records.stream().filter(record -> LoginMailFailureTest.textOf(record).contains(email)).toList(),
                empty());
    }
}
