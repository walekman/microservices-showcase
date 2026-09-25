function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// GET /transfers/mine lists both directions, each marked relative to the caller.
function isIncoming(t) {
    return t.direction === 'INCOMING';
}

// The account on the other side of a transfer from where the caller stands.
function counterpartyId(t) {
    return isIncoming(t) ? t.fromAccountId : t.toAccountId;
}

// Outgoing: the amount sent, plus -- for a converted transfer -- what the recipient received.
// Incoming: what landed in the caller's account, plus what the sender paid when converted. A
// transfer from before currencies existed has no currency fields at all, and shows the bare amount.
function formatTransferAmount(t) {
    const sent = `${Number(t.amount).toFixed(2)} ${escapeHtml(t.sourceCurrency || '')}`.trim();
    const converted = t.creditAmount != null && t.destinationCurrency !== t.sourceCurrency;
    if (isIncoming(t)) {
        const received = converted
            ? `${Number(t.creditAmount).toFixed(2)} ${escapeHtml(t.destinationCurrency)}`
            : sent;
        return `<span class="amount-main in"><span class="amount-arrow" aria-hidden="true">${ARROW_IN}</span>+${received}</span>`
            + (converted ? `<span class="amount-sub">from ${sent}</span>` : '');
    }
    return `<span class="amount-main out"><span class="amount-arrow" aria-hidden="true">${ARROW_OUT}</span>−${sent}</span>`
        + (converted ? `<span class="amount-sub">→ ${Number(t.creditAmount).toFixed(2)} ${escapeHtml(t.destinationCurrency)}</span>` : '');
}

// Down-left into the account for money received, up-right out of it for money sent.
const ARROW_IN = '<svg viewBox="0 0 12 12"><path d="M9.5 2.5 2.5 9.5M2.5 4v5.5H8" /></svg>';
const ARROW_OUT = '<svg viewBox="0 0 12 12"><path d="M2.5 9.5 9.5 2.5M4 2.5h5.5V8" /></svg>';

function initials(name) {
    return String(name).trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase();
}

// Label and badge colour per TransferStatus. COMPENSATION_REQUIRED is still settling, so it reads
// as in progress rather than failed; COMPENSATED means the money came back.
const STATUS_BADGES = {
    COMPLETED: ['Completed', 'success'],
    PENDING: ['Pending', 'warning'],
    COMPENSATION_REQUIRED: ['Reversing', 'warning'],
    COMPENSATED: ['Reversed', 'neutral'],
    FAILED: ['Failed', 'danger'],
    COMPENSATION_FAILED: ['Needs review', 'danger'],
};

// A completed transfer takes its row's arrow colour -- green received, grey sent -- so only the
// statuses that need attention (pending, failed, reversed) stand out in their own colours.
function statusBadge(status, incoming) {
    const [label, tone] = STATUS_BADGES[status] || [status, 'neutral'];
    const rowTone = status === 'COMPLETED' ? (incoming ? 'success' : 'neutral') : tone;
    return `<span class="badge ${rowTone}">${escapeHtml(label)}</span>`;
}

async function bootstrap() {
    try {
        await Auth.handleRedirectCallback();
    } catch (err) {
        if (err instanceof AuthCallbackError) {
            document.getElementById('loading').textContent = err.message;
            return;
        }
        throw err;
    }
    if (!Auth.isAuthenticated()) {
        await Auth.login();
        return;
    }
    document.getElementById('logout-button').addEventListener('click', () => Auth.logout());

    const accounts = await Api.get('/accounts/mine');
    document.getElementById('app').hidden = false;
    document.getElementById('loading').hidden = true;
    if (accounts.length === 0) {
        renderOnboarding();
    } else {
        await renderDashboard(accounts[0]);
    }
}

