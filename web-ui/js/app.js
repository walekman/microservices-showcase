async function bootstrap() {
    const justLoggedIn = await Auth.handleRedirectCallback();
    if (!Auth.isAuthenticated()) {
        await Auth.login();
        return;
    }
    document.getElementById('logout-button').addEventListener('click', () => Auth.logout());
    document.getElementById('app').hidden = false;
    document.getElementById('loading').hidden = true;
    // Task 8 replaces this line with the real onboarding/dashboard check.
    console.log('Authenticated. justLoggedIn =', justLoggedIn);
}

bootstrap().catch((err) => {
    console.error('Bootstrap failed', err);
    document.getElementById('loading').textContent = 'Something went wrong. Please refresh.';
});
