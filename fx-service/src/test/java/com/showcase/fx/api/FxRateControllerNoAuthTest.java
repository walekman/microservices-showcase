package com.showcase.fx.api;

import com.showcase.fx.service.FxQuote;
import com.showcase.fx.service.FxRateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The "no-auth" profile's open chain; FxRateControllerTest covers the default 401/403.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("no-auth")
class FxRateControllerNoAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FxRateService fxRateService;

    @Test
    void noTokenIsAcceptedUnderTheNoAuthProfile() throws Exception {
        when(fxRateService.quote("PLN", "EUR")).thenReturn(
                new FxQuote("PLN", "EUR", new BigDecimal("0.22819"), LocalDate.of(2026, 9, 23), false));

        mockMvc.perform(get("/fx/rates").param("base", "PLN").param("quote", "EUR"))
                .andExpect(status().isOk());
    }
}
