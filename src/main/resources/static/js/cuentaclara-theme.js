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
        // Sidebar & Navegación
        "Organiza tu Dinero Día a Día": "Organize Your Money Day by Day",
        "Finanzas & RAG DIAN": "Personal Finance & AI",
        "Mi Espacio Personal": "My Personal Space",
        "Módulos Fiscales": "Personal Modules",
        "Módulos del Sistema": "System Modules",
        "Mi Calendario del Mes": "My Monthly Calendar",
        "Mi Calendario y Gastos": "My Calendar & Expenses",
        "Panel Principal": "Main Dashboard",
        "Historial de Gastos": "Expense History",
        "Subir Recibo o Factura": "Upload Receipt or Invoice",
        "Cargar y Confirmar": "Upload & Confirm",
        "Mi Plan y Deudas Fijas": "My Plan & Fixed Debts",
        "Presupuesto 50/30/20": "50/30/20 Budget Plan",
        "Pregúntale a tu Asistente": "Ask Your Assistant",
        "Asistente RAG": "AI Assistant",
        "Buscar en mis Gastos": "Search My Expenses",
        "Colores y Configuración": "Colors & Settings",
        "Configuración": "Settings",
        "Recordatorios Automáticos": "Automatic Reminders",
        "Tus pagos fijos y deudas se marcan cada mes en tu calendario.": "Your fixed payments and debts are marked every month on your calendar.",
        "Claro / Oscuro": "Light / Dark",
        "Modo Visual": "Visual Mode",
        "Modo Claro": "Light Mode",
        "Modo Oscuro": "Dark Mode",
        "Temas": "Themes",
        "Colores y Ajustes": "Colors & Settings",
        "+ Deuda o Pago Fijo": "+ Debt or Fixed Payment",
        "Inicio": "Home",
        "Mi Calendario": "My Calendar",

        // Dashboard & Calendario
        "Haz clic en cualquier día del calendario para anotar tus gastos diarios.": "Click on any day in the calendar to record your daily expenses.",
        "Cargar Datos de Ejemplo y Analizar Mes": "Load Sample Data & Analyze Month",
        "Tu Ingreso del Mes": "Your Monthly Income",
        "Editar →": "Edit →",
        "Gastado en este Mes": "Spent This Month",
        "Suma exacta": "Exact sum",
        "Pagos Fijos y Deudas": "Fixed Payments & Debts",
        "Se marcan cada mes": "Marked every month",
        "+ Agregar": "+ Add",
        "Tu Meta de Ahorro": "Your Savings Goal",
        "Para tus sueños y metas": "For your dreams & goals",
        "Interactivo": "Interactive",
        "Enlazado en tiempo real con la fecha de hoy. Haz clic en cualquier día para anotar tus gastos diarios o registrar pagos.": "Linked in real time with today's date. Click on any day to log daily expenses or record payments.",
        "Haz clic en cualquier día para anotar tus gastos diarios con su descripción, o para registrar tus pagos y deudas del mes.": "Click on any day to log your daily expenses with description, or record your monthly payments and debts.",
        "Hoy": "Today",
        "Mes Anterior": "Previous Month",
        "Mes Siguiente": "Next Month",
        "📍 Día de Hoy (Actual)": "📍 Today (Current Day)",
        "📌 Pago Fijo / Deuda Pendiente": "📌 Pending Fixed Payment / Debt",
        "📌 Pago Fijo / Deuda Pendiente del Mes": "📌 Pending Monthly Payment / Debt",
        "✓ Pago Fijo al Día": "✓ Fixed Payment Paid",
        "🛍️ Gasto Diario Registrado": "🛍️ Recorded Daily Expense",
        "💡 Tip: Toca el día resaltado (Hoy) o cualquier casilla para anotar gastos": "💡 Tip: Tap the highlighted day (Today) or any cell to log expenses",
        "💡 Tip: Toca cualquier casilla para abrir la ventana del día": "💡 Tip: Tap any cell to open the daily window",
        "Lun": "Mon",
        "Mar": "Tue",
        "Mié": "Wed",
        "Jue": "Thu",
        "Vie": "Fri",
        "Sáb": "Sat",
        "Dom": "Sun",
        "Anotar": "Log",
        "Sin gastos": "No expenses",
        "Total día:": "Day total:",
        "Revisión Rápida": "Quick Review",
        "Confirma los datos de tu recibo para sumarlo a tu calendario": "Confirm your receipt details to add it to your calendar",
        "Lugar o Comercio": "Place or Merchant",
        "Fecha del Gasto": "Expense Date",
        "Categoría": "Category",
        "Bloque de tu Plan": "Budget Block",
        "Valor Total ($ COP)": "Total Amount",
        "Guardar en mi Calendario de Gastos": "Save to My Expense Calendar",
        "📌 Pagos Fijos y Deudas de Cada Mes": "📌 Monthly Fixed Payments & Debts",
        "Se marcan automáticamente el mismo día en todos los meses de tu calendario": "Automatically marked on the same day every month in your calendar",
        "Se repiten automáticamente el mismo día en tu calendario": "Automatically repeated on the same day in your calendar",
        "+ Programar Nuevo Pago o Deuda": "+ Schedule New Payment or Debt",
        "+ Nuevo Pago": "+ New Payment",
        "Aún no has programado pagos fijos (como el alquiler o cuotas de deudas). Haz clic en \"+ Programar Nuevo Pago o Deuda\" para que se marquen cada mes.": "You haven't scheduled fixed payments yet (like rent or debt installments). Click '+ Schedule New Payment or Debt' so they appear every month.",
        "Día": "Day",
        "¿Quieres buscar o filtrar todos los gastos registrados en este mes?": "Want to search or filter all expenses recorded this month?",
        "Ir a Historial de Gastos del Mes →": "Go to Monthly Expense History →",
        "Personalizable": "Customizable",
        "Así se reparte tu ingreso para vivir tranquilo(a)": "How your income is distributed for peace of mind",
        "Consejos para tu próximo mes": "Tips for your next month",
        "Tu Asistente Personal": "Your Personal Assistant",
        "Respuestas claras y amables sobre tus gastos": "Clear and friendly answers about your expenses",
        "Abrir chat →": "Open chat →",
        "🍔 ¿Cuánto gasté en domicilios?": "🍔 How much did I spend on delivery?",
        "🏠 ¿Cuánto pagué de arriendo?": "🏠 How much did I pay for rent?",

        // Modal del Día y Modal de Obligaciones
        "Gastos y Pagos del Día": "Daily Expenses & Payments",
        "📌 Pagos Fijos / Deudas Programadas para este Día": "📌 Fixed Payments / Debts Scheduled for Today",
        "🛍️ Gastos Anotados en este Día": "🛍️ Expenses Recorded on This Day",
        "No hay gastos registrados en este día todavía. ¡Anota el primero abajo!": "No expenses recorded on this day yet. Log the first one below!",
        "✨ Anotar un Nuevo Gasto en este Día": "✨ Log a New Expense on This Day",
        "¿En qué gastaste o dónde?": "What did you spend on or where?",
        "Valor del Gasto ($ COP)": "Expense Amount",
        "Tipo de Gasto": "Expense Type",
        "🏠 Necesidades Esenciales": "🏠 Essential Needs",
        "🎉 Gustos y Deseos": "🎉 Wants & Leisure",
        "🐷 Ahorro / Pago de Deuda": "🐷 Savings / Debt Payment",
        "Descripción de estos gastos": "Description of these expenses",
        "Guardar Gasto en este Día": "Save Expense on This Day",
        "Programar Pago Fijo o Deuda Mensual": "Schedule Fixed Payment or Monthly Debt",
        "Este compromiso o deuda se marcará automáticamente el mismo día en cada mes de tu calendario (por ejemplo, el día 5 para el alquiler).": "This commitment or debt will be automatically marked on the same day every month on your calendar (e.g., the 5th for rent).",
        "Nombre del Pago o Deuda": "Payment or Debt Name",
        "Monto Mensual ($ COP)": "Monthly Amount",
        "Día del Mes (1 al 31)": "Day of Month (1 to 31)",
        "🏠 Vivienda / Alquiler": "🏠 Housing / Rent",
        "💳 Deudas y Préstamos": "💳 Debts & Loans",
        "💡 Servicios e Internet": "💡 Utilities & Internet",
        "📚 Educación": "📚 Education",
        "📌 Otros Pagos Fijos": "📌 Other Fixed Payments",
        "Marcar en Todos los Meses de mi Calendario": "Mark on Every Month of My Calendar",
        "Registrar este pago hoy": "Record this payment today",
        "Pagado este mes": "Paid this month",

        // Historial (/history)
        "Detalle Completo del Mes": "Full Monthly Breakdown",
        "Consulta, filtra y revisa cada uno de los gastos que has registrado durante el mes.": "Search, filter, and review every expense you have recorded during the month.",
        "Ir al Calendario del Mes": "Go to Monthly Calendar",
        "Mes a consultar": "Month to view",
        "Mes y Año a consultar": "Month & Year to view",
        "Todas las categorías": "All categories",
        "Lugar o Concepto": "Place or Concept",
        "Todos los lugares": "All places",
        "Filtrar": "Filter",
        "Todos": "All",
        "Ir al Calendario para Anotar Gastos": "Go to Calendar to Log Expenses",

        // Subir Factura (/upload)
        "Cargar y Confirmar Factura / Gasto": "Upload & Confirm Receipt / Expense",
        "ClamAV + Magic Bytes (máx. 10 MB)": "Safe Verification (max. 10 MB)",
        "Lectura Inteligente de Recibos": "Smart Receipt Reader",
        "• XML, PDF o Foto": "• XML, PDF or Photo",
        "Subir Recibo, Factura o Anotar Gasto": "Upload Receipt, Invoice or Log Expense",
        "Sube tus facturas o recibos para que leamos los datos por ti, o usa nuestro Calendario Mensual para anotar tus gastos día por día.": "Upload your invoices or receipts so we can read the details for you, or use our Monthly Calendar to log daily expenses.",
        "Abrir Calendario Día por Día": "Open Day-by-Day Calendar",
        "Sube tu recibo, factura XML, PDF o foto": "Upload your receipt, XML invoice, PDF or photo",
        "Leemos los datos automáticamente para que solo tengas que confirmarlos y se sumen a tu calendario del mes.": "We read the details automatically so you only have to confirm them to add them to your monthly calendar.",
        "Es un extracto PDF de varios movimientos (para consultar en el asistente)": "It is a multi-transaction PDF statement (for assistant queries)",
        "Leer Datos de mi Recibo": "Read My Receipt Data",
        "Anotar un Gasto Diario Manualmente": "Log a Daily Expense Manually",
        "¿En qué gastaste o dónde? *": "What did you spend on or where? *",
        "Valor del Gasto ($ COP) *": "Expense Amount ($ COP) *",
        "Día del Gasto": "Expense Date",
        "Categoría (Desplegable)": "Category (Dropdown)",
        "✨ Sugerir automáticamente": "✨ Auto-suggest",
        "Descripción de esos gastos": "Description of those expenses",
        "Foto o Recibo (Opcional)": "Photo or Receipt (Optional)",
        "Guardar Gasto en mi Calendario": "Save Expense to My Calendar",
        "No tienes facturas pendientes de confirmar": "You have no pending receipts to confirm",
        "Cuando subas una factura XML UBL 2.1, PDF o Foto en la columna izquierda, aparecerá aquí el voucher bancario detallado para que confirmes o corrijas sus campos antes de indexar en pgvector (RF-05).": "When you upload an XML invoice, PDF or Photo on the left column, the detailed receipt preview will appear here so you can confirm or edit its fields before saving.",
        "Paso 2 de 3 • Confirmación del Usuario (RF-05)": "Step 2 of 3 • User Confirmation",
        "Revisa y Confirma los Datos Extraídos": "Review & Confirm Extracted Data",
        "Emisor / Proveedor": "Merchant / Provider",
        "NIT Registrado": "Tax ID / NIT",
        "Número de Factura": "Invoice Number",
        "Fecha de Emisión": "Issue Date",
        "Categoría (RF-09)": "Category",
        "Bloque 50 / 30 / 20": "50 / 30 / 20 Block",
        "Subtotal Extraído": "Extracted Subtotal",
        "IVA / Impuestos": "VAT / Taxes",
        "Total Liquidado a Confirmar (COP)": "Total Amount to Confirm (COP)",
        "Paso 3: Confirmar, Indexar en pgvector y Eliminar Copia Temporal": "Step 3: Confirm & Save to My Calendar",

        // Perfil (/profile - Mi Plan y Deudas Fijas)
        "Perfil Financiero y Regla 50 / 30 / 20": "Financial Profile & 50 / 30 / 20 Rule",
        "Ir al Panel": "Go to Calendar",
        "Planificación desde el Día 1 (RF-11, RF-15, RF-17)": "Day 1 Personal Planning",
        "Perfil Financiero y Presupuesto Inteligente": "Financial Profile & Smart Budget",
        "Configura tus ingresos netos, frecuencia de recaudo, compromisos prioritarios y la distribución del presupuesto regla 50/30/20 (suma = 100%).": "Configure your net income, pay frequency, priority obligations, and 50/30/20 budget distribution (sum = 100%).",
        "Configura tus ingresos netos, frecuencia recaudo, compromisos prioritarios y la distribución del presupuesto regla 50/30/20 (suma = 100%).": "Configure your net income, pay frequency, priority obligations, and 50/30/20 budget distribution (sum = 100%).",
        "Calibra tu Presupuesto 50/30/20": "Customize Your 50/30/20 Plan",
        "01 • Ingreso Neto por Periodo Declarado (COP)": "01 • Declared Net Income per Period (COP)",
        "01 • Ingreso Neto y Deudas": "01 • Net Income & Debts",
        "Monto Neto ($ COP)": "Net Amount ($ COP)",
        "Deudas Totales (COP)": "Total Debts (COP)",
        "Meta Principal": "Main Goal",
        "Ingreso Mensual Neto (COP) *": "Monthly Net Income (COP) *",
        "Total Deudas Actuales (COP)": "Total Current Debts (COP)",
        "Meta Financiera Principal": "Main Financial Goal",
        "02 • Frecuencia y Días de Pago": "02 • Pay Frequency & Days",
        "Frecuencia de Recaudo": "Income Frequency",
        "Días de recepción (ej. 15,30 o 30)": "Paydays (e.g. 15,30 or 30)",
        "03 • Calibración Regla 50 / 30 / 20": "03 • 50 / 30 / 20 Rule Calibration",
        "Suma: 100% Correcto": "Sum: 100% Valid",
        "Necesidades": "Needs",
        "Deseos": "Wants",
        "Ahorro": "Savings",
        "04 • Tus Pagos Fijos y Deudas Importantes de Cada Mes": "04 • Your Monthly Fixed Payments & Important Debts",
        "Indica qué día del mes (1 al 31) debes pagar cada compromiso o deuda; se marcarán automáticamente cada mes en tu calendario.": "Indicate which day of the month (1 to 31) each payment or debt is due; they will be automatically marked every month on your calendar.",
        "+ Agregar otra deuda o pago fijo": "+ Add another debt or fixed payment",
        "Concepto / Deuda": "Concept / Debt",
        "Concepto / Pago": "Concept / Payment",
        "Día del Mes (1-31)": "Day of Month (1-31)",
        "Ver mi Calendario": "View My Calendar",
        "Guardar mi Plan y Marcar Pagos en el Calendario": "Save My Plan & Mark Payments on Calendar",
        "Necesidades Vitales": "Vital Needs",
        "Deseos & Ocio": "Wants & Leisure",
        "Patrimonio": "Wealth & Savings",
        "Ahorro & Respaldo": "Savings & Emergency Fund",
        "Diagnóstico Determinista desde el Día 1 (RF-13, RF-16)": "Day 1 Financial Summary & Recommendations",
        "ESTIMACIÓN INICIAL: Este presupuesto se basa en tus datos declarados. Al cerrar tu primer mes de facturas y gastos confirmados generaremos un plan ajustado a tu comportamiento real.": "INITIAL ESTIMATE: This budget is based on your declared data. Once you close your first month of confirmed receipts and expenses, we will generate a plan tailored to your real behavior.",
        "🛒 Alimentación": "🛒 Groceries & Food",
        "📌 Otros": "📌 Other",
        "Quincenal": "Biweekly",
        "Variable": "Variable",

        // Asistente (/assistant)
        "Asistente Financiero Inteligente": "Smart Financial Assistant",
        "Subir Factura": "Upload Receipt",
        "Índice de Evidencias": "Evidence Index",
        "Comprobantes Confirmados Recientes": "Recent Confirmed Receipts",
        "Sin comprobantes confirmados aún.": "No confirmed receipts yet.",
        "Asistente CuentaClara": "CuentaClara Assistant",
        "Cálculos Verificados": "Verified Calculations",
        "Tus cifras provienen con exactitud matemática de tus movimientos confirmados y cada respuesta cita su comprobante.": "Your figures come with mathematical precision from your confirmed transactions and every answer cites its receipt.",
        "Sugeridos (1 clic):": "Suggested (1 click):",
        "¿Cuánto gasté en domicilios en octubre?": "How much did I spend on delivery in October?",
        "¿En qué factura compré Café Molido Gourmet?": "In which invoice did I buy Gourmet Ground Coffee?",
        "¿Cuánto gasté en Almacenes Éxito?": "How much did I spend at Almacenes Éxito?",
        "¿Cuál es mi presupuesto y distribución 50/30/20?": "What is my budget and 50/30/20 distribution?",
        "Haz una pregunta sobre tus finanzas o elige una sugerencia arriba": "Ask a question about your finances or choose a suggestion above",
        "El asistente consultará tus comprobantes confirmados y tus documentos indexados para darte una respuesta clara, humana y exacta.": "The assistant will check your confirmed receipts and indexed documents to give you a clear, friendly, and exact answer.",
        "Respuesta Respaldada con Comprobantes": "Receipt-Backed Answer",
        "Comprobantes & Facturas de Respaldo": "Supporting Receipts & Invoices",
        "Comprobante Válido": "Valid Receipt",
        "Consultar": "Ask",

        // Configuración (/settings)
        "Centro de Configuración & Ajustes": "Settings & Customization Center",
        "Personaliza la estética, paleta de colores, modo visual, perfil y parámetros de la aplicación.": "Customize the aesthetics, color palette, visual mode, profile, and system parameters.",
        "Volver al Panel": "Back to Calendar",
        "Paleta de Colores & Apariencia": "Color Palette & Appearance",
        "Elige la identidad visual y estilo de contraste que mejor se adapte a ti.": "Choose the visual identity and contrast style that suits you best.",
        "Cambio en Tiempo Real": "Real-Time Preview",
        "Selecciona tu Paleta Preferida": "Select Your Preferred Palette",
        "Vino Tinto": "Burgundy Wine",
        "Predeterminado. Elegante, sobrio y de alta distinción.": "Default. Elegant, warm, and distinctive.",
        "Verde Esmeralda": "Emerald Green",
        "Finanzas clásicas, prosperidad y crecimiento.": "Classic finance, prosperity, and growth.",
        "Azul Zafiro": "Sapphire Blue",
        "Corporativo, analítico y máxima confianza.": "Corporate, analytical, and trustworthy.",
        "Morado Amatista": "Amethyst Purple",
        "Creativo, moderno, vibrante y exclusivo.": "Creative, modern, vibrant, and exclusive.",
        "Grafito Dark Luxe": "Graphite Dark Luxe",
        "Minimalista, contraste técnico y sobriedad total.": "Minimalist, crisp contrast, and total sobriety.",
        "Modo Claro / Modo Oscuro": "Light Mode / Dark Mode",
        "Elige la intensidad lumínica para trabajar de día o descansar la vista de noche.": "Choose the brightness level for daytime work or night comfort.",
        "Claro": "Light",
        "Oscuro": "Dark",
        "Idioma & Preferencias Regionales": "Language & Regional Preferences",
        "Configura la moneda de visualización, formatos de número y localización.": "Configure display currency, number formats, and localization.",
        "Idioma de la Interfaz": "Interface Language",
        "Toda la asesoría RAG y términos DIAN se presentan en este idioma.": "All assistant answers and interface labels are presented in this language.",
        "Moneda Principal": "Primary Currency",
        "Se utiliza para los cálculos deterministas en SQL y reportes.": "Used for all calculations, conversions, and reports across the app.",
        "Tono del Asistente RAG": "Assistant Tone",
        "Amable, Claro y Humano (Recomendado)": "Friendly, Clear & Human (Recommended)",
        "Conciso y Ejecutivo": "Concise & Executive",
        "Detallado con Referencias Técnicas": "Detailed with Technical References",
        "Define cómo se redactan las respuestas del chat conversacional.": "Defines how conversational chat answers are written.",
        "Confirmar y Aplicar Idioma al Sistema": "Confirm & Apply Language & Currency",
        "Parámetros del Perfil Financiero & Presupuesto": "Financial Profile & Budget Parameters",
        "Edita tus ingresos, frecuencia de pago y porcentajes de la regla presupuestal 50/30/20.": "Edit your income, pay frequency, and 50/30/20 budget rule percentages.",
        "Ingreso Mensual Neto (COP)": "Monthly Net Income",
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
        [/Calendario de Gastos — /g, "Expense Calendar — "],
        [/Historial de Gastos — /g, "Expense History — "],
        [/Organizador de /g, "Organizer for "],
        [/📍 Hoy: /g, "📍 Today: "],
        [/HOY • /g, "TODAY • "],
        [/¡Hola, /g, "Hello, "],
        [/Total Filtrado: /g, "Filtered Total: "],
        [/Total Verificado: /g, "Verified Total: "],
        [/gasto\(s\) anotados/g, "recorded expense(s)"],
        [/gasto\(s\) en este listado/g, "expense(s) in this list"],
        [/gasto\(s\) más/g, "more expense(s)"],
        [/movimientos confirmados/g, "confirmed transactions"],
        [/en Calendario/g, "on Calendar"],
        [/del ingreso/g, "of income"],
        [/Tu Plan /g, "Your Plan "],
        [/Plan /g, "Plan "],
        [/Necesidades Esenciales/g, "Essential Needs"],
        [/Gustos y Deseos/g, "Wants & Leisure"],
        [/Ahorro y Metas/g, "Savings & Goals"],
        [/Hasta \$/g, "Up to $"],
        [/Meta: \$/g, "Goal: $"],
        [/Día (\d+) de cada mes/g, "Day $1 of every month"],
        [/Cada mes el día (\d+)/g, "Every month on day $1"],
        [/📅 Fecha: /g, "📅 Date: "],
        [/• Respaldo: /g, "• Reference: "],
        [/📝 Descripción: /g, "📝 Description: "],
        [/ESTIMACIÓN INICIAL: Este presupuesto se basa en tus datos declarados\. Al cerrar tu primer mes de facturas y gastos confirmados generaremos un plan ajustado a tu comportamiento real\./g, "INITIAL ESTIMATE: This budget is based on your declared data. Once you close your first month of confirmed receipts and expenses, we will generate a plan tailored to your real behavior."],
        [/Atención realista \(RF-13\): Tus compromisos esenciales declarados/g, "Realistic note: Your declared essential obligations"],
        [/Tus compromisos recurrentes esenciales suman /g, "Your essential recurring obligations total "],
        [/, dejándote un margen estimado de /g, ", leaving you an estimated margin of "],
        [/dentro de tu bloque de Necesidades/g, "within your Needs block"],
        [/para mercado y transporte variable\./g, "for groceries and variable transportation."],
        [/superan en /g, "exceed by "],
        [/el límite teórico de Necesidades/g, "the theoretical Needs limit"],
        [/No te recomendamos incumplir obligaciones básicas; te sugerimos cubrir esa diferencia reduciendo temporalmente el bloque de Deseos/g, "We do not recommend missing basic obligations; we suggest covering that difference by temporarily reducing your Wants block"],
        [/o ajustando tu regla porcentual \(por ejemplo a 65\/20\/15\)\./g, "or adjusting your percentage rule (e.g., to 65/20/15)."],
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
        "Pregúntame lo que quieras: ¿Cuánto gasté en domicilios este mes?...": "Ask me anything: How much did I spend on delivery this month?...",
        "Escribe tu pregunta aquí...": "Type your question here...",
        "Haz una pregunta sobre tus facturas, gastos o extractos en lenguaje natural...": "Ask a question about your receipts, expenses or statements...",
        "Ej. Supermercado, Almuerzo, Alquiler...": "E.g. Supermarket, Lunch, Rent...",
        "Ej. Supermercado, Almuerzo...": "E.g. Supermarket, Lunch...",
        "Escribe qué compraste o detalles de este gasto (ej. Mercado de la semana, pago de cuota 3, almuerzo con amigos)...": "Write what you bought or details of this expense (e.g. Weekly groceries, installment 3, lunch with friends)...",
        "Ej. Alquiler Apartamento, Cuota Préstamo, Tarjeta...": "E.g. Apartment Rent, Loan Installment, Credit Card...",
        "Ej. Pago de Alquiler o Cuota Tarjeta": "E.g. Rent Payment or Card Installment",
        "Ej. Tarjeta, Crédito, Internet...": "E.g. Credit Card, Loan, Internet...",
        "Ej. Compra de mercado, verduras y frutas": "E.g. Grocery shopping, vegetables and fruits"
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