function renderOnboarding() {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card onboarding">
            <h2>Welcome! Let's set up your account.</h2>
            <p class="lead">You'll start with a balance of 1000.00 in the currency you choose.</p>
            <div class="field">
                <label for="owner-name">Your name</label>
                <input id="owner-name" type="text" autocomplete="name" placeholder="e.g. Ada Lovelace" />
            </div>
            <div class="field">
                <span class="field-label" id="currency-label">Currency</span>
                <div class="segmented" role="radiogroup" aria-labelledby="currency-label">
                    ${['EUR', 'USD', 'GBP', 'PLN'].map((code, index) => `
                        <input type="radio" name="currency" id="currency-${code}" value="${code}" ${index === 0 ? 'checked' : ''} />
                        <label for="currency-${code}">${code}</label>`).join('')}
                </div>
            </div>
            <button id="create-account-button" type="button" class="btn-primary">Create my account</button>
            <p id="onboarding-error" class="callout error" hidden></p>
        </div>`;
    document.getElementById('owner-name').focus();

    const createButton = document.getElementById('create-account-button');
    createButton.addEventListener('click', async () => {
        const ownerName = document.getElementById('owner-name').value.trim();
        const errorEl = document.getElementById('onboarding-error');
        errorEl.hidden = true;
        if (!ownerName) {
            errorEl.textContent = 'Please enter your name.';
            errorEl.hidden = false;
            return;
        }
        const currency = document.querySelector('input[name="currency"]:checked').value;
        createButton.disabled = true;
        try {
            const account = await Api.post('/accounts', { ownerName, initialBalance: '1000.00', currency });
            renderDashboard(account);
        } catch (err) {
            errorEl.textContent = err.friendlyMessage ? err.friendlyMessage() : err.message;
            errorEl.hidden = false;
            createButton.disabled = false;
        }
    });
}

async function renderDashboard(account, { flashMessage } = {}) {
    document.getElementById('header-user').textContent = account.ownerName;
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card account-card">
            <div class="label">Account holder</div>
            <div class="account-owner">${escapeHtml(account.ownerName)}</div>
            <div class="balance">
                <span class="balance-amount">${Number(account.balance).toFixed(2)}</span>
                <span class="currency-badge">${escapeHtml(account.currency)}</span>
            </div>
            <div class="account-actions">
                <span class="id-pill" title="Share this so others can send you money">
                    <code id="account-id">${escapeHtml(account.id)}</code>
                    <button id="copy-account-id-button" type="button">Copy</button>
                </span>
                <button id="send-money-button" type="button" class="btn-primary">Send money</button>
            </div>
            <p id="dashboard-flash" class="callout success" hidden></p>
        </div>
        <div id="transfer-section"></div>
        <div id="quick-transfers-section"></div>
        <div id="history-section"></div>`;

    if (flashMessage) {
        const flashEl = document.getElementById('dashboard-flash');
        flashEl.textContent = flashMessage;
        flashEl.hidden = false;
    }

    // The ID is what someone else types into "To account" to pay this account.
    const copyButton = document.getElementById('copy-account-id-button');
    copyButton.addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(account.id);
            copyButton.textContent = 'Copied';
        } catch {
            copyButton.textContent = 'Copy failed';
        }
        setTimeout(() => { copyButton.textContent = 'Copy'; }, 1500);
    });

    const transfers = await Api.get('/transfers/mine');
    document.getElementById('send-money-button')
        .addEventListener('click', () => renderTransferForm(account, transfers));
    await renderQuickTransfers(transfers);
    await renderHistory(transfers);
}

