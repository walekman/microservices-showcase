package com.showcase.fx.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RateProviderTest {

    private static final String BASE_URL = "http://provider.test";
    // Spring encodes URI variable values in full, so the comma arrives as %2C. Frankfurter decodes it
    // (verified live on 2026-09-24: same body as with a literal comma).
    private static final String PLN_URL = BASE_URL + "/latest?base=PLN&symbols=EUR%2CUSD%2CGBP";

    private MockRestServiceServer server;
    private RateProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RateProvider(builder.build(), List.of("EUR", "USD", "GBP", "PLN"));
    }

    @Test
    void fetchesEveryOtherSupportedCurrencyForTheBaseInOneCall() {
        // The body is the real shape api.frankfurter.dev/v1 returned on 2026-09-24.
        server.expect(requestTo(PLN_URL))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"PLN","date":"2026-09-24","rates":{"EUR":0.22819,"GBP":0.19621,"USD":0.25938}}
                        """, MediaType.APPLICATION_JSON));

        RateTable table = provider.fetch("PLN");

        assertThat(table.base()).isEqualTo("PLN");
        assertThat(table.asOf()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(table.rates()).containsOnlyKeys("EUR", "GBP", "USD");
        assertThat(table.rates().get("EUR")).isEqualByComparingTo("0.22819");
        server.verify();
    }

    @Test
    void aServerErrorIsProviderUnavailable() {
        server.expect(requestTo(PLN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aConnectionFailureIsProviderUnavailable() {
        server.expect(requestTo(PLN_URL)).andRespond(withException(new ConnectException("connection refused")));

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aBodyForADifferentBaseIsProviderUnavailable() {
        server.expect(requestTo(PLN_URL))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"EUR","date":"2026-09-24","rates":{"PLN":4.38}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aNullRateIsProviderUnavailableNotAnNpe() {
        server.expect(requestTo(PLN_URL))
                .andRespond(withSuccess("""
                        {"amount":1.0,"base":"PLN","date":"2026-09-24","rates":{"EUR":null}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.fetch("PLN")).isInstanceOf(ProviderUnavailableException.class);
    }
}
