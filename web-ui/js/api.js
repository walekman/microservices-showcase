const GATEWAY_BASE = 'http://localhost:8080';

const ERROR_MESSAGES = {
    ACCOUNT_NOT_FOUND: 'That account could not be found.',
    ACCOUNT_ALREADY_EXISTS: 'You already have an account.',
    INSUFFICIENT_FUNDS: 'Insufficient funds for this transfer.',
    CONCURRENT_MODIFICATION: 'That account was just updated elsewhere — please try again.',
    SAME_ACCOUNT_TRANSFER: 'You cannot transfer to your own account.',
    SOURCE_ACCOUNT_BLOCKED: 'Your account is currently blocked from sending money.',
    DESTINATION_ACCOUNT_BLOCKED: 'The destination account is currently blocked.',
    ACCOUNT_SERVICE_UNAVAILABLE: 'The banking system is temporarily unavailable. Please try again shortly.',
    SOURCE_FRAUD_SERVICE_UNAVAILABLE: 'The banking system is temporarily unavailable. Please try again shortly.',
    DESTINATION_FRAUD_SERVICE_UNAVAILABLE: 'The banking system is temporarily unavailable. Please try again shortly.',
    COMPENSATION_REQUIRED: 'The transfer could not be completed and is being reversed automatically.',
    VALIDATION_FAILED: 'Please check the values you entered.',
};

class ApiError extends Error {
    constructor(problem) {
        super(problem.detail || 'Request failed');
        this.code = problem.code;
        this.problem = problem;
    }

    friendlyMessage() {
        return ERROR_MESSAGES[this.code] || 'Something went wrong. Please try again.';
    }
}

async function request(method, path, body) {
    const response = await fetch(GATEWAY_BASE + path, {
        method,
        headers: {
            'Content-Type': 'application/json',
            Authorization: 'Bearer ' + Auth.getToken(),
        },
        body: body ? JSON.stringify(body) : undefined,
    });

    if (response.status === 401) {
        Auth.clearSession();
        await Auth.login();
        throw new Error('Session expired, redirecting to login');
    }

    if (!response.ok) {
        const problem = await response.json().catch(() => ({ code: 'UNEXPECTED_ERROR', detail: response.statusText }));
        throw new ApiError(problem);
    }

    if (response.status === 204) {
        return null;
    }
    return response.json();
}

const Api = {
    get: (path) => request('GET', path),
    post: (path, body) => request('POST', path, body),
};
