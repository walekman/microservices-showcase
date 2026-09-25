package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.domain.BlockedAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudCheckServiceTest {

    private static final UUID BLOCKED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CLEAR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final BlockedAccountRepository repository = mock(BlockedAccountRepository.class);
    private final FraudCheckService service = new FraudCheckService(repository);

    @Test
    void throwsForABlockedAccount() {
        when(repository.existsById(BLOCKED)).thenReturn(true);

        assertThatThrownBy(() -> service.check(BLOCKED))
                .isInstanceOf(AccountBlockedException.class)
                .hasMessageContaining(BLOCKED.toString());
    }

    @Test
    void passesForAnUnlistedAccount() {
        when(repository.existsById(CLEAR)).thenReturn(false);

        assertThatCode(() -> service.check(CLEAR)).doesNotThrowAnyException();
    }

    // Fail closed: an unreadable blocklist must never read as "not blocked".
    @Test
    void propagatesADatabaseFailureRatherThanPassingTheAccount() {
        when(repository.existsById(BLOCKED)).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.check(BLOCKED)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
