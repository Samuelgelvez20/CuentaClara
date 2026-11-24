/**
 * CuentaClara — Visual Date & Period Picker (Inspirado en Bunny Analyst × Tokens CuentaClara)
 * Soporta selección de cualquier fecha antigua o futura (1000–9999), columna de años navegable,
 * salto rápido por año/mes, doble calendario mensual y selección por día, mes o rango de fechas.
 */
(function () {
    const MONTHS_FULL = [
        'Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio',
        'Julio', 'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'
    ];
    const MONTHS_SHORT = [
        'Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun',
        'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic'
    ];
    const WEEKDAYS = ['Lu', 'Ma', 'Mi', 'Ju', 'Vi', 'Sa', 'Do'];

    const hasNativePopover = typeof HTMLElement !== 'undefined'
        && Object.hasOwn(HTMLElement.prototype, 'popover');

    function pad2(n) {
        return String(n).padStart(2, '0');
    }

    function toIso(y, m, d) {
        return `${String(y).padStart(4, '0')}-${pad2(m)}-${pad2(d)}`;
    }

    function toYm(y, m) {
        return `${String(y).padStart(4, '0')}-${pad2(m)}`;
    }

    function parseIso(str) {
        if (!str || !/^\d{4}-\d{2}-\d{2}$/.test(str)) return null;
        const [y, m, d] = str.split('-').map(Number);
        if (m < 1 || m > 12 || d < 1 || d > 31) return null;
        return { y, m, d };
    }

    function parseYm(str) {
        if (!str || !/^\d{4}-\d{2}$/.test(str)) return null;
        const [y, m] = str.split('-').map(Number);
        if (m < 1 || m > 12) return null;
        return { y, m };
    }

    function daysInMonth(y, m) {
        return new Date(y, m, 0).getDate();
    }

    function shiftMonth(y, m, delta) {
        const total = (y * 12 + (m - 1)) + delta;
        const ny = Math.floor(total / 12);
        const nm = ((total % 12) + 12) % 12 + 1;
        return { y: ny, m: nm };
    }

    function addDaysIso(iso, delta) {
        const p = parseIso(iso);
        if (!p) return iso;
        const dt = new Date(p.y, p.m - 1, p.d + delta);
        return toIso(dt.getFullYear(), dt.getMonth() + 1, dt.getDate());
    }

    function formatShortIso(iso) {
        const p = parseIso(iso);
        if (!p) return iso || '';
        return `${p.d} ${MONTHS_SHORT[p.m - 1]} ${p.y}`;
    }

    function formatMonthName(y, m) {
        return `${MONTHS_FULL[m - 1]} ${y}`;
    }

    function clampYear(y) {
        if (Number.isNaN(y)) return new Date().getFullYear();
        return Math.min(2999, Math.max(1800, y));
    }

    function initDatePicker(root) {
        const trigger = root.querySelector('[data-cc-dp-trigger]');
        const panel = root.querySelector('[data-cc-dp-panel]');
        const form = root.querySelector('[data-cc-dp-form]');
        if (!trigger || !panel || !form) return;

        const mode = root.getAttribute('data-dp-mode') || 'month';
        const actionUrl = root.getAttribute('data-dp-action') || '/dashboard';
        const isDashboardDayAction = actionUrl.indexOf('/dashboard') === 0 && form.querySelector('[data-dp-input-day]') !== null;
        const todayIso = parseIso(root.getAttribute('data-dp-today'))
            ? root.getAttribute('data-dp-today')
            : (() => {
                const now = new Date();
                return toIso(now.getFullYear(), now.getMonth() + 1, now.getDate());
            })();
        const todayParts = parseIso(todayIso);
        const initialYm = parseYm(root.getAttribute('data-dp-month')) || { y: todayParts.y, m: todayParts.m };
        const initialStart = root.getAttribute('data-dp-start') || '';
        const initialEnd = root.getAttribute('data-dp-end') || '';
        const initialAll = root.getAttribute('data-dp-all') === 'true';

        let urlDayNum = null;
        try {
            const urlParams = new URLSearchParams(window.location.search);
            const rawDay = parseInt(urlParams.get('day'), 10);
            if (rawDay >= 1 && rawDay <= 31) urlDayNum = rawDay;
        } catch (_) {}

        const state = {
            mode,
            leftY: initialYm.y,
            leftM: initialYm.m,
            yearWindowCenter: initialYm.y,
            // mode === 'month'
            selectedMonth: toYm(initialYm.y, initialYm.m),
            selectedDay: urlDayNum,
            // mode === 'range'
            rangeStart: initialStart || (initialAll ? '' : toIso(initialYm.y, initialYm.m, 1)),
            rangeEnd: initialEnd || (initialAll ? '' : toIso(initialYm.y, initialYm.m, daysInMonth(initialYm.y, initialYm.m))),
            isAll: initialAll,
            hoverIso: null,
            activePreset: initialAll ? 'all' : ''
        };

        if (state.mode === 'range' && initialStart) {
            const sp = parseIso(initialStart);
            if (sp) {
                state.leftY = sp.y;
                state.leftM = sp.m;
                state.yearWindowCenter = sp.y;
            }
        }

        function isOpen() {
            if (hasNativePopover) {
                return panel.matches(':popover-open');
            }
            return panel.classList.contains('is-popover-open');
        }

        function positionPanel() {
            const vw = window.innerWidth;
            const vh = window.innerHeight;
            if (vw < 680) {
                panel.style.left = '';
                panel.style.top = '';
                panel.classList.add('is-mobile-centered');
                return;
            }
            panel.classList.remove('is-mobile-centered');

            const rect = trigger.getBoundingClientRect();
            const panelW = Math.min(680, vw - 24);
            panel.style.width = `${panelW}px`;

            // Medir altura real
            const panelH = panel.offsetHeight || 440;
            let left = rect.left;
            if (left + panelW > vw - 12) {
                left = vw - panelW - 12;
            }
            if (left < 12) left = 12;

            let top = rect.bottom + 8;
            if (top + panelH > vh - 12) {
                const aboveTop = rect.top - panelH - 8;
                if (aboveTop >= 12) {
                    top = aboveTop;
                } else {
                    top = Math.max(12, vh - panelH - 12);
                }
            }

            panel.style.left = `${Math.round(left)}px`;
            panel.style.top = `${Math.round(top)}px`;
        }

        function openPanel() {
            render();
            if (hasNativePopover) {
                if (!panel.matches(':popover-open')) {
                    panel.showPopover();
                }
            } else {
                panel.classList.add('is-popover-open');
            }
            trigger.setAttribute('aria-expanded', 'true');
            positionPanel();
        }

        function closePanel(restoreFocus) {
            if (hasNativePopover) {
                if (panel.matches(':popover-open')) {
                    panel.hidePopover();
                }
            } else {
                panel.classList.remove('is-popover-open');
            }
            trigger.setAttribute('aria-expanded', 'false');
            if (restoreFocus) trigger.focus();
        }

        function updateRangePreviewDom() {
            if (state.mode !== 'range') return;
            const start = state.rangeStart;
            const end = state.rangeEnd || state.hoverIso;
            let low = start;
            let high = end;
            if (low && high && low > high) {
                low = end;
                high = start;
            }
            panel.querySelectorAll('.cc-dp-day[data-dp-iso]').forEach(btn => {
                const iso = btn.getAttribute('data-dp-iso');
                btn.classList.remove('is-selected', 'is-range-start', 'is-range-end', 'is-in-range', 'is-in-preview');
                if (state.isAll) return;
                if (start && !state.rangeEnd && iso === start) {
                    btn.classList.add('is-range-start', 'is-range-end');
                } else if (low && high) {
                    if (iso === low) btn.classList.add('is-range-start');
                    if (iso === high) btn.classList.add('is-range-end');
                    if (iso > low && iso < high) {
                        btn.classList.add(state.rangeEnd ? 'is-in-range' : 'is-in-preview');
                    }
                }
            });
        }

        function buildCalendarHtml(y, m, side) {
            const dim = daysInMonth(y, m);
            const firstDow = new Date(y, m - 1, 1).getDay(); // 0=Dom..6=Sab
            const leading = (firstDow + 6) % 7; // Lunes=0 .. Domingo=6
            const prevMonth = shiftMonth(y, m, -1);
            const prevDim = daysInMonth(prevMonth.y, prevMonth.m);
            const nextMonth = shiftMonth(y, m, 1);

            let cellsHtml = '';

            for (let i = 0; i < leading; i++) {
                const d = prevDim - leading + 1 + i;
                const iso = toIso(prevMonth.y, prevMonth.m, d);
                cellsHtml += `<button type="button" class="cc-dp-day is-outside" data-dp-iso="${iso}" data-dp-ym="${toYm(prevMonth.y, prevMonth.m)}" data-dp-day="${d}">${d}</button>`;
            }

            for (let d = 1; d <= dim; d++) {
                const iso = toIso(y, m, d);
                const ym = toYm(y, m);
                const classes = ['cc-dp-day'];
                if (iso === todayIso) classes.push('is-today');

                if (state.mode === 'month') {
                    if (state.selectedDay !== null) {
                        if (ym === state.selectedMonth && d === state.selectedDay) {
                            classes.push('is-selected');
                        }
                    } else if (ym === state.selectedMonth) {
                        classes.push('is-in-month');
                    }
                } else if (!state.isAll && state.rangeStart) {
                    const effEnd = state.rangeEnd || state.rangeStart;
                    let low = state.rangeStart;
                    let high = effEnd;
                    if (low > high) {
                        low = effEnd;
                        high = state.rangeStart;
                    }
                    if (iso === low) classes.push('is-range-start');
                    if (iso === high) classes.push('is-range-end');
                    if (iso > low && iso < high) classes.push('is-in-range');
                }

                cellsHtml += `<button type="button" class="${classes.join(' ')}" data-dp-iso="${iso}" data-dp-ym="${ym}" data-dp-day="${d}">${d}</button>`;
            }

            const rendered = leading + dim;
            const trailing = (7 - (rendered % 7)) % 7;
            for (let d = 1; d <= trailing; d++) {
                const iso = toIso(nextMonth.y, nextMonth.m, d);
                cellsHtml += `<button type="button" class="cc-dp-day is-outside" data-dp-iso="${iso}" data-dp-ym="${toYm(nextMonth.y, nextMonth.m)}" data-dp-day="${d}">${d}</button>`;
            }

            const prevNavHtml = side === 'left'
                ? `<button type="button" class="cc-dp-nav-arrow" data-dp-action-btn="prev-month" aria-label="Mes anterior"><span class="material-symbols-outlined text-[18px]">chevron_left</span></button>`
                : `<span class="cc-dp-nav-spacer" aria-hidden="true"></span>`;
            const nextNavHtml = side === 'right'
                ? `<button type="button" class="cc-dp-nav-arrow" data-dp-action-btn="next-month" aria-label="Mes siguiente"><span class="material-symbols-outlined text-[18px]">chevron_right</span></button>`
                : `<span class="cc-dp-nav-spacer" aria-hidden="true"></span>`;

            return `
                <div class="cc-dp-month-box">
                    <div class="cc-dp-month-head">
                        ${prevNavHtml}
                        <button type="button" class="cc-dp-month-title-btn" data-dp-select-month="${toYm(y, m)}" title="Seleccionar todo ${formatMonthName(y, m)}">
                            ${formatMonthName(y, m)}
                        </button>
                        ${nextNavHtml}
                    </div>
                    <div class="cc-dp-weekdays" aria-hidden="true">
                        ${WEEKDAYS.map(w => `<span>${w}</span>`).join('')}
                    </div>
                    <div class="cc-dp-days-grid">
                        ${cellsHtml}
                    </div>
                </div>
            `;
        }

        function getSelectionSummary() {
            if (state.mode === 'range') {
                if (state.isAll) return 'Todo el historial disponible';
                if (state.rangeStart && state.rangeEnd) {
                    if (state.rangeStart === state.rangeEnd) {
                        return `Día: ${formatShortIso(state.rangeStart)}`;
                    }
                    return `${formatShortIso(state.rangeStart)} – ${formatShortIso(state.rangeEnd)}`;
                }
                if (state.rangeStart) {
                    return `Desde ${formatShortIso(state.rangeStart)} (elige fin)`;
                }
                return 'Elige un rango o mes';
            }
            const parsed = parseYm(state.selectedMonth) || { y: state.leftY, m: state.leftM };
            if (state.selectedDay !== null && isDashboardDayAction) {
                return `${state.selectedDay} ${MONTHS_FULL[parsed.m - 1]} ${parsed.y}`;
            }
            return formatMonthName(parsed.y, parsed.m);
        }

        function render() {
            const right = shiftMonth(state.leftY, state.leftM, 1);

            const presets = state.mode === 'range'
                ? [
                    { id: 'today', label: 'Hoy' },
                    { id: '1w', label: '1 semana' },
                    { id: '2w', label: '2 semanas' },
                    { id: '1m', label: 'Este mes' },
                    { id: 'prev-m', label: 'Mes pasado' },
                    { id: '3m', label: '3 meses' },
                    { id: '1y', label: '1 año' },
                    { id: 'all', label: 'Todo' }
                ]
                : [
                    { id: 'today', label: 'Mes actual' },
                    { id: 'prev-m', label: 'Mes pasado' },
                    { id: 'minus-3m', label: '-3 meses' },
                    { id: 'minus-1y', label: '-1 año' },
                    { id: 'next-m', label: 'Próx. mes' },
                    { id: 'plus-1y', label: '+1 año' }
                ];

            const minY = clampYear(state.yearWindowCenter - 7);
            const maxY = clampYear(minY + 14);
            let yearsHtml = '';
            for (let yr = minY; yr <= maxY; yr++) {
                const isSel = yr === state.leftY;
                yearsHtml += `<button type="button" class="cc-dp-year-btn ${isSel ? 'is-active' : ''}" data-dp-year="${yr}">${yr}</button>`;
            }

            const monthsStripHtml = MONTHS_SHORT.map((lbl, idx) => {
                const mNum = idx + 1;
                const isCur = mNum === state.leftM;
                return `<button type="button" class="cc-dp-month-chip ${isCur ? 'is-active' : ''}" data-dp-month-chip="${mNum}">${lbl}</button>`;
            }).join('');

            panel.innerHTML = `
                <div class="cc-dp-topbar">
                    <div class="cc-dp-presets" role="group" aria-label="Atajos rápidos de periodo">
                        <span class="cc-dp-presets-label">Tiempo</span>
                        ${presets.map(p => `<button type="button" class="cc-dp-preset-pill ${state.activePreset === p.id ? 'is-active' : ''}" data-dp-preset="${p.id}">${p.label}</button>`).join('')}
                    </div>
                    <div class="cc-dp-year-jump">
                        <input type="number" min="1800" max="2999" step="1" class="cc-dp-year-input" value="${state.leftY}" aria-label="Ir a cualquier año" data-dp-year-input/>
                        <button type="button" class="cc-dp-year-go" data-dp-action-btn="go-year" title="Ir a ese año">Ir</button>
                    </div>
                </div>

                <div class="cc-dp-months-strip" role="group" aria-label="Seleccionar mes de ${state.leftY}">
                    ${monthsStripHtml}
                </div>

                <div class="cc-dp-body">
                    <div class="cc-dp-years-col" aria-label="Seleccionar año">
                        <button type="button" class="cc-dp-years-arrow" data-dp-action-btn="years-up" aria-label="Años anteriores" title="Ver años más antiguos">
                            <span class="material-symbols-outlined text-[18px]">keyboard_arrow_up</span>
                        </button>
                        <div class="cc-dp-years-list" data-dp-years-list>
                            ${yearsHtml}
                        </div>
                        <button type="button" class="cc-dp-years-arrow" data-dp-action-btn="years-down" aria-label="Años siguientes" title="Ver años más recientes o futuros">
                            <span class="material-symbols-outlined text-[18px]">keyboard_arrow_down</span>
                        </button>
                    </div>

                    <div class="cc-dp-calendars">
                        ${buildCalendarHtml(state.leftY, state.leftM, 'left')}
                        ${buildCalendarHtml(right.y, right.m, 'right')}
                    </div>
                </div>

                <div class="cc-dp-footer">
                    <div class="cc-dp-summary" data-dp-summary>${getSelectionSummary()}</div>
                    <div class="cc-dp-footer-actions">
                        <button type="button" class="cc-dp-btn-ghost" data-dp-action-btn="today">Hoy</button>
                        <button type="button" class="cc-dp-btn-ghost" data-dp-action-btn="cancel">Cancelar</button>
                        <button type="button" class="cc-dp-btn-apply cc-cta-tactile" data-dp-action-btn="apply">Aplicar</button>
                    </div>
                </div>
            `;

            const activeYearEl = panel.querySelector('.cc-dp-year-btn.is-active');
            if (activeYearEl) {
                activeYearEl.scrollIntoView({ block: 'nearest' });
            }
        }

        function applyPreset(presetId) {
            state.activePreset = presetId;
            state.isAll = false;
            if (state.mode === 'range') {
                if (presetId === 'today') {
                    state.rangeStart = todayIso;
                    state.rangeEnd = todayIso;
                    state.leftY = todayParts.y;
                    state.leftM = todayParts.m;
                } else if (presetId === '1w') {
                    state.rangeStart = addDaysIso(todayIso, -6);
                    state.rangeEnd = todayIso;
                    const sp = parseIso(state.rangeStart);
                    state.leftY = sp.y;
                    state.leftM = sp.m;
                } else if (presetId === '2w') {
                    state.rangeStart = addDaysIso(todayIso, -13);
                    state.rangeEnd = todayIso;
                    const sp = parseIso(state.rangeStart);
                    state.leftY = sp.y;
                    state.leftM = sp.m;
                } else if (presetId === '1m') {
                    state.rangeStart = toIso(todayParts.y, todayParts.m, 1);
                    state.rangeEnd = toIso(todayParts.y, todayParts.m, daysInMonth(todayParts.y, todayParts.m));
                    state.leftY = todayParts.y;
                    state.leftM = todayParts.m;
                } else if (presetId === 'prev-m') {
                    const prev = shiftMonth(todayParts.y, todayParts.m, -1);
                    state.rangeStart = toIso(prev.y, prev.m, 1);
                    state.rangeEnd = toIso(prev.y, prev.m, daysInMonth(prev.y, prev.m));
                    state.leftY = prev.y;
                    state.leftM = prev.m;
                } else if (presetId === '3m') {
                    const st = shiftMonth(todayParts.y, todayParts.m, -2);
                    state.rangeStart = toIso(st.y, st.m, 1);
                    state.rangeEnd = todayIso;
                    state.leftY = st.y;
                    state.leftM = st.m;
                } else if (presetId === '1y') {
                    const st = shiftMonth(todayParts.y, todayParts.m, -11);
                    state.rangeStart = toIso(st.y, st.m, 1);
                    state.rangeEnd = todayIso;
                    state.leftY = todayParts.y;
                    state.leftM = todayParts.m;
                } else if (presetId === 'all') {
                    state.isAll = true;
                    state.rangeStart = '';
                    state.rangeEnd = '';
                }
            } else {
                let target = { y: todayParts.y, m: todayParts.m };
                let targetDay = null;
                if (presetId === 'today') {
                    target = { y: todayParts.y, m: todayParts.m };
                    targetDay = isDashboardDayAction ? todayParts.d : null;
                } else if (presetId === 'prev-m') {
                    target = shiftMonth(todayParts.y, todayParts.m, -1);
                } else if (presetId === 'minus-3m') {
                    target = shiftMonth(todayParts.y, todayParts.m, -3);
                } else if (presetId === 'minus-1y') {
                    target = shiftMonth(todayParts.y, todayParts.m, -12);
                } else if (presetId === 'next-m') {
                    target = shiftMonth(todayParts.y, todayParts.m, 1);
                } else if (presetId === 'plus-1y') {
                    target = shiftMonth(todayParts.y, todayParts.m, 12);
                }
                state.leftY = target.y;
                state.leftM = target.m;
                state.selectedMonth = toYm(target.y, target.m);
                state.selectedDay = targetDay;
            }
            state.yearWindowCenter = state.leftY;
            render();
        }

        function submitSelection() {
            const monthInput = form.querySelector('[data-dp-input-month]');
            const dayInput = form.querySelector('[data-dp-input-day]');
            const startInput = form.querySelector('[data-dp-input-start]');
            const endInput = form.querySelector('[data-dp-input-end]');

            if (state.mode === 'range') {
                if (state.isAll) {
                    if (monthInput) {
                        monthInput.disabled = false;
                        monthInput.value = 'ALL';
                    }
                    if (startInput) startInput.disabled = true;
                    if (endInput) endInput.disabled = true;
                } else {
                    let s = state.rangeStart;
                    let e = state.rangeEnd || state.rangeStart;
                    if (s && e && s > e) {
                        const tmp = s;
                        s = e;
                        e = tmp;
                    }
                    const sp = parseIso(s);
                    const ep = parseIso(e);
                    const isWholeSingleMonth = sp && ep
                        && sp.y === ep.y && sp.m === ep.m
                        && sp.d === 1 && ep.d === daysInMonth(sp.y, sp.m);

                    if (isWholeSingleMonth) {
                        if (monthInput) {
                            monthInput.disabled = false;
                            monthInput.value = toYm(sp.y, sp.m);
                        }
                        if (startInput) startInput.disabled = true;
                        if (endInput) endInput.disabled = true;
                    } else if (s && e) {
                        if (monthInput) {
                            monthInput.disabled = false;
                            monthInput.value = toYm(sp.y, sp.m);
                        }
                        if (startInput) {
                            startInput.disabled = false;
                            startInput.value = s;
                        }
                        if (endInput) {
                            endInput.disabled = false;
                            endInput.value = e;
                        }
                    } else {
                        if (monthInput) {
                            monthInput.disabled = false;
                            monthInput.value = toYm(state.leftY, state.leftM);
                        }
                        if (startInput) startInput.disabled = true;
                        if (endInput) endInput.disabled = true;
                    }
                }
            } else {
                const targetMonth = state.selectedMonth || toYm(state.leftY, state.leftM);
                if (monthInput) {
                    monthInput.disabled = false;
                    monthInput.value = targetMonth;
                }
                if (dayInput) {
                    if (isDashboardDayAction && state.selectedDay !== null) {
                        dayInput.disabled = false;
                        dayInput.value = String(state.selectedDay);
                    } else {
                        dayInput.disabled = true;
                        dayInput.value = '';
                    }
                }
            }

            closePanel(false);
            form.submit();
        }

        // Eventos del botón trigger
        trigger.addEventListener('click', () => {
            if (isOpen()) {
                closePanel(false);
            } else {
                openPanel();
            }
        });

        // Eventos delegados dentro del panel popover
        panel.addEventListener('click', (e) => {
            const actionBtn = e.target.closest('[data-dp-action-btn]');
            if (actionBtn) {
                const act = actionBtn.getAttribute('data-dp-action-btn');
                if (act === 'prev-month') {
                    const moved = shiftMonth(state.leftY, state.leftM, -1);
                    state.leftY = moved.y;
                    state.leftM = moved.m;
                    state.yearWindowCenter = moved.y;
                    render();
                } else if (act === 'next-month') {
                    const moved = shiftMonth(state.leftY, state.leftM, 1);
                    state.leftY = moved.y;
                    state.leftM = moved.m;
                    state.yearWindowCenter = moved.y;
                    render();
                } else if (act === 'years-up') {
                    state.yearWindowCenter = clampYear(state.yearWindowCenter - 7);
                    render();
                } else if (act === 'years-down') {
                    state.yearWindowCenter = clampYear(state.yearWindowCenter + 7);
                    render();
                } else if (act === 'go-year') {
                    const inp = panel.querySelector('[data-dp-year-input]');
                    const typed = clampYear(parseInt(inp ? inp.value : '', 10));
                    state.leftY = typed;
                    state.yearWindowCenter = typed;
                    if (state.mode === 'month') {
                        state.selectedMonth = toYm(state.leftY, state.leftM);
                        state.selectedDay = null;
                    }
                    render();
                } else if (act === 'today') {
                    applyPreset('today');
                } else if (act === 'cancel') {
                    closePanel(true);
                } else if (act === 'apply') {
                    submitSelection();
                }
                return;
            }

            const presetBtn = e.target.closest('[data-dp-preset]');
            if (presetBtn) {
                applyPreset(presetBtn.getAttribute('data-dp-preset'));
                return;
            }

            const yearBtn = e.target.closest('[data-dp-year]');
            if (yearBtn) {
                const yr = clampYear(parseInt(yearBtn.getAttribute('data-dp-year'), 10));
                state.leftY = yr;
                state.yearWindowCenter = yr;
                state.activePreset = '';
                if (state.mode === 'month') {
                    state.selectedMonth = toYm(yr, state.leftM);
                    state.selectedDay = null;
                }
                render();
                return;
            }

            const monthChipBtn = e.target.closest('[data-dp-month-chip]');
            if (monthChipBtn) {
                const mNum = parseInt(monthChipBtn.getAttribute('data-dp-month-chip'), 10);
                state.leftM = mNum;
                state.activePreset = '';
                if (state.mode === 'month') {
                    state.selectedMonth = toYm(state.leftY, mNum);
                    state.selectedDay = null;
                } else {
                    state.isAll = false;
                    state.rangeStart = toIso(state.leftY, mNum, 1);
                    state.rangeEnd = toIso(state.leftY, mNum, daysInMonth(state.leftY, mNum));
                }
                render();
                return;
            }

            const titleMonthBtn = e.target.closest('[data-dp-select-month]');
            if (titleMonthBtn) {
                const ymStr = titleMonthBtn.getAttribute('data-dp-select-month');
                const ym = parseYm(ymStr);
                if (!ym) return;
                state.activePreset = '';
                if (state.mode === 'month') {
                    state.selectedMonth = ymStr;
                    state.selectedDay = null;
                } else {
                    state.isAll = false;
                    state.rangeStart = toIso(ym.y, ym.m, 1);
                    state.rangeEnd = toIso(ym.y, ym.m, daysInMonth(ym.y, ym.m));
                }
                render();
                return;
            }

            const dayBtn = e.target.closest('.cc-dp-day[data-dp-iso]');
            if (dayBtn) {
                const iso = dayBtn.getAttribute('data-dp-iso');
                const ymStr = dayBtn.getAttribute('data-dp-ym');
                const dNum = parseInt(dayBtn.getAttribute('data-dp-day'), 10);
                state.activePreset = '';

                if (state.mode === 'month') {
                    state.selectedMonth = ymStr;
                    state.selectedDay = dNum;
                    const parsed = parseYm(ymStr);
                    if (parsed) {
                        state.leftY = parsed.y;
                        state.leftM = parsed.m;
                        state.yearWindowCenter = parsed.y;
                    }
                    render();
                } else {
                    state.isAll = false;
                    if (!state.rangeStart || (state.rangeStart && state.rangeEnd)) {
                        state.rangeStart = iso;
                        state.rangeEnd = '';
                        state.hoverIso = iso;
                    } else {
                        if (iso < state.rangeStart) {
                            state.rangeEnd = state.rangeStart;
                            state.rangeStart = iso;
                        } else {
                            state.rangeEnd = iso;
                        }
                        state.hoverIso = null;
                    }
                    render();
                }
            }
        });

        // Doble clic en un día o mes en modo simple aplica de una vez
        panel.addEventListener('dblclick', (e) => {
            if (state.mode === 'month' && e.target.closest('.cc-dp-day[data-dp-iso], [data-dp-select-month]')) {
                submitSelection();
            }
        });

        // Enter dentro del input de año salta de inmediato
        panel.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && e.target.matches('[data-dp-year-input]')) {
                e.preventDefault();
                const typed = clampYear(parseInt(e.target.value, 10));
                state.leftY = typed;
                state.yearWindowCenter = typed;
                if (state.mode === 'month') {
                    state.selectedMonth = toYm(state.leftY, state.leftM);
                    state.selectedDay = null;
                }
                render();
            }
        });

        // Hover preview para rango
        panel.addEventListener('pointerover', (e) => {
            if (state.mode !== 'range' || !state.rangeStart || state.rangeEnd) return;
            const dayBtn = e.target.closest('.cc-dp-day[data-dp-iso]');
            if (!dayBtn) return;
            const iso = dayBtn.getAttribute('data-dp-iso');
            if (iso && iso !== state.hoverIso) {
                state.hoverIso = iso;
                updateRangePreviewDom();
            }
        });

        if (hasNativePopover) {
            panel.addEventListener('toggle', (e) => {
                trigger.setAttribute('aria-expanded', e.newState === 'open' ? 'true' : 'false');
                if (e.newState === 'open') {
                    positionPanel();
                }
            });
        } else {
            document.addEventListener('click', (e) => {
                if (isOpen() && !panel.contains(e.target) && !trigger.contains(e.target)) {
                    closePanel(false);
                }
            });
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && isOpen()) {
                    closePanel(true);
                }
            });
        }

        window.addEventListener('resize', () => {
            if (isOpen()) positionPanel();
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('[data-cc-dp]').forEach(initDatePicker);
    });
})();
