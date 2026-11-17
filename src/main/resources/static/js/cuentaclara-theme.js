/**
 * CuentaClara Theme & Language Controller
 * Manages Dynamic Color Palettes (Vino Tinto default, Esmeralda, Zafiro, Amatista, Grafito),
 * Light / Dark Mode persistence, and System Language (Español es-CO / English en-US).
 */
(function () {
    const THEME_STORAGE_KEY = 'cuentaclara_theme';
    const MODE_STORAGE_KEY = 'cuentaclara_mode';
    const LANG_STORAGE_KEY = 'cuentaclara_lang';
    const CURRENCY_STORAGE_KEY = 'cuentaclara_currency';

    // Diccionario bilingüe Español <-> English para toda la interfaz del sistema
    const EN_TRANSLATIONS = {
        "Organiza tu Dinero Día a Día": "Organize Your Money Day by Day",
        "Mi Espacio Personal": "My Personal Space",
        "Mi Calendario del Mes": "My Monthly Calendar",
        "Mi Calendario y Gastos": "My Calendar & Expenses",
        "Historial de Gastos": "Expense History",
        "Subir Recibo o Factura": "Upload Receipt or Invoice",
        "Mi Plan y Deudas Fijas": "My Plan & Fixed Debts",
        "Pregúntale a tu Asistente": "Ask Your Assistant",
        "Colores y Configuración": "Colors & Settings",
        "Configuración": "Settings",
        "Claro / Oscuro": "Light / Dark",
        "Modo Visual": "Visual Mode",
        "Modo Claro": "Light Mode",
        "Modo Oscuro": "Dark Mode",
        "Colores y Ajustes": "Colors & Settings",
        "+ Deuda o Pago Fijo": "+ Debt or Fixed Payment",
        "Tu Ingreso del Mes": "Your Monthly Income",
        "Gastado en este Mes": "Spent This Month",
        "Pagos Fijos y Deudas": "Fixed Payments & Debts",
        "Tu Meta de Ahorro": "Your Savings Goal",
        "Mes Anterior": "Previous Month",
        "Mes Siguiente": "Next Month",
        "Interactivo": "Interactive",
        "Lun": "Mon",
        "Mar": "Tue",
        "Mié": "Wed",
        "Jue": "Thu",
        "Vie": "Fri",
        "Sáb": "Sat",
        "Dom": "Sun",
        "Sin gastos": "No expenses",
        "Total día:": "Day total:",
        "Gastos y Pagos del Día": "Daily Expenses & Payments",
        "🛍️ Gastos Anotados en este Día": "🛍️ Expenses Recorded on This Day",
        "✨ Anotar un Nuevo Gasto en este Día": "✨ Record a New Expense on This Day",
        "¿En qué gastaste o dónde?": "What did you spend on or where?",
        "Valor del Gasto ($ COP)": "Expense Amount ($)",
        "Categoría": "Category",
        "Tipo de Gasto": "Expense Type",
        "Descripción de estos gastos": "Description of these expenses",
        "Guardar Gasto en este Día": "Save Expense on This Day",
        "📌 Pagos Fijos y Deudas de Cada Mes": "📌 Monthly Fixed Payments & Debts",
        "+ Programar Nuevo Pago o Deuda": "+ Schedule New Payment or Debt",
        "Ir a Historial de Gastos del Mes →": "Go to Monthly Expense History →",
        "Tu Asistente Personal": "Your Personal Assistant",
        "Respuestas claras y amables sobre tus gastos": "Clear and friendly answers about your expenses",
        "Abrir chat →": "Open chat →",
        "Centro de Configuración & Ajustes": "Settings & Customization Center",
        "Personaliza la estética, paleta de colores, modo visual, perfil y parámetros de la aplicación.": "Customize the look, color palette, visual mode, language, and financial parameters.",
        "Volver al Panel": "Back to Calendar",
        "Paleta de Colores & Apariencia": "Color Palette & Appearance",
        "Elige la identidad visual y estilo de contraste que mejor se adapte a ti.": "Choose the visual identity and contrast style that suits you best.",
        "Cambio en Tiempo Real": "Real-Time Preview",
        "Selecciona tu Paleta Preferida": "Select Your Preferred Palette",
        "Modo Claro / Modo Oscuro": "Light Mode / Dark Mode",
        "Claro": "Light",
        "Oscuro": "Dark",
        "Idioma & Preferencias Regionales": "Language & Regional Preferences",
        "Configura la moneda de visualización, formatos de número y localización.": "Configure display language, currency, and assistant tone.",
        "Idioma de la Interfaz": "System Interface Language",
        "Moneda Principal": "Primary Currency",
        "Tono del Asistente RAG": "Assistant Tone",
        "Confirmar y Aplicar Idioma al Sistema": "Confirm & Apply System Language",
        "Parámetros del Perfil Financiero & Presupuesto": "Financial Profile & Budget Parameters",
        "Guardar Mis Preferencias": "Save My Preferences",
        "Pagos Fijos y Deudas Mensuales en tu Calendario": "Monthly Fixed Payments & Debts on Your Calendar",
        "Agendar cada Mes": "Schedule Monthly",
        "Detalle Completo del Mes": "Full Monthly Breakdown",
        "Ir al Calendario del Mes": "Go to Monthly Calendar",
        "Mes a consultar": "Month to view",
        "Todas las categorías": "All categories",
        "Lugar o Concepto": "Place or Concept",
        "Todos los lugares": "All places",
        "Filtrar": "Filter",
        "Iniciar Sesión": "Sign In",
        "Crear Cuenta": "Create Account",
        "Continuar con Google": "Continue with Google"
    };

    const ES_REVERSE = {};
    Object.keys(EN_TRANSLATIONS).forEach(esKey => {
        ES_REVERSE[EN_TRANSLATIONS[esKey]] = esKey;
    });

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

    function getPreferredLanguage() {
        return localStorage.getItem(LANG_STORAGE_KEY) || 'es-CO';
    }

    function getPreferredCurrency() {
        return localStorage.getItem(CURRENCY_STORAGE_KEY) || 'COP';
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

    function translateDOM(lang) {
        document.documentElement.setAttribute('lang', lang === 'en-US' ? 'en' : 'es');
        const dict = lang === 'en-US' ? EN_TRANSLATIONS : ES_REVERSE;

        const walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null);
        let node;
        while ((node = walker.nextNode())) {
            const raw = node.nodeValue;
            if (!raw) continue;
            const trimmed = raw.trim();
            if (trimmed && dict[trimmed]) {
                node.nodeValue = raw.replace(trimmed, dict[trimmed]);
            }
        }
    }

    function applyLanguage(lang) {
        const resolved = lang === 'en-US' ? 'en-US' : 'es-CO';
        localStorage.setItem(LANG_STORAGE_KEY, resolved);
        if (document.body) {
            translateDOM(resolved);
        }
        window.dispatchEvent(new CustomEvent('cuentaclara:language-changed', { detail: { language: resolved } }));
        return resolved;
    }

    function applyCurrency(currency) {
        const resolved = currency || 'COP';
        localStorage.setItem(CURRENCY_STORAGE_KEY, resolved);
        return resolved;
    }

    // Apply theme & mode immediately before render
    applyTheme(getPreferredTheme());
    applyMode(getPreferredMode());

    document.addEventListener('DOMContentLoaded', () => {
        const savedLang = getPreferredLanguage();
        if (savedLang === 'en-US') {
            translateDOM('en-US');
        }
    });

    // Public API on window
    window.CuentaClaraTheme = {
        getTheme: getPreferredTheme,
        getMode: getPreferredMode,
        getLanguage: getPreferredLanguage,
        getCurrency: getPreferredCurrency,
        setTheme: applyTheme,
        setMode: applyMode,
        setLanguage: applyLanguage,
        setCurrency: applyCurrency,
        toggleDarkMode: function () {
            const nextMode = document.documentElement.classList.contains('dark') ? 'light' : 'dark';
            applyMode(nextMode);
            return nextMode;
        }
    };
})();
