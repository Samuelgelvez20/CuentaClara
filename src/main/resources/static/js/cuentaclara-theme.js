/**
 * CuentaClara Theme, Full-Page i18n (es-CO / en-US) & Live Currency Converter (COP / USD / EUR)
 */
(function () {
    const THEME_STORAGE_KEY = 'cuentaclara_theme';
    const MODE_STORAGE_KEY = 'cuentaclara_mode';
    const LANG_STORAGE_KEY = 'cuentaclara_lang';
    const CURRENCY_STORAGE_KEY = 'cuentaclara_currency';

    // Tasas de conversión de referencia frente a COP
    const EXCHANGE_RATES = {
        COP: { rate: 1, symbol: '$', code: 'COP', decimals: 0, locale: 'es-CO' },
        USD: { rate: 4000, symbol: 'US$', code: 'USD', decimals: 2, locale: 'en-US' },
        EUR: { rate: 4350, symbol: '€', code: 'EUR', decimals: 2, locale: 'de-DE' }
    };

    // Diccionario completo de frases exactas Español -> Inglés
    const EXACT_EN = {
        // Sidebar & Navegación unificada
        "Finanzas personales": "Personal finance",
        "Menú": "Menu",
        "Calendario": "Calendar",
        "Historial de gastos": "Expense history",
        "Facturas y recibos": "Invoices & receipts",
        "Presupuesto y deudas": "Budget & fixed payments",
        "Consultas": "Queries",
        "Configuración": "Settings",
        "Tema": "Theme",
        "Ajustes": "Settings",
        "Pago fijo": "Fixed payment",
        "Inicio": "Home",

        // Login (/auth/login)
        "Lleva el control de tus gastos y pagos del mes.": "Keep track of your monthly expenses and payments.",
        "Registra tus gastos diarios en el calendario, programa tus pagos fijos mensuales y distribuye tu ingreso de forma clara.": "Log daily expenses on the calendar, schedule monthly fixed payments, and organize your income clearly.",
        "Calendario mensual para registrar gastos por día": "Monthly calendar to log expenses by day",
        "Recordatorio mensual de arriendo, servicios y cuotas": "Monthly reminders for rent, utilities, and installments",
        "Presupuesto 50/30/20 adaptable a tus ingresos": "50/30/20 budget adaptable to your income",
        "Iniciar sesión": "Sign in",
        "Crear cuenta": "Create account",
        "Nombre completo": "Full name",
        "Correo electrónico": "Email address",
        "Contraseña": "Password",
        "Pagos fijos mensuales (opcional)": "Monthly fixed payments (optional)",
        "Indica tu ingreso y los pagos que realizas el mismo día de cada mes para agregarlos al calendario.": "Enter your income and payments due on the same day each month to add them to the calendar.",
        "Ingreso mensual ($ COP)": "Monthly income ($ COP)",
        "+ Agregar otro pago fijo": "+ Add another fixed payment",
        "Acepto la política de tratamiento de datos (Ley 1581 de 2012).": "I accept the data privacy policy (Law 1581 of 2012).",
        "Continuar con Google": "Continue with Google",
        "Probar con cuenta demo": "Try demo account",

        // Dashboard & Calendario (/dashboard)
        "Resumen mensual y registro diario de gastos": "Monthly overview and daily expense log",
        "Cargar datos de ejemplo": "Load sample data",
        "Subir factura o recibo": "Upload invoice or receipt",
        "Ingreso mensual": "Monthly income",
        "Editar": "Edit",
        "Gastado este mes": "Spent this month",
        "Ver detalle": "View details",
        "Pagos fijos del mes": "Monthly fixed payments",
        "Recordatorio mensual": "Monthly reminder",
        "+ Agregar": "+ Add",
        "Meta de ahorro": "Savings goal",
        "Selecciona cualquier día para registrar o consultar tus gastos.": "Select any day to log or view your expenses.",
        "Hoy": "Today",
        "Anterior": "Previous",
        "Siguiente": "Next",
        "Día actual": "Today",
        "Pago fijo pendiente": "Pending fixed payment",
        "Pago fijo al día": "Fixed payment paid",
        "Gasto registrado": "Recorded expense",
        "Lun": "Mon",
        "Mar": "Tue",
        "Mié": "Wed",
        "Jue": "Thu",
        "Vie": "Fri",
        "Sáb": "Sat",
        "Dom": "Sun",
        "Registrar": "Add",
        "Sin gastos": "No expenses",
        "Total:": "Total:",
        "Confirmar datos del recibo": "Confirm receipt details",
        "Comercio o lugar": "Merchant or place",
        "Fecha": "Date",
        "Categoría": "Category",
        "Bloque": "Block",
        "Valor total ($ COP)": "Total amount ($ COP)",
        "Confirmar y guardar": "Confirm & save",
        "Pagos fijos mensuales": "Monthly fixed payments",
        "Se repiten en la fecha indicada de cada mes": "Repeated on the selected day each month",
        "+ Agregar pago fijo": "+ Add fixed payment",
        "No hay pagos fijos programados todavía.": "No fixed payments scheduled yet.",
        "Día": "Day",
        "Consulta y filtra todos los movimientos del mes": "Search and filter all monthly transactions",
        "Ver historial del mes": "View monthly history",
        "Distribución mensual según tu ingreso": "Monthly distribution based on your income",
        "Balance del mes": "Monthly balance",
        "Consultas rápidas": "Quick queries",
        "Pregunta sobre tus gastos registrados": "Ask about your recorded expenses",
        "Abrir →": "Open →",
        "¿Cuánto gasté en domicilios?": "How much did I spend on delivery?",
        "¿Cuánto pagué de arriendo?": "How much did I pay for rent?",

        // Modal del Día y Modal de Pago Fijo
        "Detalle del día": "Day details",
        "Pagos fijos programados": "Scheduled fixed payments",
        "Gastos del día": "Day expenses",
        "Sin gastos registrados en este día.": "No expenses recorded on this day.",
        "Registrar gasto": "Log expense",
        "Concepto o comercio": "Concept or merchant",
        "Valor ($ COP)": "Amount ($ COP)",
        "Necesidades": "Needs",
        "Deseos": "Wants",
        "Ahorro / Deuda": "Savings / Debt",
        "Notas (opcional)": "Notes (optional)",
        "Guardar gasto": "Save expense",
        "Nuevo pago fijo mensual": "New monthly fixed payment",
        "Se recordará el mismo día de cada mes en el calendario.": "Will be reminded on the same day every month on the calendar.",
        "Concepto": "Concept",
        "Monto mensual ($ COP)": "Monthly amount ($ COP)",
        "Día de pago (1-31)": "Due day (1-31)",
        "Vivienda / Alquiler": "Housing / Rent",
        "Deudas y préstamos": "Debts & loans",
        "Servicios e internet": "Utilities & internet",
        "Guardar pago fijo": "Save fixed payment",
        "Registrar pago": "Record payment",
        "Pagado": "Paid",

        // Historial (/history)
        "Movimientos registrados durante el periodo seleccionado.": "Transactions recorded during the selected period.",
        "Periodo": "Period",
        "Todas las categorías": "All categories",
        "Comercio o concepto": "Merchant or concept",
        "Todos": "All",
        "Filtrar": "Filter",
        "Limpiar": "Clear",
        "Volver al calendario": "Back to calendar",

        // Subir Factura (/upload)
        "Facturas, recibos y gastos": "Invoices, receipts & expenses",
        "Sube una factura (XML, PDF o imagen) o registra un gasto manualmente.": "Upload an invoice (XML, PDF or image) or log an expense manually.",
        "Subir archivo de factura o recibo": "Upload invoice or receipt file",
        "Formatos admitidos: XML, PDF, JPG o PNG (máx. 10 MB).": "Supported formats: XML, PDF, JPG or PNG (max. 10 MB).",
        "Es un extracto PDF con varios movimientos": "It is a multi-transaction PDF statement",
        "Procesar archivo": "Process file",
        "Registrar gasto manual": "Log manual expense",
        "Concepto o comercio *": "Concept or merchant *",
        "Valor ($ COP) *": "Amount ($ COP) *",
        "Asignar automáticamente": "Auto-assign",
        "Descripción": "Description",
        "Comprobante (opcional)": "Receipt (optional)",
        "Sin comprobantes pendientes": "No pending receipts",
        "Al subir un archivo en el panel izquierdo podrás revisar y confirmar sus datos aquí antes de guardarlo.": "When you upload a file on the left panel, you can review and confirm its details here before saving.",
        "Pendiente de revisión": "Pending review",
        "Confirmar datos del comprobante": "Confirm receipt details",
        "Comercio / Emisor": "Merchant / Issuer",
        "NIT": "Tax ID / NIT",
        "Número de factura": "Invoice number",
        "Subtotal": "Subtotal",
        "Impuestos": "Taxes",
        "Total ($ COP)": "Total ($ COP)",

        // Perfil (/profile - Presupuesto y deudas)
        "Presupuesto y pagos fijos": "Budget & fixed payments",
        "Define tu ingreso mensual, la distribución 50/30/20 y los pagos que se repiten cada mes.": "Set your monthly income, 50/30/20 distribution, and recurring monthly payments.",
        "Ingresos y deudas": "Income & debts",
        "Ingreso neto ($ COP)": "Net income ($ COP)",
        "Deudas totales (COP)": "Total debts (COP)",
        "Objetivo principal": "Main goal",
        "Frecuencia de pago": "Pay frequency",
        "Periodicidad": "Frequency",
        "Días de pago (ej. 15,30 o 30)": "Paydays (e.g. 15,30 or 30)",
        "Distribución 50 / 30 / 20": "50 / 30 / 20 Distribution",
        "Suma: 100% Correcto": "Sum: 100% Valid",
        "Ahorro": "Savings",
        "Compromisos que vencen el mismo día cada mes (1 al 31).": "Obligations due on the same day each month (1 to 31).",
        "Día (1-31)": "Day (1-31)",
        "Guardar cambios": "Save changes",
        "Gastos esenciales": "Essential expenses",
        "Gustos y ocio": "Wants & leisure",
        "Reserva mensual": "Monthly reserve",
        "Notas sobre tu presupuesto": "Budget notes",
        "Este presupuesto se calcula con tus datos actuales y se irá ajustando conforme registres los gastos del mes.": "This budget is calculated from your current data and will adjust as you log your monthly expenses.",
        "Quincenal": "Biweekly",
        "Variable": "Variable",

        // Consultas (/assistant)
        "Subir factura": "Upload invoice",
        "Resumen de movimientos": "Transaction summary",
        "Las respuestas se calculan sobre tus gastos y facturas confirmadas.": "Answers are calculated from your confirmed expenses and invoices.",
        "Últimos movimientos": "Recent transactions",
        "Sin movimientos registrados aún.": "No transactions recorded yet.",
        "Consultas sobre tus gastos": "Queries about your expenses",
        "Pregunta por comercios, categorías, fechas o totales del mes": "Ask by merchant, category, date, or monthly totals",
        "Ejemplos:": "Examples:",
        "¿Cuánto gasté en domicilios en octubre?": "How much did I spend on delivery in October?",
        "¿En qué factura compré Café Molido Gourmet?": "In which invoice did I buy Gourmet Ground Coffee?",
        "¿Cuánto gasté en Almacenes Éxito?": "How much did I spend at Almacenes Éxito?",
        "¿Cuál es mi presupuesto y distribución 50/30/20?": "What is my budget and 50/30/20 distribution?",
        "Escribe una pregunta o selecciona un ejemplo": "Type a question or select an example",
        "Puedes consultar cuánto gastaste en una categoría, buscar una compra específica o revisar tu presupuesto.": "You can check how much you spent in a category, find a specific purchase, or review your budget.",
        "Resultado": "Result",
        "Sin registros coincidentes": "No matching records",
        "Referencias consultadas": "Referenced transactions",
        "Consultar": "Ask",

        // Configuración (/settings)
        "Apariencia, idioma, moneda y parámetros del presupuesto.": "Appearance, language, currency, and budget parameters.",
        "Apariencia": "Appearance",
        "Color principal y modo de pantalla.": "Primary color and screen mode.",
        "Color del tema": "Theme color",
        "Vino Tinto": "Burgundy Wine",
        "Verde Esmeralda": "Emerald Green",
        "Azul Zafiro": "Sapphire Blue",
        "Morado Amatista": "Amethyst Purple",
        "Grafito": "Graphite",
        "Modo de pantalla": "Screen mode",
        "Alternar entre fondo claro y oscuro.": "Switch between light and dark background.",
        "Claro": "Light",
        "Oscuro": "Dark",
        "Idioma y moneda": "Language & currency",
        "Formato de textos y divisa de visualización.": "Text language and display currency.",
        "Idioma": "Language",
        "Moneda": "Currency",
        "Estilo de respuestas": "Response style",
        "Claro y directo": "Clear & direct",
        "Resumido": "Concise",
        "Detallado": "Detailed",
        "Idioma y moneda actualizados.": "Language and currency updated.",
        "Aplicar idioma y moneda": "Apply language & currency",
        "Presupuesto mensual": "Monthly budget",
        "Ingresos, frecuencia de pago y distribución 50/30/20.": "Income, pay frequency, and 50/30/20 distribution.",
        "Ingreso Mensual Neto (COP)": "Monthly Net Income (COP)",
        "Base líquida para calcular los topes 50/30/20.": "Net base used to calculate your 50/30/20 limits.",
        "Frecuencia de Ingresos": "Income Frequency",
        "Mensual": "Monthly",
        "Quincenal (Días 15 y 30)": "Biweekly (Days 15 & 30)",
        "Semanal": "Weekly",
        "Ajusta los reportes quincenales o mensuales.": "Adjusts biweekly or monthly reports.",
        "Día(s) de Pago de tu Ingreso": "Your Income Payday(s)",
        "Día del mes en que recibes tu ingreso.": "Day of the month you receive your income.",
        "Distribución de Porcentajes de Gasto (Suma = 100%)": "Expense Percentage Distribution (Sum = 100%)",
        "Necesidades Esenciales (%)": "Essential Needs (%)",
        "Alquiler, mercado, servicios, salud.": "Rent, groceries, utilities, health.",
        "Gustos y Deseos (%)": "Wants & Leisure (%)",
        "Salidas, domicilios, entretenimiento.": "Dining out, delivery, entertainment.",
        "Ahorro y Metas (%)": "Savings & Goals (%)",
        "Fondo de tranquilidad y sueños.": "Emergency fund and personal goals.",
        "Tu Meta u Objetivo Principal": "Your Main Financial Goal",
        "Guardar Mis Preferencias": "Save My Preferences",
        "Pagos Fijos y Deudas Mensuales en tu Calendario": "Monthly Fixed Payments & Debts on Your Calendar",
        "Todo pago o deuda que registres aquí se marcará automáticamente el mismo día en todos los meses de tu calendario.": "Every payment or debt you register here will be automatically marked on the same day every month on your calendar.",
        "Nombre del Pago o Deuda (ej. Alquiler, Préstamo)": "Payment or Debt Name (e.g. Rent, Loan)",
        "Monto Mensual ($)": "Monthly Amount ($)",
        "Agendar cada Mes": "Schedule Monthly",

        // Categorías estándar
        "Alimentación": "Groceries & Food",
        "Domicilios y Restaurantes": "Delivery & Restaurants",
        "Vivienda": "Housing & Rent",
        "Deudas y Préstamos": "Debts & Loans",
        "Servicios": "Utilities & Bills",
        "Transporte": "Transportation",
        "Salud": "Health & Pharmacy",
        "Entretenimiento": "Entertainment",
        "Educación": "Education",
        "Panadería y Antojos": "Bakery & Snacks",
        "Otros": "Other"
    };

    // Reemplazos parciales para cadenas dinámicas (meses, días, contadores)
    const PARTIAL_EN_RULES = [
        [/Calendario — /g, "Calendar — "],
        [/Historial de gastos — /g, "Expense history — "],
        [/Historial — /g, "History — "],
        [/Hoy: /g, "Today: "],
        [/HOY • /g, "TODAY • "],
        [/Total: /g, "Total: "],
        [/movimiento\(s\) confirmados/g, "confirmed transaction(s)"],
        [/movimientos confirmados/g, "confirmed transactions"],
        [/movimiento\(s\)/g, "transaction(s)"],
        [/programados/g, "scheduled"],
        [/ más$/g, " more"],
        [/del ingreso/g, "of income"],
        [/Regla /g, "Rule "],
        [/Presupuesto /g, "Budget "],
        [/Necesidades \(/g, "Needs ("],
        [/Deseos \(/g, "Wants ("],
        [/Ahorro \(/g, "Savings ("],
        [/Día (\d+) de cada mes/g, "Day $1 of every month"],
        [/Ref: /g, "Ref: "],
        [/Tus pagos fijos suman /g, "Your fixed payments total "],
        [/, dejando un margen de /g, ", leaving a margin of "],
        [/dentro de Necesidades/g, "within Needs"],
        [/para alimentación y transporte\./g, "for groceries and transportation."],
        [/Tus pagos fijos esenciales/g, "Your essential fixed payments"],
        [/superan en /g, "exceed by "],
        [/el monto asignado a Necesidades/g, "the amount assigned to Needs"],
        [/Puedes compensarlo reduciendo el porcentaje de Deseos/g, "You can offset this by reducing your Wants percentage"],
        [/o ajustando la distribución\./g, "or adjusting the distribution."],
        [/Flujo de caja quincenal \(días de pago /g, "Biweekly cash flow (paydays "],
        [/Entre los días 1 y 15 vencen obligaciones por /g, "Between days 1 and 15, obligations due total "],
        [/, y del día 16 a fin de mes vencen /g, ", and from day 16 to month-end they total "],
        [/Reserva liquidez según las fechas reales de vencimiento en lugar de partir la quincena en mitades iguales\./g, "Reserve liquidity according to actual due dates instead of splitting biweekly pay in equal halves."],
        [/Ingreso variable detectado: Te recomendamos operar con un escenario conservador del 85%/g, "Variable income detected: We recommend operating with a conservative 85% scenario"],
        [/para cubrir obligaciones fijas y destinar cualquier excedente al fondo de estabilidad\./g, "to cover fixed obligations and allocate any surplus to your stability fund."],
        [/Suma: (\d+)% \(Debe ser 100%\)/g, "Sum: $1% (Must be 100%)"],
        [/Lunes/g, "Monday"],
        [/Martes/g, "Tuesday"],
        [/Miércoles/g, "Wednesday"],
        [/Jueves/g, "Thursday"],
        [/Viernes/g, "Friday"],
        [/Sábado/g, "Saturday"],
        [/Domingo/g, "Sunday"],
        [/Enero/g, "January"],
        [/Febrero/g, "February"],
        [/Marzo/g, "March"],
        [/Abril/g, "April"],
        [/Mayo/g, "May"],
        [/Junio/g, "June"],
        [/Julio/g, "July"],
        [/Agosto/g, "August"],
        [/Septiembre/g, "September"],
        [/Octubre/g, "October"],
        [/Noviembre/g, "November"],
        [/Diciembre/g, "December"],
        [/ de 202/g, ", 202"],
        [/ de /g, " "]
    ];

    const PLACEHOLDER_EN = {
        "Consultar gastos del mes...": "Search monthly expenses...",
        "Escribe una consulta...": "Type a query...",
        "Escribe tu consulta sobre gastos o facturas...": "Type your query about expenses or invoices...",
        "Supermercado, almuerzo...": "Supermarket, lunch...",
        "Detalle adicional del gasto...": "Additional expense details...",
        "Detalle opcional del gasto": "Optional expense detail",
        "Arriendo, cuota préstamo, internet...": "Rent, loan installment, internet...",
        "Tarjeta, crédito, internet...": "Credit card, loan, internet...",
        "Ej. Pago de Alquiler o Cuota Tarjeta": "E.g. Rent Payment or Card Installment",
        "Mínimo 8 caracteres": "Minimum 8 characters"
    };

    const INPUT_VALUE_EN = {
        "Alquiler / Arriendo": "Rent / Housing",
        "Alquiler / Vivienda": "Rent / Housing",
        "Cuota Préstamo / Deuda": "Loan / Debt Installment",
        "Servicios e Internet": "Utilities & Internet",
        "Ahorro y control": "Savings & control",
        "Fondo de tranquilidad y salir de deudas": "Emergency fund & paying off debt",
        "Vivienda": "Housing & Rent",
        "Deudas y Préstamos": "Debts & Loans",
        "Servicios": "Utilities & Bills"
    };

    function getPreferredMode() {
        const stored = localStorage.getItem(MODE_STORAGE_KEY);
        return (stored === 'dark' || stored === 'light') ? stored : 'light';
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

    // Convierte montos en texto desde COP hacia la moneda elegida (COP, USD, EUR)
    function convertCurrencyInString(str, targetCurrency) {
        const cfg = EXCHANGE_RATES[targetCurrency] || EXCHANGE_RATES.COP;

        // Reemplazar etiquetas de unidad (COP) / ($ COP)
        let out = str;
        if (targetCurrency !== 'COP') {
            out = out.replace(/\(\$ COP\)/g, `(${cfg.symbol} ${cfg.code})`)
                     .replace(/\(COP\)/g, `(${cfg.code})`);
        }

        // Detectar montos con formato "$ 1.450.000 COP", "$1,450,000.00 COP", "$ 1.450.000" o "$1.450.000"
        return out.replace(/\$\s?([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]+)(?:[.,][0-9]{1,2})?(\s?COP)?/g, (match, numPart, copSuffix) => {
            const cleanDigits = numPart.replace(/[.,]/g, '');
            const copValue = parseFloat(cleanDigits);
            if (isNaN(copValue)) return match;

            if (targetCurrency === 'COP') {
                const formatted = copValue.toLocaleString('es-CO', { maximumFractionDigits: 0 });
                return '$ ' + formatted + (copSuffix ? ' COP' : '');
            } else {
                const converted = copValue / cfg.rate;
                const formatted = converted.toLocaleString(cfg.locale, {
                    minimumFractionDigits: cfg.decimals,
                    maximumFractionDigits: cfg.decimals
                });
                return `${cfg.symbol} ${formatted}` + (copSuffix ? ` ${cfg.code}` : '');
            }
        });
    }

    function translateSingleString(origStr, lang) {
        if (lang !== 'en-US') {
            return origStr;
        }
        const normalized = origStr.trim().replace(/\s+/g, ' ');
        if (!normalized) return origStr;

        if (EXACT_EN[normalized]) {
            return EXACT_EN[normalized];
        }

        let translated = origStr;
        for (const [regex, replacement] of PARTIAL_EN_RULES) {
            translated = translated.replace(regex, replacement);
        }
        return translated;
    }

    function refreshPageLocalization() {
        if (!document.body) return;
        const lang = getPreferredLanguage();
        const curr = getPreferredCurrency();
        document.documentElement.setAttribute('lang', lang === 'en-US' ? 'en' : 'es');

        // 1. Nodos de texto
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        let node;
        while ((node = walker.nextNode())) {
            const parentTag = node.parentElement ? node.parentElement.tagName : '';
            if (parentTag === 'SCRIPT' || parentTag === 'STYLE') continue;

            if (node._ccOrigText === undefined) {
                node._ccOrigText = node.nodeValue;
            }
            const baseText = node._ccOrigText;
            if (!baseText || !baseText.trim()) continue;

            let step1 = translateSingleString(baseText, lang);
            let step2 = convertCurrencyInString(step1, curr);
            if (node.nodeValue !== step2) {
                node.nodeValue = step2;
            }
        }

        // 2. Placeholders de inputs y textareas
        document.querySelectorAll('input[placeholder], textarea[placeholder]').forEach(el => {
            if (el._ccOrigPlaceholder === undefined) {
                el._ccOrigPlaceholder = el.getAttribute('placeholder');
            }
            const origPh = el._ccOrigPlaceholder;
            if (lang === 'en-US' && PLACEHOLDER_EN[origPh]) {
                el.setAttribute('placeholder', PLACEHOLDER_EN[origPh]);
            } else {
                el.setAttribute('placeholder', origPh);
            }
        });

        // 3. Valores predeterminados en inputs de texto (ej. nombres de deudas/metas por defecto)
        document.querySelectorAll('input[type="text"]').forEach(el => {
            if (el._ccOrigValue === undefined) {
                el._ccOrigValue = el.value;
            }
            const origVal = el._ccOrigValue ? el._ccOrigValue.trim() : '';
            if (!origVal) return;
            if (lang === 'en-US' && INPUT_VALUE_EN[origVal]) {
                if (el.value === origVal) {
                    el.value = INPUT_VALUE_EN[origVal];
                }
            } else if (lang !== 'en-US' && INPUT_VALUE_EN[origVal]) {
                if (el.value === INPUT_VALUE_EN[origVal]) {
                    el.value = el._ccOrigValue;
                }
            }
        });
    }

    function applyLanguage(lang) {
        const resolved = lang === 'en-US' ? 'en-US' : 'es-CO';
        localStorage.setItem(LANG_STORAGE_KEY, resolved);
        refreshPageLocalization();
        window.dispatchEvent(new CustomEvent('cuentaclara:language-changed', { detail: { language: resolved } }));
        return resolved;
    }

    function applyCurrency(currency) {
        const resolved = (currency === 'USD' || currency === 'EUR') ? currency : 'COP';
        localStorage.setItem(CURRENCY_STORAGE_KEY, resolved);
        refreshPageLocalization();
        window.dispatchEvent(new CustomEvent('cuentaclara:currency-changed', { detail: { currency: resolved } }));
        return resolved;
    }

    // Aplicar tema y modo antes de pintar
    applyTheme(getPreferredTheme());
    applyMode(getPreferredMode());

    document.addEventListener('DOMContentLoaded', () => {
        refreshPageLocalization();
    });

    // API pública global
    window.CuentaClaraTheme = {
        getTheme: getPreferredTheme,
        getMode: getPreferredMode,
        getLanguage: getPreferredLanguage,
        getCurrency: getPreferredCurrency,
        setTheme: applyTheme,
        setMode: applyMode,
        setLanguage: applyLanguage,
        setCurrency: applyCurrency,
        refreshLocalization: refreshPageLocalization,
        formatAmount: function (copAmount) {
            const curr = getPreferredCurrency();
            const cfg = EXCHANGE_RATES[curr] || EXCHANGE_RATES.COP;
            const num = Number(copAmount) || 0;
            if (curr === 'COP') {
                return '$ ' + num.toLocaleString('es-CO', { maximumFractionDigits: 0 }) + ' COP';
            }
            const converted = num / cfg.rate;
            return `${cfg.symbol} ${converted.toLocaleString(cfg.locale, { minimumFractionDigits: cfg.decimals, maximumFractionDigits: cfg.decimals })} ${cfg.code}`;
        },
        toggleDarkMode: function () {
            const nextMode = document.documentElement.classList.contains('dark') ? 'light' : 'dark';
            applyMode(nextMode);
            return nextMode;
        }
    };
})();
