package com.showcase.transfer.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FxClientTest {

    private static final String BASE_URL = "http://fx-service:8085";
    private static final String PLN_EUR = BASE_URL + "/fx/rates?base=PLN&quote=EUR";

    private MockRestServiceServer server;
    private FxClient fxClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        fxClient = new FxClient(builder.build());
    }

    @Test
    void returnsTheRate() {
        server.expect(requestTo(PLN_EUR)).andRespond(withSuccess("""
                {"base":"PLN","quote":"EUR","rate":0.22819,"asOf":"2026-09-23","stale":true}
                """, MediaType.APPLICATION_JSON));

        FxRate rate = fxClient.rate("PLN", "EUR");

        assertThat(rate.rate()).isEqualByComparingTo("0.22819");
        assertThat(rate.asOf()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(rate.stale()).isTrue();
        server.verify();
    }

    @Test
    void a503IsUnavailable() {
        server.expect(requestTo(PLN_EUR)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }

    // FX has no business rejections: its 400 means Account and FX disagree on the currency list.
    @Test
    void a400IsUnavailableNotARejection() {
        server.expect(requestTo(PLN_EUR)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }

    @Test
    void aConnectionFailureIsUnavailable() {
        server.expect(requestTo(PLN_EUR)).andRespond(withException(new ConnectException("connection refused")));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }

    @Test
    void aZeroOrMissingRateIsUnavailable() {
        server.expect(requestTo(PLN_EUR)).andRespond(withSuccess("""
                {"base":"PLN","quote":"EUR","rate":0,"asOf":"2026-09-23","stale":false}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fxClient.rate("PLN", "EUR")).isInstanceOf(FxServiceUnavailableException.class);
    }
}
