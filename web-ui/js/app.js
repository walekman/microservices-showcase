async function bootstrap() {
    const justLoggedIn = await Auth.handleRedirectCallback();
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
            <p>You'll start with a balance of $1000.00.</p>
            <label for="owner-name">Your name</label>
            <input id="owner-name" type="text" />
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
            const account = await Api.post('/accounts', { ownerName, initialBalance: '1000.00' });
            renderDashboard(account);
        } catch (err) {
            errorEl.textContent = err.friendlyMessage ? err.friendlyMessage() : err.message;
            errorEl.hidden = false;
        }
    });
}

function renderDashboard(account) {
    const main = document.getElementById('main-content');
    main.innerHTML = `
        <div class="card">
            <h2>${account.ownerName}</h2>
            <p class="balance">$${Number(account.balance).toFixed(2)}</p>
            <button id="send-money-button" type="button">Send money</button>
        </div>
        <div id="transfer-section"></div>
        <div id="history-section"></div>`;
    // Task 9 wires send-money-button and fills transfer-section/history-section.
}

bootstrap().catch((err) => {
    console.error('Bootstrap failed', err);
    document.getElementById('loading').textContent = 'Something went wrong. Please refresh.';
});
