// Light/dark theme. 'auto' follows the OS/browser setting (prefers-color-scheme); 'light' and
// 'dark' override it. The resolved theme is set as <html data-theme>, which styles.css keys its
// dark palette on. Loaded synchronously in <head> so the page never paints in the wrong theme.
const Theme = (() => {
    const STORAGE_KEY = 'bank-ui-theme';
    const MODES = ['auto', 'light', 'dark'];
    const LABELS = { auto: '◐ Auto', light: '☀ Light', dark: '☾ Dark' };
    const systemDark = window.matchMedia('(prefers-color-scheme: dark)');

    let mode = 'auto';
    try {
        const saved = localStorage.getItem(STORAGE_KEY);
        if (MODES.includes(saved)) {
            mode = saved;
        }
    } catch {
        // Storage blocked (private window, disabled site data): just start on auto.
    }

    function apply() {
        const resolved = mode === 'auto' ? (systemDark.matches ? 'dark' : 'light') : mode;
        document.documentElement.dataset.theme = resolved;
        const button = document.getElementById('theme-button');
        if (button) {
            button.textContent = LABELS[mode];
            button.title = mode === 'auto'
                ? 'Theme: follows your system setting. Click to change.'
                : `Theme: ${mode}. Click to change.`;
        }
    }

    function cycle() {
        mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
        try {
            localStorage.setItem(STORAGE_KEY, mode);
        } catch {
            // Not remembered across reloads, but still applied now.
        }
        apply();
    }

    systemDark.addEventListener('change', apply);
    apply();
    document.addEventListener('DOMContentLoaded', () => {
        document.getElementById('theme-button').addEventListener('click', cycle);
        apply();
    });

    return { cycle };
})();