function renderTransferForm(account, transfers, prefillAccountId) {
    const section = document.getElementById('transfer-section');
    section.innerHTML = `
        <div class="card">
            <div class="card-header">
                <h3>Send money</h3>
                <button id="close-transfer-button" type="button" class="btn-icon" aria-label="Close">×</button>
            </div>
            <div class="field">
                <label for="to-account">To account ID</label>
                <input id="to-account" type="text" class="mono" placeholder="Paste the recipient's account ID"
                       value="${escapeHtml(prefillAccountId || '')}" />
            </div>
            <div class="field">
                <label for="amount">Amount</label>
                <div class="input-affix">
                    <input id="amount" type="number" step="0.01" min="0.01" placeholder="0.00" />
                    <span class="suffix">${escapeHtml(account.currency)}</span>
                </div>
                <p id="amount-hint" class="field-hint">Available: ${Number(account.balance).toFixed(2)} ${escapeHtml(account.currency)}</p>
            </div>
            <div id="transfer-quote" class="callout info" hidden></div>
            <p id="transfer-error" class="callout error" hidden></p>
            <div class="form-actions">
                <button id="submit-transfer-button" type="button" class="btn-primary">Send</button>
                <button id="cancel-transfer-button" type="button" class="btn-secondary">Cancel</button>
            </div>
        </div>`;

    const close = () => { section.innerHTML = ''; };
    document.getElementById('close-transfer-button').addEventListener('click', close);
    document.getElementById('cancel-transfer-button').addEventListener('click', close);
    section.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    document.getElementById(prefillAccountId ? 'amount' : 'to-account').focus();

    // One Idempotency-Key per intended transfer. Resending the same transfer (a double-click,
    // a retry after a lost response) reuses it, so Transfer Service replays the first attempt
    // instead of moving the money twice. Editing either field makes it a different transfer.
    let idempotencyKey = crypto.randomUUID();
    const startNewTransfer = () => { idempotencyKey = crypto.randomUUID(); };

    // A soft warning only: the server decides whether the funds are there.
    const amountHint = document.getElementById('amount-hint');
    const refreshAmountHint = () => {
        const overBalance = Number(document.getElementById('amount').value) > Number(account.balance);
        amountHint.classList.toggle('warn', overBalance);
        amountHint.textContent = `${overBalance ? 'More than your balance — ' : ''}Available: `
            + `${Number(account.balance).toFixed(2)} ${account.currency}`;
    };

    // An approximate quote while the user types. Advisory only: Transfer Service prices the
    // transfer itself when it is sent, and the result shows the amount it actually locked.
    // The recipient's summary is kept so the success message can name them.
    const quoteEl = document.getElementById('transfer-quote');
    let latestQuote = 0;
    let knownRecipient = null;
    const refreshQuote = async () => {
        const requestId = ++latestQuote;
        quoteEl.hidden = true;
        const toAccountId = document.getElementById('to-account').value.trim();
        const amount = Number(document.getElementById('amount').value);
        if (!toAccountId || !(amount > 0)) {
            return;
        }
        try {
            const recipient = await Api.get(`/accounts/${encodeURIComponent(toAccountId)}/summary`);
            knownRecipient = { id: toAccountId, ...recipient };
            if (requestId !== latestQuote || recipient.currency === account.currency) {
                return;
            }
            const fx = await Api.get(`/fx/rates?base=${account.currency}&quote=${recipient.currency}`);
            if (requestId !== latestQuote) {
                return; // a newer edit superseded this quote
            }
            quoteEl.innerHTML = `
                <div>
                    <div class="quote-main">${escapeHtml(recipient.ownerName)} receives ≈ ${(amount * fx.rate).toFixed(2)} ${escapeHtml(recipient.currency)}</div>
                    <div class="quote-meta">1 ${escapeHtml(account.currency)} = ${escapeHtml(fx.rate)} ${escapeHtml(recipient.currency)}
                        · as of ${escapeHtml(fx.asOf)}${fx.stale ? ' · last known rate' : ''}
                        · the exact amount is fixed when you send</div>
                </div>`;
            quoteEl.hidden = false;
        } catch (err) {
            // No quote is not a form error: sending still works, and the server prices it.
        }
    };
    document.getElementById('to-account').addEventListener('input', () => { startNewTransfer(); refreshQuote(); });
    document.getElementById('amount').addEventListener('input', () => {
        startNewTransfer();
        refreshAmountHint();
        refreshQuote();
    });

    const submitButton = document.getElementById('submit-transfer-button');
    submitButton.addEventListener('click', async () => {
        const toAccountId = document.getElementById('to-account').value.trim();
        const amount = document.getElementById('amount').value;
        const errorEl = document.getElementById('transfer-error');
        errorEl.hidden = true;
        submitButton.disabled = true;
        submitButton.innerHTML = '<span class="spinner spinner-sm" aria-hidden="true"></span>Sending…';
        try {
            const transfer = await Api.post('/transfers', { fromAccountId: account.id, toAccountId, amount },
                { 'Idempotency-Key': idempotencyKey });
            const refreshedAccounts = await Api.get('/accounts/mine');
            await renderDashboard(refreshedAccounts[0], { flashMessage: describeCompletedTransfer(transfer, toAccountId) });
        } catch (err) {
            if (reportsASettledTransfer(err)) {
                // That key is spent: its transfer ended unsuccessfully, and replaying it would
                // only repeat the same failure. A retry (say, after topping up) is a new transfer.
                startNewTransfer();
            }
            // Anything else -- no response at all, TRANSFER_IN_PROGRESS, a 500 that names no
            // outcome -- leaves the first attempt's fate unknown, so the key is kept.
            errorEl.textContent = err.friendlyMessage ? err.friendlyMessage() : err.message;
            errorEl.hidden = false;
            submitButton.disabled = false;
            submitButton.textContent = 'Send';
        }
    });

    // "Sent 5.00 PLN to Ada Lovelace, who received 1.14 EUR." Falls back to "the recipient" when
    // the quote's lookup never ran or was for a different ID.
    function describeCompletedTransfer(transfer, toAccountId) {
        const name = knownRecipient && knownRecipient.id === toAccountId ? knownRecipient.ownerName : 'the recipient';
        const sent = `${Number(transfer.amount).toFixed(2)} ${transfer.sourceCurrency || account.currency}`;
        const converted = transfer.destinationCurrency && transfer.destinationCurrency !== transfer.sourceCurrency;
        return converted
            ? `Sent ${sent} to ${name}, who received ${Number(transfer.creditAmount).toFixed(2)} ${transfer.destinationCurrency}.`
            : `Sent ${sent} to ${name}.`;
    }
}

