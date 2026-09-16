package com.showcase.fraud.api;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.service.FraudCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FraudCheckController.class)
class FraudCheckControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCheckService fraudCheckService;

    @Test
    void returns200WhenTheAccountIsClear() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void returns422WithAccountBlockedCodeWhenTheServiceRejects() throws Exception {
        doThrow(new AccountBlockedException(ACCOUNT_ID)).when(fraudCheckService).check(ACCOUNT_ID);

        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void returns400WhenAccountIdIsMissing() throws Exception {
        mockMvc.perform(get("/fraud-check"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returns400WhenAccountIdIsMalformed() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
