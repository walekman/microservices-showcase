package com.showcase.fx.api;

import com.showcase.fx.service.FxQuote;
import com.showcase.fx.service.FxRateService;
import com.showcase.fx.service.RateUnavailableException;
import com.showcase.fx.service.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @SpringBootTest, not @WebMvcTest -- the slice does not load this service's SecurityConfig
// (see fraud-service's FraudCheckControllerTest). FxRateService is mocked, so no Redis is needed.
@SpringBootTest
@AutoConfigureMockMvc
class FxRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FxRateService fxRateService;

    @Test
    void returnsTheQuote() throws Exception {
        when(fxRateService.quote("PLN", "EUR")).thenReturn(
                new FxQuote("PLN", "EUR", new BigDecimal("0.22819"), LocalDate.of(2026, 9, 23), false));

        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("PLN"))
                .andExpect(jsonPath("$.quote").value("EUR"))
                .andExpect(jsonPath("$.rate").value(0.22819))
                .andExpect(jsonPath("$.asOf").value("2026-09-23"))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void anUnsupportedCurrencyIs400() throws Exception {
        when(fxRateService.quote("JPY", "EUR")).thenThrow(new UnsupportedCurrencyException("JPY"));

        mockMvc.perform(get("/fx/rates").param("base", "JPY").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void noRateAvailableIs503() throws Exception {
        when(fxRateService.quote("PLN", "EUR")).thenThrow(new RateUnavailableException("nothing cached"));

        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FX_RATE_UNAVAILABLE"));
    }

    @Test
    void aMissingParameterIs400() throws Exception {
        mockMvc.perform(get("/fx/rates").param("base", "PLN").with(jwt().authorities(() -> "fx-reader")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithoutFxReaderIs403() throws Exception {
        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR")
                        .with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isForbidden());
    }
}
