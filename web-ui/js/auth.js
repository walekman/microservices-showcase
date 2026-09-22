const KEYCLOAK_BASE = 'http://localhost:8180';
const REALM = 'showcase';
const CLIENT_ID = 'showcase-ui';
const REDIRECT_URI = window.location.origin + '/';

const AUTHORIZE_URL = `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/auth`;
const TOKEN_URL = `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/token`;
const END_SESSION_URL = `${KEYCLOAK_BASE}/realms/${REALM}/protocol/openid-connect/logout`;

const TOKEN_KEY = 'bank_ui_access_token';
const ID_TOKEN_KEY = 'bank_ui_id_token';
const VERIFIER_KEY = 'bank_ui_pkce_verifier';

function base64UrlEncode(bytes) {
    let binary = '';
    bytes.forEach((b) => { binary += String.fromCharCode(b); });
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomString() {
    const bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    return base64UrlEncode(bytes);
}

async function sha256(input) {
    const data = new TextEncoder().encode(input);
    const digest = await crypto.subtle.digest('SHA-256', data);
    return base64UrlEncode(new Uint8Array(digest));
}

const Auth = {
    isAuthenticated() {
        return sessionStorage.getItem(TOKEN_KEY) !== null;
    },

    getToken() {
        return sessionStorage.getItem(TOKEN_KEY);
    },

    async login() {
        const verifier = randomString();
        sessionStorage.setItem(VERIFIER_KEY, verifier);
        const challenge = await sha256(verifier);

        const params = new URLSearchParams({
            client_id: CLIENT_ID,
            response_type: 'code',
            scope: 'openid',
            redirect_uri: REDIRECT_URI,
            code_challenge: challenge,
            code_challenge_method: 'S256',
        });
        window.location.href = `${AUTHORIZE_URL}?${params.toString()}`;
    },

    // Call once on page load. Returns true if a login redirect was just completed (and
    // strips the ?code=... from the URL bar), false if there was nothing to handle.
    async handleRedirectCallback() {
        const params = new URLSearchParams(window.location.search);
        const code = params.get('code');
        if (!code) {
            return false;
        }
        const verifier = sessionStorage.getItem(VERIFIER_KEY);
        const body = new URLSearchParams({
            grant_type: 'authorization_code',
            client_id: CLIENT_ID,
            redirect_uri: REDIRECT_URI,
            code,
            code_verifier: verifier,
        });
        const response = await fetch(TOKEN_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body.toString(),
        });
        if (!response.ok) {
            throw new Error('Token exchange failed: ' + response.status);
        }
        const tokens = await response.json();
        sessionStorage.setItem(TOKEN_KEY, tokens.access_token);
        if (tokens.id_token) {
            sessionStorage.setItem(ID_TOKEN_KEY, tokens.id_token);
        }
        sessionStorage.removeItem(VERIFIER_KEY);
        window.history.replaceState({}, document.title, window.location.pathname);
        return true;
    },

    // Called on any 401 from the Gateway, and by the logout button.
    clearSession() {
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(ID_TOKEN_KEY);
    },

    logout() {
        const idToken = sessionStorage.getItem(ID_TOKEN_KEY);
        this.clearSession();
        const params = new URLSearchParams({
            post_logout_redirect_uri: REDIRECT_URI,
        });
        if (idToken) {
            params.set('id_token_hint', idToken);
        }
        window.location.href = `${END_SESSION_URL}?${params.toString()}`;
    },
};