// Transfer Service stamps transferStatus on the problem only when it knows the transfer's
// state; PENDING means it is not settled yet. IDEMPOTENCY_KEY_CONFLICT means this key is
// already spent on a different transfer.
function reportsASettledTransfer(err) {
    if (!(err instanceof ApiError)) {
        return false;
    }
    const status = err.problem.transferStatus;
    return err.code === 'IDEMPOTENCY_KEY_CONFLICT' || (Boolean(status) && status !== 'PENDING');
}

async function renderQuickTransfers(transfers) {
    const section = document.getElementById('quick-transfers-section');
    const recentFirst = [...transfers].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    // Anyone the caller has paid or been paid by, most recent first -- paying someone back is
    // as common as paying them again.
    const seen = new Set();
    const distinctRecipients = [];
    for (const transfer of recentFirst) {
        const id = counterpartyId(transfer);
        if (!seen.has(id)) {
            seen.add(id);
            distinctRecipients.push(id);
        }
    }

    if (distinctRecipients.length === 0) {
        section.innerHTML = '';
        return;
    }

    // Only ever offer a quick-transfer to an account that still exists. GET /accounts/{id}/summary
    // has no ownership restriction (any authenticated caller can look up any id), so a failed
    // lookup here reliably means the account itself is gone -- e.g. a past transfer attempt to a
    // mistyped or otherwise nonexistent id -- not a permissions issue. Those are excluded rather
    // than shown with a fallback label, since a quick-transfer button that just fails again isn't
    // useful.
    const summaryResults = await Promise.all(
        distinctRecipients.map((id) => Api.get(`/accounts/${id}/summary`)
            .then((summary) => ({ id, summary }))
            .catch(() => null)));
    const existingRecipients = summaryResults.filter((result) => result !== null);

    if (existingRecipients.length === 0) {
        section.innerHTML = '';
        return;
    }

    section.innerHTML = `
        <div class="card">
            <div class="card-header"><h3>Send again</h3></div>
            <ul id="quick-transfers-list" class="chips"></ul>
        </div>`;
    const list = document.getElementById('quick-transfers-list');
    existingRecipients.forEach(({ id, summary }) => {
        const li = document.createElement('li');
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'chip';
        button.innerHTML = `<span class="avatar" aria-hidden="true">${escapeHtml(initials(summary.ownerName))}</span>`
            + `${escapeHtml(summary.ownerName)}<span class="muted">${escapeHtml(summary.currency || '')}</span>`;
        button.addEventListener('click', async () => {
            const accounts = await Api.get('/accounts/mine');
            renderTransferForm(accounts[0], transfers, id);
        });
        li.appendChild(button);
        list.appendChild(li);
    });
}

async function renderHistory(transfers) {
    const section = document.getElementById('history-section');
    const counterparties = [...new Set(transfers.map(counterpartyId))];
    const summaries = await Promise.all(
        counterparties.map((id) => Api.get(`/accounts/${id}/summary`).catch(() => ({ ownerName: id }))));
    const nameByAccountId = Object.fromEntries(
        counterparties.map((id, index) => [id, summaries[index].ownerName]));

    if (transfers.length === 0) {
        section.innerHTML = `
            <div class="card">
                <div class="card-header"><h3>Transfer history</h3></div>
                <div class="empty-state">
                    <strong>No transfers yet</strong>
                    Money you send or receive will show up here.
                </div>
            </div>`;
        return;
    }

    const dateFormat = { dateStyle: 'medium', timeStyle: 'short' };
    const rows = [...transfers]
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
        .map((t) => {
            const name = nameByAccountId[counterpartyId(t)];
            const incoming = isIncoming(t);
            return `<tr>
                <td class="date muted">${new Date(t.createdAt).toLocaleString(undefined, dateFormat)}</td>
                <td>
                    <span class="recipient">
                        ${escapeHtml(name)}
                        <span class="direction-label">${incoming ? 'Received' : 'Sent'}</span>
                    </span>
                </td>
                <td class="num">${formatTransferAmount(t)}</td>
                <td>${statusBadge(t.status, incoming)}</td>
            </tr>`;
        })
        .join('');
    section.innerHTML = `
        <div class="card">
            <div class="card-header"><h3>Transfer history</h3></div>
            <table class="history-table">
                <thead><tr><th>Date</th><th>Counterparty</th><th class="num">Amount</th><th>Status</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
}

bootstrap().catch((err) => {
    console.error('Bootstrap failed', err);
    const loading = document.getElementById('loading');
    loading.hidden = false;
    loading.textContent = 'Something went wrong. Please refresh.';
});
