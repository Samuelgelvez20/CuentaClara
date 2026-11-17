/**
 * CuentaClara Theme Controller
 * Manages Dynamic Color Palettes (Vino Tinto default, Esmeralda, Zafiro, Amatista, Grafito)
 * and Light / Dark Mode persistence.
 */
(function () {
    const THEME_STORAGE_KEY = 'cuentaclara_theme';
    const MODE_STORAGE_KEY = 'cuentaclara_mode';

    function getPreferredMode() {
        const stored = localStorage.getItem(MODE_STORAGE_KEY);
        if (stored === 'dark' || stored === 'light') {
            return stored;
        }
        return 'light';
    }

    function getPreferredTheme() {
        return localStorage.getItem(THEME_STORAGE_KEY) || 'vinotinto';
    }

    function applyTheme(themeName) {
        const root = document.documentElement;
        if (!themeName || themeName === 'vinotinto') {
            root.removeAttribute('data-theme');
        } else {
            root.setAttribute('data-theme', themeName);
        }
        localStorage.setItem(THEME_STORAGE_KEY, themeName || 'vinotinto');
        window.dispatchEvent(new CustomEvent('cuentaclara:theme-changed', { detail: { theme: themeName || 'vinotinto' } }));
    }

    function applyMode(mode) {
        const root = document.documentElement;
        const resolved = mode === 'dark' ? 'dark' : 'light';
        if (resolved === 'dark') {
            root.classList.add('dark');
        } else {
            root.classList.remove('dark');
        }
        localStorage.setItem(MODE_STORAGE_KEY, resolved);
        window.dispatchEvent(new CustomEvent('cuentaclara:mode-changed', { detail: { mode: resolved } }));
    }

    // Apply immediately to avoid flash of unstyled content
    applyTheme(getPreferredTheme());
    applyMode(getPreferredMode());

    // Public API on window
    window.CuentaClaraTheme = {
        getTheme: getPreferredTheme,
        getMode: getPreferredMode,
        setTheme: applyTheme,
        setMode: applyMode,
        toggleDarkMode: function () {
            const nextMode = document.documentElement.classList.contains('dark') ? 'light' : 'dark';
            applyMode(nextMode);
            return nextMode;
        }
    };
})();
