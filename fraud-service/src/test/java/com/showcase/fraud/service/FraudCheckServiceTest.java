package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCheckServiceTest {

    private static final UUID BLOCKED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLEAR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final FraudCheckService service = new FraudCheckService(new FraudBlocklistProperties(List.of(BLOCKED)));

    @Test
    void throwsForABlockedAccount() {
        assertThatThrownBy(() -> service.check(BLOCKED))
                .isInstanceOf(AccountBlockedException.class)
                .hasMessageContaining(BLOCKED.toString());
    }

    @Test
    void passesForAnUnlistedAccount() {
        assertThatCode(() -> service.check(CLEAR)).doesNotThrowAnyException();
    }

    @Test
    void aNullConfiguredListDefaultsToEmptyRatherThanThrowing() {
        FraudCheckService noBlocklist = new FraudCheckService(new FraudBlocklistProperties(null));

        assertThatCode(() -> noBlocklist.check(BLOCKED)).doesNotThrowAnyException();
    }
}
