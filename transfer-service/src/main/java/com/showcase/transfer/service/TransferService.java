package com.showcase.transfer.service;

import com.showcase.transfer.client.AccountClient;
import com.showcase.transfer.client.AccountRejectedException;
import com.showcase.transfer.client.AccountServiceUnavailableException;
import com.showcase.transfer.client.FraudClient;
import com.showcase.transfer.client.FraudRejectedException;
import com.showcase.transfer.client.FraudServiceUnavailableException;
import com.showcase.transfer.client.FxClient;
import com.showcase.transfer.client.FxRate;
import com.showcase.transfer.client.FxServiceUnavailableException;
import com.showcase.transfer.domain.DebitOutcomeUnknownException;
import com.showcase.transfer.domain.IdempotencyKeyConflictException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferInProgressException;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private final FxClient fxClient;
    private final TransferSaveService transferSaveService;

    public TransferService(TransferRepository transferRepository, AccountClient accountClient,
                            FraudClient fraudClient, FxClient fxClient, TransferSaveService transferSaveService) {
        this.transferRepository = transferRepository;
        this.accountClient = accountClient;
        this.fraudClient = fraudClient;
        this.fxClient = fxClient;
        this.transferSaveService = transferSaveService;
    }

    public Transfer execute(UUID fromAccountId, UUID toAccountId, BigDecimal amount, UUID initiatorId,
                            String idempotencyKey) {
        // A caller retrying the same request (a double-click, a retry after a lost response)
        // gets the transfer its first attempt created, never a second saga moving the money again.
        Optional<Transfer> earlier = transferRepository.findByInitiatorIdAndIdempotencyKey(initiatorId, idempotencyKey);
        if (earlier.isPresent()) {
            return replay(earlier.get(), fromAccountId, toAccountId, amount, idempotencyKey);
        }

        // Constructor guards reject a self-transfer before anything is looked up or persisted:
        // SameAccountTransferException must propagate to the caller as a 400.
        Transfer transfer = new Transfer(fromAccountId, toAccountId, amount, initiatorId, idempotencyKey);

        // Steps 1-2, entirely in memory: validate both accounts and lock the conversion. Nothing
        // here moves money, so every failure settles the transfer as FAILED before it is inserted.
        prepare(transfer);

        // The single insert. A PENDING row is born with its conversion locked, so no sweep can
        // ever find a PENDING row without the amounts it must replay (docs/phase-12-fx-rates-redis-cache.md).
        // A FAILED row goes through the outbox choke point like every other terminal write.
        try {
            transfer = transfer.getStatus() == TransferStatus.PENDING
                    ? transferRepository.save(transfer)
                    : transferSaveService.save(transfer);
        } catch (DataIntegrityViolationException ex) {
            // Two requests with the same key both missed the lookup above; the unique constraint
            // on (initiator_id, idempotency_key) let exactly one insert through. Nothing has moved
            // for this request -- prepare() only reads -- so it becomes a replay of the winner. No
            // winner means the violation was something else -- rethrow it.
            Transfer winner = transferRepository.findByInitiatorIdAndIdempotencyKey(initiatorId, idempotencyKey)
                    .orElseThrow(() -> ex);
            return replay(winner, fromAccountId, toAccountId, amount, idempotencyKey);
        }
        if (transfer.getStatus() != TransferStatus.PENDING) {
            log.info("Transfer {} failed before any money moved [{}]: {}",
                    transfer.getId(), transfer.getFailureCode(), transfer.getFailureReason());
            return transfer;
        }
        log.info("Transfer {} started: {} -> {} amount {} {}, credits {} {}", transfer.getId(), fromAccountId,
                toAccountId, amount, transfer.getSourceCurrency(), transfer.getCreditAmount(),
                transfer.getDestinationCurrency());
        return moveMoney(transfer);
    }

    /**
     * Steps 1-2. Marks the transfer FAILED in memory on any failure, or locks its conversion. Never
     * persists and never moves money, which is what makes the single insert in execute() safe.
     */
    private void prepare(Transfer transfer) {
        try {
            // Step 1: pre-validate both accounts, learning the currency each is held in. An
            // optimisation for the common mistyped-id case, NOT a guarantee -- an account can
            // still disappear before the debit, which is why the legs handle every rejection on their own.
            String sourceCurrency;
            String destinationCurrency;
            try {
                sourceCurrency = accountClient.accountCurrency(transfer.getFromAccountId());
                destinationCurrency = accountClient.accountCurrency(transfer.getToAccountId());
            } catch (AccountRejectedException ex) {
                transfer.markFailed(TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
                return;
            } catch (AccountServiceUnavailableException ex) {
                transfer.markFailed(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
                return;
            }

            // Step 2: price the transfer. The same currency needs no rate. Anything else asks FX
            // Service once, here, and never again: every later step and every replay uses the
            // locked values.
            if (sourceCurrency.equals(destinationCurrency)) {
                transfer.lockConversion(sourceCurrency, destinationCurrency, BigDecimal.ONE, null);
                return;
            }
            FxRate fx;
            try {
                fx = fxClient.rate(sourceCurrency, destinationCurrency);
            } catch (FxServiceUnavailableException ex) {
                transfer.markFailed(TransferFailureCode.FX_SERVICE_UNAVAILABLE, ex.getMessage());
                return;
            }
            BigDecimal creditAmount = Transfer.creditAmountFor(transfer.getAmount(), fx.rate());
            if (creditAmount.signum() == 0) {
                transfer.markFailed(TransferFailureCode.AMOUNT_TOO_SMALL, "%s %s converts to %s %s at rate %s"
                        .formatted(transfer.getAmount(), sourceCurrency, creditAmount, destinationCurrency, fx.rate()));
                return;
            }
            transfer.lockConversion(sourceCurrency, destinationCurrency, fx.rate(), fx.asOf());
        } catch (RuntimeException ex) {
            // Anything the client exceptions do not cover -- a bug, a mapper failure. Nothing has
            // moved and nothing is persisted yet, so this is a clean failure.
            transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR, ex.toString());
        }
    }

    /** Steps 3-6, on a persisted PENDING row with its conversion locked. */
    private Transfer moveMoney(Transfer transfer) {
        // Tracks whether the debit leg committed, so the catch-all below knows whether an
        // unexpected failure left money stranded or left everything untouched.
        boolean debited = false;

        try {
            // Step 3: screen the source account before any money moves. A block or an
            // unreachable Fraud Service both fail clean here -- nothing to compensate, same
            // shape as every other pre-debit rejection. See docs/phase-5-fraud-service.md.
            try {
                fraudClient.check(transfer.getFromAccountId());
            } catch (FraudRejectedException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return fail(transfer, TransferFailureCode.SOURCE_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 4: debit the source, in its own currency.
            try {
                accountClient.debit(transfer.getFromAccountId(), transfer.getAmount(), transfer.getSourceCurrency(),
                        transfer.getId() + ":debit");
            } catch (AccountRejectedException ex) {
                // Account understood and refused. Nothing moved -- genuinely clean.
                return fail(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                // AMBIGUOUS, and the most dangerous state in this service. A read timeout is
                // indistinguishable from "the request never arrived": Account may have
                // committed the debit and failed to tell us.
                //
                // So the transfer is NOT settled here. FAILED would be terminal -- no sweep
                // revisits it -- and would stay wrong for good if the debit had committed.
                // COMPENSATION_REQUIRED means "debit definitely succeeded", and the compensator
                // would credit the source back on a guess, inventing money if it never landed.
                // Left PENDING, the row is exactly what CompensationScheduler.sweepStalePending
                // exists for: it replays <id>:debit against Account's idempotency ledger and
                // settles the transfer from the answer, not from a guess.
                log.error("Transfer {} debit outcome UNKNOWN for account {} amount {}: {}. "
                                + "Left PENDING for the stale-PENDING sweep to reconcile.",
                        transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(), ex.getMessage());
                throw new DebitOutcomeUnknownException(transfer.getId(), ex);
            }
            debited = true;

            // Step 5: screen the destination account. Money has already moved, so a
            // block or an unreachable Fraud Service both strand the transfer for
            // CompensationScheduler rather than failing clean.
            try {
                fraudClient.check(transfer.getToAccountId());
            } catch (FraudRejectedException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, ex.getDetail());
            } catch (FraudServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.DESTINATION_FRAUD_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            // Step 6: credit the destination the locked amount, in its own currency. Past this
            // point the source is already debited, so business rejection and infrastructure
            // failure have identical consequences: funds are stranded and something has to put
            // them back. CompensationScheduler resolves that.
            try {
                accountClient.credit(transfer.getToAccountId(), transfer.amountToCredit(),
                        transfer.getDestinationCurrency(), transfer.getId() + ":credit");
            } catch (AccountRejectedException ex) {
                return strand(transfer, TransferFailureCode.fromAccountCode(ex.getCode()), ex.getDetail());
            } catch (AccountServiceUnavailableException ex) {
                return strand(transfer, TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
            }

            transfer.markCompleted();
            log.info("Transfer {} completed", transfer.getId());
            return transferSaveService.save(transfer);
        } catch (DebitOutcomeUnknownException ex) {
            // Deliberately unsettled -- see step 4. Must not fall into the catch-all below,
            // which would record the PENDING row as FAILED.
            throw ex;
        } catch (RuntimeException ex) {
            // Anything the client exceptions do not cover -- a DataAccessException or an
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

    private Transfer replay(Transfer earlier, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                            String idempotencyKey) {
        if (earlier.conflictsWith(fromAccountId, toAccountId, amount)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        if (earlier.getStatus() == TransferStatus.PENDING) {
            throw new TransferInProgressException(earlier.getId());
        }
        log.info("Transfer {} replayed for idempotency key {}: already {}",
                earlier.getId(), idempotencyKey, earlier.getStatus());
        return earlier;
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

    // Outgoing: every transfer the caller started, in any status. Incoming: COMPLETED transfers
    // into any account the caller owns -- a recipient sees money that arrived, never someone
    // else's failed or unsettled attempt (nor its failure reason). The caller's accounts come
    // from a live call to Account, for the reason getTransfer's destination-owner check above
    // is live; if Account is unavailable that propagates (503) rather than quietly answering
    // with the outgoing half alone.
    public List<Transfer> listMyTransfers(UUID callerId, TransferStatus status) {
        List<Transfer> transfers = new ArrayList<>(status == null
                ? transferRepository.findByInitiatorId(callerId)
                : transferRepository.findByInitiatorIdAndStatus(callerId, status));
        if (status != null && status != TransferStatus.COMPLETED) {
            return transfers;
        }
        List<UUID> accountIds = accountClient.callerAccountIds();
        if (accountIds.isEmpty()) {
            return transfers;
        }
        transferRepository.findByToAccountIdInAndStatus(accountIds, TransferStatus.COMPLETED).stream()
                .filter(incoming -> !Objects.equals(incoming.getInitiatorId(), callerId)) // already listed as outgoing
                .forEach(transfers::add);
        return transfers;
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
        log.error("Transfer {} needs compensation: {} was debited {} {} but {} was not credited [{}]: {}",
                transfer.getId(), transfer.getFromAccountId(), transfer.getAmount(), transfer.getSourceCurrency(),
                transfer.getToAccountId(), code, reason);
        return transferSaveService.save(transfer);
    }
}
