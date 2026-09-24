function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// "100.00 PLN → 22.82 EUR" for a converted transfer; "40.00 EUR" otherwise. A transfer from before
// currencies existed has no currency fields at all, and shows the bare amount.
function formatTransferAmount(t) {
    const sent = `${Number(t.amount).toFixed(2)} ${escapeHtml(t.sourceCurrency || '')}`.trim();
    if (t.creditAmount == null || t.destinationCurrency === t.sourceCurrency) {
        return sent;
    }
    return `${sent} → ${Number(t.creditAmount).toFixed(2)} ${escapeHtml(t.destinationCurrency)}`;
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
    document.getElementById('app').hidden = false;
    document.getElementById('loading').hidden = true;

    const accounts = await Api.get('/accounts/mine');
    if (accounts.length === 0) {
        renderOnboarding();
    } else {
        await renderDashboard(accounts[0]);
    }
}

function renderOnboarding() {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card">
            <h2>Welcome! Let's set up your account.</h2>
            <p>You'll start with a balance of 1000.00 in the currency you choose.</p>
            <label for="owner-name">Your name</label>
            <input id="owner-name" type="text" />
            <label for="currency">Currency</label>
            <select id="currency">
                <option value="EUR">EUR</option>
                <option value="USD">USD</option>
                <option value="GBP">GBP</option>
                <option value="PLN">PLN</option>
            </select>
            <button id="create-account-button" type="button">Create my account</button>
            <p id="onboarding-error" class="error" hidden></p>
        </div>`;

    document.getElementById('create-account-button').addEventListener('click', async () => {
        const ownerName = document.getElementById('owner-name').value.trim();
        const errorEl = document.getElementById('onboarding-error');
        errorEl.hidden = true;
        if (!ownerName) {
            errorEl.textContent = 'Please enter your name.';
            errorEl.hidden = false;
            return;
        }
        try {
            const account = await Api.post('/accounts', { ownerName, initialBalance: '1000.00', currency: document.getElementById('currency').value });
            renderDashboard(account);
        } catch (err) {
            errorEl.textContent = err.friendlyMessage ? err.friendlyMessage() : err.message;
            errorEl.hidden = false;
        }
    });
}

async function renderDashboard(account, { flashMessage } = {}) {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card">
            <h2>${escapeHtml(account.ownerName)}</h2>
            <p class="balance">${Number(account.balance).toFixed(2)} ${escapeHtml(account.currency)}</p>
            <button id="send-money-button" type="button">Send money</button>
        </div>
        <p id="dashboard-flash" class="success" hidden></p>
        <div id="transfer-section"></div>
        <div id="quick-transfers-section"></div>
        <div id="history-section"></div>`;

    if (flashMessage) {
        const flashEl = document.getElementById('dashboard-flash');
        flashEl.textContent = flashMessage;
        flashEl.hidden = false;
    }

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
            <h3>Send money</h3>
            <label for="to-account">To account ID</label>
            <input id="to-account" type="text" value="${prefillAccountId || ''}" />
            <label for="amount">Amount</label>
            <input id="amount" type="number" step="0.01" min="0.01" />
            <p id="transfer-quote" class="hint" hidden></p>
            <button id="submit-transfer-button" type="button">Send</button>
            <p id="transfer-error" class="error" hidden></p>
            <p id="transfer-success" hidden></p>
        </div>`;

    // One Idempotency-Key per intended transfer. Resending the same transfer (a double-click,
    // a retry after a lost response) reuses it, so Transfer Service replays the first attempt
    // instead of moving the money twice. Editing either field makes it a different transfer.
    let idempotencyKey = crypto.randomUUID();
    const startNewTransfer = () => { idempotencyKey = crypto.randomUUID(); };

    // An approximate quote while the user types. Advisory only: Transfer Service prices the
    // transfer itself when it is sent, and the result shows the amount it actually locked.
    const quoteEl = document.getElementById('transfer-quote');
    let latestQuote = 0;
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
            if (requestId !== latestQuote || recipient.currency === account.currency) {
                return;
            }
            const fx = await Api.get(`/fx/rates?base=${account.currency}&quote=${recipient.currency}`);
            if (requestId !== latestQuote) {
                return; // a newer edit superseded this quote
            }
            quoteEl.textContent = `${recipient.ownerName} receives ≈ ${(amount * fx.rate).toFixed(2)} ${recipient.currency}`
                + ` (1 ${account.currency} = ${fx.rate} ${recipient.currency}, as of ${fx.asOf}`
                + `${fx.stale ? ' — last known rate' : ''}). The exact amount is fixed when you send.`;
            quoteEl.hidden = false;
        } catch (err) {
            // No quote is not a form error: sending still works, and the server prices it.
        }
    };
    document.getElementById('to-account').addEventListener('input', () => { startNewTransfer(); refreshQuote(); });
    document.getElementById('amount').addEventListener('input', () => { startNewTransfer(); refreshQuote(); });

    const submitButton = document.getElementById('submit-transfer-button');
    submitButton.addEventListener('click', async () => {
        const toAccountId = document.getElementById('to-account').value.trim();
        const amount = document.getElementById('amount').value;
        const errorEl = document.getElementById('transfer-error');
        const successEl = document.getElementById('transfer-success');
        errorEl.hidden = true;
        successEl.hidden = true;
        submitButton.disabled = true;
        try {
            const transfer = await Api.post('/transfers', { fromAccountId: account.id, toAccountId, amount },
                { 'Idempotency-Key': idempotencyKey });
            const refreshedAccounts = await Api.get('/accounts/mine');
            const converted = transfer.destinationCurrency && transfer.destinationCurrency !== transfer.sourceCurrency;
            await renderDashboard(refreshedAccounts[0], {
                flashMessage: converted
                    ? `Transfer completed: the recipient received ${Number(transfer.creditAmount).toFixed(2)} ${transfer.destinationCurrency}.`
                    : 'Transfer completed.',
            });
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
        } finally {
            submitButton.disabled = false;
        }
    });
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
    const seen = new Set();
    const distinctRecipients = [];
    for (const transfer of recentFirst) {
        if (!seen.has(transfer.toAccountId)) {
            seen.add(transfer.toAccountId);
            distinctRecipients.push(transfer.toAccountId);
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
            <h3>Quick transfers</h3>
            <ul id="quick-transfers-list"></ul>
        </div>`;
    const list = document.getElementById('quick-transfers-list');
    existingRecipients.forEach(({ id, summary }) => {
        const li = document.createElement('li');
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = summary.ownerName;
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
    const distinctRecipients = [...new Set(transfers.map((t) => t.toAccountId))];
    const summaries = await Promise.all(
        distinctRecipients.map((id) => Api.get(`/accounts/${id}/summary`).catch(() => ({ ownerName: id }))));
    const nameByAccountId = Object.fromEntries(
        distinctRecipients.map((id, index) => [id, summaries[index].ownerName]));

    const rows = [...transfers]
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
        .map((t) => `<tr><td>${new Date(t.createdAt).toLocaleString()}</td><td>${escapeHtml(nameByAccountId[t.toAccountId])}</td>
            <td>${formatTransferAmount(t)}</td><td>${t.status}</td></tr>`)
        .join('');
    section.innerHTML = `
        <div class="card">
            <h3>Transfer history</h3>
            <table>
                <thead><tr><th>Date</th><th>To</th><th>Amount</th><th>Status</th></tr></thead>
                <tbody>${rows || '<tr><td colspan="4">No transfers yet.</td></tr>'}</tbody>
            </table>
        </div>`;
}

bootstrap().catch((err) => {
    console.error('Bootstrap failed', err);
    document.getElementById('loading').textContent = 'Something went wrong. Please refresh.';
});
