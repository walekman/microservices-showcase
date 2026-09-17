package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;
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
import java.util.Objects;
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
    private final FraudClient fraudClient;
    private final TransferSaveService transferSaveService;

    public TransferService(TransferRepository transferRepository, AccountClient accountClient,
                            FraudClient fraudClient, TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.fraudClient = fraudClient;
        this.transferSaveService = transferSaveService;
    }

    public Transfer execute(UUID fromAccountId, UUID toAccountId, BigDecimal amount, UUID initiatorId) {
        // Constructor guards reject a self-transfer before anything is persisted.
        // This save stays OUTSIDE the try below on purpose: SameAccountTransferException
        // must propagate to the caller as a 400, not be swallowed into UNEXPECTED_ERROR.
        Transfer transfer = transferRepository.save(new Transfer(fromAccountId, toAccountId, amount, initiatorId));
        log.info("Transfer {} started: {} -> {} amount {}", transfer.getId(), fromAccountId, toAccountId, amount);

        // Tracks whether the debit leg committed, so the catch-all below knows whether an
        // unexpected failure left money stranded or left everything untouched.
        boolean debited = false;

        try {
            // Step 1: pre-validate both accounts. An optimisation for the common
            // mistyped-id case, NOT a guarantee -- an account can still disappear between
            // here and the debit, which is why step 3 handles every rejection on its own.
            try {
                accountClient.accountExists(fromAccountId);
                accountClient.accountExists(toAccountId);
            } catch (AccountRejectedException ex) {
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 2: screen the source account before any money moves. A block or an
            // unreachable Fraud Service both fail clean here -- nothing to compensate, same
            // shape as every other pre-debit rejection. See docs/phase-5-fraud-service.md.
            try {
                fraudClient.check(fromAccountId);
            } catch (FraudRejectedException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 3: debit the source.
            try {
                accountClient.debit(fromAccountId, amount, transfer.getId() + ":debit");
            } catch (AccountRejectedException ex) {
                // Account understood and refused. Nothing moved -- genuinely clean.
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                // AMBIGUOUS, and the most dangerous state in this service. A read timeout is
                // indistinguishable from "the request never arrived": Account may have
                // committed the debit and failed to tell us. FAILED here therefore means
                // "debit NOT CONFIRMED", never "debit definitely did not happen".
                //
                // It is deliberately NOT routed to COMPENSATION_REQUIRED. That state means
                // "debit definitely succeeded, credit definitely did not", and the compensator
                // credits the source back on the strength of it. Feeding an ambiguous outcome
                // into it would make the compensator invent money whenever the debit never
                // actually landed -- strictly worse than under-reporting.
                //
                // BLOCKING PRECONDITION FOR THE COMPENSATOR PLAN: it must reconcile against
                // Account before crediting anything back, and must not read this combination
                // (FAILED + ACCOUNT_SERVICE_UNAVAILABLE on the debit leg) as "no money moved".
                log.error("Transfer {} debit outcome UNKNOWN for account {} amount {}: {}. "
                                + "Recorded FAILED, but the debit may have committed -- needs reconciliation.",
                        transfer.getId(), fromAccountId, amount, ex.getMessage());
                return fail(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }
            debited = true;

            // Step 4: screen the destination account. Money has already moved, so a
            // block or an unreachable Fraud Service both strand the transfer for
            // CompensationScheduler rather than failing clean.
            try {
                fraudClient.check(toAccountId);
            } catch (FraudRejectedException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 5: credit the destination. Past this point the source is already debited,
            // so business rejection and infrastructure failure have identical consequences:
            // funds are stranded and something has to put them back. CompensationScheduler
            // resolves that.
            try {
                accountClient.credit(toAccountId, amount, transfer.getId() + ":credit");
            } catch (AccountRejectedException ex) {
                return strand(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            transfer.markCompleted();
            log.info("Transfer {} completed", transfer.getId());
            return transferSaveService.save(transfer);
        } catch (RuntimeException ex) {
            // Anything the two client exceptions do not cover -- a DataAccessException or an
            // optimistic-lock failure from a save, a bug. Without this the row is orphaned in
            // PENDING: a 500 reaches the caller and nothing ever revisits it.
            if (transfer.getStatus() != TransferStatus.PENDING) {
                // Already settled in memory -- the failure was persisting that outcome.
                // Marking again would throw IllegalStateException from requirePending()
                // and mask the real cause.
                log.error("Transfer {} failed to persist terminal state {}", transfer.getId(), transfer.getStatus(), ex);
                // Wrapped, not rethrown raw: by now the transfer has an id and a row that still
                // reads PENDING, and the API has to hand that id back -- a caller who cannot
                // name the record cannot reconcile it. The original stays as the cause.
                throw new TransferPersistenceException(transfer.getId(), ex);
            }
            return debited
                    ? strand(transfer, TransferFailureCode.UNEXPECTED_ERROR, ex.toString())
                    : fail(transfer, TransferFailureCode.UNEXPECTED_ERROR, ex.toString());
        }
    }

    // Scoped to the initiator or the destination account's owner -- see
    // docs/phase-7b-account-ownership-authorization.md's Design Decisions for why the
    // destination-owner check is a live call rather than something stored on Transfer.
    // Objects.equals, not a raw .equals() call: ddl-auto: update cannot add a NOT NULL column
    // over a table with existing rows, so a pre-Phase-7b row can have a null initiatorId --
    // Objects.equals denies cleanly instead of NPE-ing into a 500.
    public Transfer getTransfer(UUID id, UUID callerId) {
        Transfer transfer = transferRepository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
        if (Objects.equals(transfer.getInitiatorId(), callerId)) {
            return transfer;
        }
        if (accountClient.isOwnedByCaller(transfer.getToAccountId())) {
            return transfer;
        }
        throw new TransferNotFoundException(id);
    }

    public List<Transfer> listTransfers(TransferStatus status) {
        return status == null ? transferRepository.findAll() : transferRepository.findByStatus(status);
    }

    private Transfer fail(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markFailed(code, reason);
        log.info("Transfer {} failed [{}]: {}", transfer.getId(), code, reason);
        return transferSaveService.save(transfer);
    }

    private Transfer strand(Transfer transfer, TransferFailureCode code, String reason) {
        transfer.markCompensationRequired(code, reason);
        log.error("Transfer {} needs compensation: {} was debited {} but {} was not credited [{}]: {}",
                transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(),
                transfer.getToAccountId(), code, reason);
        return transferSaveService.save(transfer);
    }
}
