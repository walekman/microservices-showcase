package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the transfer saga.
 *
 * <p>Deliberately NOT annotated {@code @Transactional}. A database transaction spanning
 * the HTTP calls below would hold a connection and row locks across two network
 * round-trips, and would roll back local state that the remote service has already
 * committed. Each state change is persisted by {@code TransferRepository.save}, which is
 * itself transactional, so every write commits independently. Do not add
 * {@code @Transactional} to this class.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final AccountClient accountClient;

    public TransferService(TransferRepository transferRepository, AccountClient accountClient) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
    }

    public Transfer execute(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        // Constructor guards reject a self-transfer before anything is persisted.
        Transfer transfer = transferRepository.save(new Transfer(fromAccountId, toAccountId, amount));
        log.info("Transfer {} started: {} -> {} amount {}", transfer.getId(), fromAccountId, toAccountId, amount);

        // Step 1: pre-validate both accounts. An optimisation for the common
        // mistyped-id case, NOT a guarantee -- an account can still disappear between
        // here and the debit, which is why step 2 handles every rejection on its own.
        try {
            accountClient.getAccount(fromAccountId);
            accountClient.getAccount(toAccountId);
        } catch (AccountRejectedException ex) {
            return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
        } catch (AccountServiceUnavailableException ex) {
            return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
        }

        // Step 2: debit the source. Nothing has moved yet, so any failure is clean.
        try {
            accountClient.debit(fromAccountId, amount);
        } catch (AccountRejectedException ex) {
            return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
        } catch (AccountServiceUnavailableException ex) {
            return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
        }

        // Step 3: credit the destination. Past this point the source is already debited,
        // so business rejection and infrastructure failure have identical consequences:
        // funds are stranded and something has to put them back. Plan 3 adds that.
        try {
            accountClient.credit(toAccountId, amount);
        } catch (AccountRejectedException ex) {
            return strand(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
        } catch (AccountServiceUnavailableException ex) {
            return strand(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
        }

        transfer.markCompleted();
        log.info("Transfer {} completed", transfer.getId());
        return transferRepository.save(transfer);
    }

    public Transfer getTransfer(UUID id) {
        return transferRepository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    public List<Transfer> listTransfers(TransferStatus status) {
        return status == null ? transferRepository.findAll() : transferRepository.findByStatus(status);
    }

    private Transfer fail(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markFailed(code, reason);
        log.info("Transfer {} failed [{}]: {}", transfer.getId(), code, reason);
        return transferRepository.save(transfer);
    }

    private Transfer strand(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markCompensationRequired(code, reason);
        log.error("Transfer {} needs compensation: {} was debited {} but {} was not credited [{}]: {}",
                transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(),
                transfer.getToAccountId(), code, reason);
        return transferRepository.save(transfer);
    }
}
