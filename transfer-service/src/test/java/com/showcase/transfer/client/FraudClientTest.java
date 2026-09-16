package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FraudClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://fraud-service:8084";

    private MockRestServiceServer server;
    private FraudClient fraudClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        fraudClient = new FraudClient(builder.build(), new ObjectMapper());
    }

    @Test
    void checkSucceedsWhenTheAccountIsClear() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess());

        assertThatCode(() -> fraudClient.check(ACCOUNT_ID)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void checkThrowsRejectedWithDetailWhenTheAccountIsBlocked() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_BLOCKED",
                        "Account is blocklisted: " + ACCOUNT_ID));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudRejectedException.class)
                .satisfies(thrown -> org.assertj.core.api.Assertions.assertThat(((FraudRejectedException) thrown).getDetail())
                        .isEqualTo("Account is blocklisted: " + ACCOUNT_ID));
        server.verify();
    }

    @Test
    void checkThrowsUnavailableOnServerError() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableOn3xxRedirect() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableWhenTheConnectionFails() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableWhenTheErrorBodyIsNotAProblem() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void checkThrowsUnavailableWhenTheProblemCodeIsNotAccountBlocked() {
        server.expect(requestToUriTemplate(BASE_URL + "/fraud-check?accountId={id}", ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "SOME_OTHER_CODE", "not what we expected"));

        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudServiceUnavailableException.class);
        server.verify();
    }

    private static ResponseCreator problem(HttpStatus status, String code, String detail) {
        return withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"type":"https://showcase.example/errors/x","title":"t","status":%d,
                         "detail":"%s","code":"%s","timestamp":"2026-09-16T12:00:00Z"}
                        """.formatted(status.value(), detail, code));
    }
}
