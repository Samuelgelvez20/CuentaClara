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
        return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function getPreferredTheme() {
        return localStorage.getItem(THEME_STORAGE_KEY) || 'vinotinto';
    }

    function applyTheme(themeName) {
        const root = document.documentElement;
        if (themeName === 'vinotinto') {
            root.removeAttribute('data-theme');
        } else {
            root.setAttribute('data-theme', themeName);
        }
        localStorage.setItem(THEME_STORAGE_KEY, themeName);
        window.dispatchEvent(new CustomEvent('cuentaclara:theme-changed', { detail: { theme: themeName } }));
    }

    function applyMode(mode) {
        const root = document.documentElement;
        if (mode === 'dark') {
            root.classList.add('dark');
        } else {
            root.classList.remove('dark');
        }
        localStorage.setItem(MODE_STORAGE_KEY, mode);
        window.dispatchEvent(new CustomEvent('cuentaclara:mode-changed', { detail: { mode: mode } }));
    }

    // Apply immediately to avoid flash of unstyled content
    const currentTheme = getPreferredTheme();
    const currentMode = getPreferredMode();
    applyTheme(currentTheme);
    applyMode(currentMode);

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
