/**
 * CuentaClara — Calendar Module Controller
 * Maneja vista mensual (días adyacentes), panel lateral/bottom sheet de día,
 * popover anclado de registro rápido, vistas Semana/Día y navegación por teclado.
 */
(function () {
    const shell = document.getElementById('calendario-mes');
    if (!shell) return;

    const monthGrid = document.getElementById('cc-month-grid');
    const dayPanel = document.getElementById('cc-day-panel');
    const sheetBackdrop = document.getElementById('cc-sheet-backdrop');
    const quickPopover = document.getElementById('cc-quick-popover');
    const quickForm = document.getElementById('cc-quick-form');

    const selectedMonth = shell.getAttribute('data-selected-month') || '';
    const selectedYear = parseInt(shell.getAttribute('data-selected-year'), 10) || new Date().getFullYear();
    const selectedMonthNum = parseInt(shell.getAttribute('data-selected-month-num'), 10) || (new Date().getMonth() + 1);
    const todayIso = shell.getAttribute('data-today-iso') || '';

    let activeDayNum = null;
    let activeView = 'month';
    let lastTriggerEl = null;

    function formatCurrency(val) {
        if (window.CuentaClaraTheme && typeof window.CuentaClaraTheme.formatAmount === 'function') {
            return window.CuentaClaraTheme.formatAmount(val);
        }
        const num = Number(val) || 0;
        return '$ ' + num.toLocaleString('es-CO', { maximumFractionDigits: 0 }) + ' COP';
    }

    function escapeHtml(str) {
        return String(str || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function cleanDisplayTitle(raw) {
        const original = String(raw || '').trim();
        if (!original) return '';
        let cleaned = original
            .replace(/\b(S\.?\s*A\.?\s*S\.?|S\.?\s*A\.?|LTDA\.?|CIA\.?|INC\.?|CORP\.?)\b/gi, '')
            .replace(/\s{2,}/g, ' ')
            .replace(/[.,\-\s]+$/, '')
            .trim();
        if (!cleaned) cleaned = original;
        if (cleaned === cleaned.toUpperCase() && cleaned.length > 3) {
            cleaned = cleaned.toLowerCase().replace(/\b\p{L}/gu, c => c.toUpperCase());
        }
        return cleaned;
    }

    // Leer todos los datos del mes renderizados por Thymeleaf en #day-data-N
    function readDayData(dayNum) {
        const dataEl = document.getElementById('day-data-' + dayNum);
        if (!dataEl) return null;

        const obligations = Array.from(dataEl.querySelectorAll('.day-ob-item')).map(el => ({
            name: el.getAttribute('data-name') || '',
            amount: Number(el.getAttribute('data-amount')) || 0,
            category: el.getAttribute('data-category') || '',
            block: el.getAttribute('data-block') || 'NEEDS',
            paid: el.getAttribute('data-paid') === 'true'
        }));

        const transactions = Array.from(dataEl.querySelectorAll('.day-tx-item')).map(el => ({
            merchant: el.getAttribute('data-merchant') || '',
            amount: Number(el.getAttribute('data-amount')) || 0,
            category: el.getAttribute('data-category') || '',
            desc: el.getAttribute('data-desc') || ''
        }));

        return {
            dayNumber: parseInt(dataEl.getAttribute('data-day'), 10) || dayNum,
            iso: dataEl.getAttribute('data-iso') || '',
            label: dataEl.getAttribute('data-label') || '',
            weekday: dataEl.getAttribute('data-weekday') || '',
            today: dataEl.getAttribute('data-today') === 'true',
            total: Number(dataEl.getAttribute('data-total')) || 0,
            obligations,
            transactions
        };
    }

    function getAllMonthDays() {
        const daysInMonth = new Date(selectedYear, selectedMonthNum, 0).getDate();
        const list = [];
        for (let d = 1; d <= daysInMonth; d++) {
            const data = readDayData(d);
            if (data) list.push(data);
        }
        return list;
    }

    // Rellenar números grises de mes anterior y días finales del mes siguiente para cuadrícula rectangular limpia
    function populateOutsideMonthDays() {
        if (!monthGrid) return;
        const leadingCells = monthGrid.querySelectorAll('.cc-leading-outside');
        const prevMonthLastDay = new Date(selectedYear, selectedMonthNum - 1, 0).getDate();
        const leadingCount = leadingCells.length;

        leadingCells.forEach((cell, idx) => {
            const num = prevMonthLastDay - leadingCount + idx + 1;
            const span = cell.querySelector('.cc-day-num');
            if (span) span.textContent = String(num);
        });

        // Quitar celdas trailing previas si existieran
        monthGrid.querySelectorAll('.cc-trailing-outside').forEach(el => el.remove());

        const totalCells = monthGrid.children.length;
        const remainder = totalCells % 7;
        const trailingCount = remainder === 0 ? 0 : (7 - remainder);

        for (let i = 1; i <= trailingCount; i++) {
            const cell = document.createElement('div');
            cell.className = 'cc-day-cell is-outside cc-trailing-outside';
            cell.setAttribute('aria-hidden', 'true');
            cell.innerHTML = `<div class="cc-day-top"><span class="cc-day-num">${i}</span></div>`;
            monthGrid.appendChild(cell);
        }
    }

    // Marcar celda seleccionada
    function highlightSelectedCell(dayNum) {
        if (!monthGrid) return;
        monthGrid.querySelectorAll('.cc-day-cell.is-selected').forEach(c => c.classList.remove('is-selected'));
        const target = monthGrid.querySelector(`.cc-day-cell[data-day="${dayNum}"]`);
        if (target) {
            target.classList.add('is-selected');
        }
    }

    // Abrir Panel Lateral (desktop) o Bottom Sheet (móvil) con detalle del día
    function openDayPanel(dayNum) {
        const data = readDayData(dayNum);
        if (!data || !dayPanel) return;

        activeDayNum = dayNum;
        highlightSelectedCell(dayNum);

        const titleEl = document.getElementById('cc-panel-date-title');
        const totalEl = document.getElementById('cc-panel-day-total');
        const obSection = document.getElementById('cc-panel-ob-section');
        const obList = document.getElementById('cc-panel-ob-list');
        const txList = document.getElementById('cc-panel-tx-list');
        const addBtn = document.getElementById('cc-panel-add-btn');

        if (titleEl) titleEl.textContent = data.label;
        if (totalEl) {
            totalEl.textContent = data.total > 0 ? ('Total del día: ' + formatCurrency(data.total)) : 'Sin gastos registrados';
        }

        if (obList && obSection) {
            obList.innerHTML = '';
            if (data.obligations.length > 0) {
                obSection.classList.remove('hidden');
                data.obligations.forEach(ob => {
                    const row = document.createElement('div');
                    row.className = 'cc-panel-item ' + (ob.paid ? 'is-paid' : 'is-pending');
                    row.innerHTML = `
                        <div class="flex flex-col min-w-0">
                            <span class="text-xs font-bold text-on-surface truncate">${escapeHtml(ob.name)}</span>
                            <span class="text-[11px] text-on-surface-variant">${escapeHtml(ob.category)} · ${formatCurrency(ob.amount)}</span>
                        </div>
                    `;
                    if (!ob.paid) {
                        const payBtn = document.createElement('button');
                        payBtn.type = 'button';
                        payBtn.className = 'px-2.5 py-1.5 rounded-lg bg-primary-container text-on-primary text-[11px] font-semibold border-0 cursor-pointer flex-shrink-0';
                        payBtn.textContent = 'Registrar';
                        payBtn.addEventListener('click', (e) => {
                            e.stopPropagation();
                            openQuickPopover(dayNum, payBtn, {
                                merchant: ob.name,
                                amount: Math.round(ob.amount),
                                category: ob.category,
                                block: ob.block || 'NEEDS',
                                desc: 'Pago mensual de ' + ob.name
                            });
                        });
                        row.appendChild(payBtn);
                    } else {
                        const badge = document.createElement('span');
                        badge.className = 'px-2 py-0.5 rounded-md bg-emerald-600 text-white text-[10px] font-bold flex-shrink-0';
                        badge.textContent = 'Al día';
                        row.appendChild(badge);
                    }
                    obList.appendChild(row);
                });
            } else {
                obSection.classList.add('hidden');
            }
        }

        if (txList) {
            txList.innerHTML = '';
            if (data.transactions.length === 0) {
                txList.innerHTML = '<div class="p-3 rounded-xl bg-surface-container-low text-xs text-on-surface-variant text-center">No hay movimientos en este día.</div>';
            } else {
                data.transactions.forEach(tx => {
                    const card = document.createElement('div');
                    card.className = 'cc-panel-tx-card';
                    card.setAttribute('role', 'button');
                    card.setAttribute('tabindex', '0');
                    card.setAttribute('aria-expanded', 'false');
                    card.innerHTML = `
                        <div class="cc-panel-tx-summary">
                            <div class="flex flex-col min-w-0">
                                <span class="text-xs font-bold text-primary truncate">${escapeHtml(tx.merchant)}</span>
                                <span class="text-[11px] text-on-surface-variant truncate">${escapeHtml(tx.category)}</span>
                            </div>
                            <div class="flex items-center gap-1.5 flex-shrink-0">
                                <span class="text-xs font-extrabold text-secondary">${formatCurrency(tx.amount)}</span>
                                <span class="material-symbols-outlined text-[16px] text-on-surface-variant transition-transform duration-150">expand_more</span>
                            </div>
                        </div>
                        <div class="cc-panel-tx-details">
                            <div class="flex items-center justify-between gap-2">
                                <span class="font-semibold">Categoría</span>
                                <span>${escapeHtml(tx.category || 'General')}</span>
                            </div>
                            <div class="flex items-center justify-between gap-2">
                                <span class="font-semibold">Detalle</span>
                                <span class="text-right truncate">${escapeHtml(tx.desc || 'Sin nota adicional')}</span>
                            </div>
                        </div>
                    `;
                    const toggleCard = () => {
                        const expanded = card.classList.toggle('is-expanded');
                        card.setAttribute('aria-expanded', expanded ? 'true' : 'false');
                        const icon = card.querySelector('.material-symbols-outlined');
                        if (icon) icon.style.transform = expanded ? 'rotate(180deg)' : '';
                    };
                    card.addEventListener('click', toggleCard);
                    card.addEventListener('keydown', (e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            toggleCard();
                        }
                    });
                    txList.appendChild(card);
                });
            }
        }

        if (addBtn) {
            addBtn.onclick = (e) => {
                e.stopPropagation();
                openQuickPopover(dayNum, addBtn);
            };
        }

        dayPanel.classList.remove('hidden');
        if (window.innerWidth < 1280 && sheetBackdrop) {
            sheetBackdrop.classList.add('is-open');
        }

        if (window.CuentaClaraTheme && typeof window.CuentaClaraTheme.refreshLocalization === 'function') {
            window.CuentaClaraTheme.refreshLocalization();
        }
    }

    function closeDayPanel() {
        if (dayPanel) dayPanel.classList.add('hidden');
        if (sheetBackdrop) sheetBackdrop.classList.remove('is-open');
        if (monthGrid) {
            monthGrid.querySelectorAll('.cc-day-cell.is-selected').forEach(c => c.classList.remove('is-selected'));
        }
        activeDayNum = null;
    }

    // Popover de registro rápido anclado al disparador (celda o botón)
    function openQuickPopover(dayNum, anchorEl, preset) {
        const data = readDayData(dayNum);
        if (!data || !quickPopover || !quickForm) return;

        lastTriggerEl = anchorEl || null;
        activeDayNum = dayNum;
        highlightSelectedCell(dayNum);

        document.getElementById('cc-quick-date-label').textContent = data.label;
        document.getElementById('cc-quick-input-date').value = data.iso;
        document.getElementById('cc-quick-merchant').value = (preset && preset.merchant) ? preset.merchant : '';
        document.getElementById('cc-quick-amount').value = (preset && preset.amount) ? preset.amount : '';
        document.getElementById('cc-quick-desc').value = (preset && preset.desc) ? preset.desc : '';

        const catSelect = document.getElementById('cc-quick-category');
        if (catSelect && preset && preset.category) {
            catSelect.value = preset.category;
        }
        const blockSelect = document.getElementById('cc-quick-block');
        if (blockSelect && preset && preset.block) {
            blockSelect.value = preset.block;
        }

        // Mostrar temporalmente para medir y posicionar dentro del viewport
        quickPopover.classList.remove('hidden');
        const popW = quickPopover.offsetWidth || 340;
        const popH = quickPopover.offsetHeight || 360;
        const vw = window.innerWidth;
        const vh = window.innerHeight;
        const margin = 12;

        let left = (vw - popW) / 2;
        let top = (vh - popH) / 2;
        let origin = 'center center';

        if (anchorEl && vw > 640) {
            const rect = anchorEl.getBoundingClientRect();
            const spaceRight = vw - rect.right - margin;
            const spaceLeft = rect.left - margin;

            if (spaceRight >= popW) {
                left = rect.right + 10;
                origin = 'left top';
            } else if (spaceLeft >= popW) {
                left = rect.left - popW - 10;
                origin = 'right top';
            } else {
                left = Math.max(margin, Math.min(vw - popW - margin, rect.left));
                origin = 'top center';
            }

            top = rect.top;
            if (top + popH > vh - margin) {
                top = Math.max(margin, vh - popH - margin);
                origin = origin.replace('top', 'bottom');
            }
            if (top < margin) top = margin;
        }

        quickPopover.style.left = Math.round(left) + 'px';
        quickPopover.style.top = Math.round(top) + 'px';
        quickPopover.style.setProperty('--popover-origin', origin);

        const merchantInput = document.getElementById('cc-quick-merchant');
        if (merchantInput) {
            setTimeout(() => merchantInput.focus(), 40);
        }

        if (window.CuentaClaraTheme && typeof window.CuentaClaraTheme.refreshLocalization === 'function') {
            window.CuentaClaraTheme.refreshLocalization();
        }
    }

    function closeQuickPopover() {
        if (!quickPopover) return;
        quickPopover.classList.add('hidden');
        if (lastTriggerEl && typeof lastTriggerEl.focus === 'function') {
            lastTriggerEl.focus();
        }
    }

    // Delegación de eventos en la cuadrícula mensual
    if (monthGrid) {
        monthGrid.addEventListener('click', (e) => {
            const addBtn = e.target.closest('[data-quick-add-day]');
            if (addBtn) {
                e.stopPropagation();
                const dayNum = parseInt(addBtn.getAttribute('data-quick-add-day'), 10);
                if (dayNum) openQuickPopover(dayNum, addBtn);
                return;
            }
            const cell = e.target.closest('.cc-day-cell[data-day]');
            if (cell) {
                const dayNum = parseInt(cell.getAttribute('data-day'), 10);
                if (dayNum) openDayPanel(dayNum);
            }
        });
    }

    // Botones rápidos de monto en el popover
    document.querySelectorAll('[data-quick-delta]').forEach(btn => {
        btn.addEventListener('click', () => {
            const delta = parseInt(btn.getAttribute('data-quick-delta'), 10) || 0;
            const amtInput = document.getElementById('cc-quick-amount');
            if (!amtInput) return;
            const current = parseInt(amtInput.value, 10) || 0;
            amtInput.value = current + delta;
            amtInput.focus();
        });
    });

    const closePanelBtn = document.getElementById('cc-panel-close-btn');
    if (closePanelBtn) closePanelBtn.addEventListener('click', closeDayPanel);

    const closePopoverBtn = document.getElementById('cc-quick-close-btn');
    if (closePopoverBtn) closePopoverBtn.addEventListener('click', closeQuickPopover);

    if (sheetBackdrop) {
        sheetBackdrop.addEventListener('click', () => {
            closeDayPanel();
            closeQuickPopover();
        });
    }

    // Cerrar popover al hacer clic fuera
    document.addEventListener('click', (e) => {
        if (quickPopover && !quickPopover.classList.contains('hidden')) {
            if (!quickPopover.contains(e.target)
                && !e.target.closest('[data-quick-add-day]')
                && !e.target.closest('#cc-panel-add-btn')
                && !e.target.closest('[data-day-view-add]')) {
                closeQuickPopover();
            }
        }
        const jumpDetails = document.getElementById('cc-month-jump-details');
        if (jumpDetails && jumpDetails.open && !jumpDetails.contains(e.target)) {
            jumpDetails.open = false;
        }
    });

    // Tecla Escape cierra popover o panel
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (quickPopover && !quickPopover.classList.contains('hidden')) {
                closeQuickPopover();
            } else if (dayPanel && !dayPanel.classList.contains('hidden')) {
                closeDayPanel();
            }
        }
    });

    // =========================================================================
    // PASO 5: Selector Mes / Semana / Día + Vista Semanal y Vista Diaria
    // =========================================================================
    const viewMonthEl = document.getElementById('cc-view-month');
    const viewWeekEl = document.getElementById('cc-view-week');
    const viewDayEl = document.getElementById('cc-view-day');
    const viewTabs = document.querySelectorAll('.cc-segmented-btn[data-view]');
    const navPrevBtn = document.getElementById('cc-nav-prev');
    const navTodayBtn = document.getElementById('cc-nav-today');
    const navNextBtn = document.getElementById('cc-nav-next');

    const WEEKDAY_SHORT = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];
    const daysInCurrentMonth = new Date(selectedYear, selectedMonthNum, 0).getDate();

    function getDefaultAnchorDay() {
        if (activeDayNum && activeDayNum >= 1 && activeDayNum <= daysInCurrentMonth) {
            return activeDayNum;
        }
        const todayCell = monthGrid ? monthGrid.querySelector('.cc-day-cell.is-today[data-day]') : null;
        if (todayCell) {
            return parseInt(todayCell.getAttribute('data-day'), 10) || 1;
        }
        return 1;
    }

    function getMondayOffset(year, monthNum, day) {
        const jsDay = new Date(year, monthNum - 1, day).getDay(); // 0=Dom .. 6=Sab
        return jsDay === 0 ? 6 : jsDay - 1; // 0=Lun .. 6=Dom
    }

    function renderWeekView(anchorDay) {
        if (!viewWeekEl) return;
        const clampedDay = Math.max(1, Math.min(daysInCurrentMonth, anchorDay));
        activeDayNum = clampedDay;

        const mondayOffset = getMondayOffset(selectedYear, selectedMonthNum, clampedDay);
        const startDay = clampedDay - mondayOffset; // puede ser <= 0 o > daysInCurrentMonth

        const grid = document.createElement('div');
        grid.className = 'cc-week-grid';

        for (let i = 0; i < 7; i++) {
            const d = startDay + i;
            const dateObj = new Date(selectedYear, selectedMonthNum - 1, d);
            const inMonth = (dateObj.getFullYear() === selectedYear && (dateObj.getMonth() + 1) === selectedMonthNum);
            const dayData = inMonth ? readDayData(d) : null;

            const card = document.createElement('div');
            card.className = 'cc-week-card' + (!inMonth ? ' is-outside' : '') + (dayData && dayData.today ? ' is-today' : '');

            const head = document.createElement('div');
            head.className = 'cc-week-card-head';
            head.innerHTML = `
                <div class="cc-week-day-info">
                    <span class="cc-week-weekday">${WEEKDAY_SHORT[i]}</span>
                    <span class="cc-day-num">${dateObj.getDate()}</span>
                </div>
            `;

            if (inMonth && dayData) {
                const addBtn = document.createElement('button');
                addBtn.type = 'button';
                addBtn.className = 'cc-icon-btn';
                addBtn.setAttribute('data-quick-add-day', String(d));
                addBtn.setAttribute('title', 'Registrar gasto');
                addBtn.setAttribute('aria-label', 'Registrar gasto el día ' + d);
                addBtn.innerHTML = '<span class="material-symbols-outlined text-[18px]">add</span>';
                addBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    openQuickPopover(d, addBtn);
                });
                head.appendChild(addBtn);
            }
            card.appendChild(head);

            const itemsWrap = document.createElement('div');
            itemsWrap.className = 'cc-week-items';

            if (dayData) {
                dayData.obligations.forEach(ob => {
                    const chip = document.createElement('div');
                    chip.className = 'cc-chip ' + (ob.paid ? 'cc-chip-paid' : 'cc-chip-pending');
                    chip.innerHTML = `<span class="cc-chip-title">${escapeHtml(ob.name)}</span><span class="cc-chip-amt">${formatCurrency(ob.amount)}</span>`;
                    itemsWrap.appendChild(chip);
                });

                dayData.transactions.forEach(tx => {
                    const chip = document.createElement('div');
                    chip.className = 'cc-chip cc-chip-expense';
                    chip.innerHTML = `<span class="cc-chip-title">${escapeHtml(tx.merchant)}</span><span class="cc-chip-amt">${formatCurrency(tx.amount)}</span>`;
                    itemsWrap.appendChild(chip);
                });

                card.style.cursor = 'pointer';
                card.addEventListener('click', () => openDayPanel(d));
            }
            card.appendChild(itemsWrap);

            if (dayData && dayData.total > 0) {
                const foot = document.createElement('div');
                foot.className = 'cc-week-total';
                foot.innerHTML = `<span>Total</span><span>${formatCurrency(dayData.total)}</span>`;
                card.appendChild(foot);
            }

            grid.appendChild(card);
        }

        viewWeekEl.innerHTML = '';
        viewWeekEl.appendChild(grid);
        if (window.CuentaClaraTheme && typeof window.CuentaClaraTheme.refreshLocalization === 'function') {
            window.CuentaClaraTheme.refreshLocalization();
        }
    }

    function renderDayView(dayNum) {
        if (!viewDayEl) return;
        const clampedDay = Math.max(1, Math.min(daysInCurrentMonth, dayNum));
        const data = readDayData(clampedDay);
        if (!data) return;
        activeDayNum = clampedDay;

        const container = document.createElement('div');
        container.className = 'cc-day-focus';

        const head = document.createElement('div');
        head.className = 'cc-day-focus-head';
        head.innerHTML = `
            <div>
                <h2 class="text-lg font-extrabold text-primary m-0">${escapeHtml(data.label)}</h2>
                <span class="text-xs font-semibold text-secondary">${data.total > 0 ? ('Total del día: ' + formatCurrency(data.total)) : 'Sin gastos registrados'}</span>
            </div>
        `;
        const addBtn = document.createElement('button');
        addBtn.type = 'button';
        addBtn.setAttribute('data-day-view-add', String(clampedDay));
        addBtn.className = 'cc-primary-btn';
        addBtn.style.width = 'auto';
        addBtn.innerHTML = '<span class="material-symbols-outlined text-[18px]">add</span><span>Registrar gasto</span>';
        addBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            openQuickPopover(clampedDay, addBtn);
        });
        head.appendChild(addBtn);
        container.appendChild(head);

        if (data.obligations.length > 0) {
            const obWrap = document.createElement('div');
            obWrap.innerHTML = '<span class="cc-panel-section-label">Pagos fijos del día</span>';
            const list = document.createElement('div');
            list.className = 'flex flex-col gap-2';
            data.obligations.forEach(ob => {
                const row = document.createElement('div');
                row.className = 'cc-panel-item ' + (ob.paid ? 'is-paid' : 'is-pending');
                row.innerHTML = `
                    <div class="flex flex-col min-w-0">
                        <span class="text-xs font-bold text-on-surface">${escapeHtml(ob.name)}</span>
                        <span class="text-[11px] text-on-surface-variant">${escapeHtml(ob.category)} · ${formatCurrency(ob.amount)}</span>
                    </div>
                `;
                if (!ob.paid) {
                    const payBtn = document.createElement('button');
                    payBtn.type = 'button';
                    payBtn.className = 'px-3 py-1.5 rounded-lg bg-primary-container text-on-primary text-xs font-semibold border-0 cursor-pointer';
                    payBtn.textContent = 'Registrar pago';
                    payBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        openQuickPopover(clampedDay, payBtn, {
                            merchant: ob.name,
                            amount: Math.round(ob.amount),
                            category: ob.category,
                            block: ob.block || 'NEEDS',
                            desc: 'Pago mensual de ' + ob.name
                        });
                    });
                    row.appendChild(payBtn);
                } else {
                    const badge = document.createElement('span');
                    badge.className = 'px-2.5 py-0.5 rounded-md bg-emerald-600 text-white text-[11px] font-bold';
                    badge.textContent = 'Al día';
                    row.appendChild(badge);
                }
                list.appendChild(row);
            });
            obWrap.appendChild(list);
            container.appendChild(obWrap);
        }

        const txWrap = document.createElement('div');
        txWrap.innerHTML = '<span class="cc-panel-section-label">Movimientos registrados</span>';
        const txList = document.createElement('div');
        txList.className = 'flex flex-col gap-2';
        if (data.transactions.length === 0) {
            txList.innerHTML = '<div class="p-4 rounded-xl bg-surface-container-low text-xs text-on-surface-variant text-center">No hay movimientos en este día.</div>';
        } else {
            data.transactions.forEach(tx => {
                const row = document.createElement('div');
                row.className = 'cc-panel-item';
                row.innerHTML = `
                    <div class="flex flex-col min-w-0">
                        <span class="text-xs font-bold text-primary">${escapeHtml(tx.merchant)}</span>
                        <span class="text-[11px] text-on-surface-variant">${escapeHtml(tx.category)}${tx.desc ? ' · ' + escapeHtml(tx.desc) : ''}</span>
                    </div>
                    <span class="text-xs font-extrabold text-secondary flex-shrink-0">${formatCurrency(tx.amount)}</span>
                `;
                txList.appendChild(row);
            });
        }
        txWrap.appendChild(txList);
        container.appendChild(txWrap);

        viewDayEl.innerHTML = '';
        viewDayEl.appendChild(container);
        if (window.CuentaClaraTheme && typeof window.CuentaClaraTheme.refreshLocalization === 'function') {
            window.CuentaClaraTheme.refreshLocalization();
        }
    }

    function animateViewPanel(el) {
        if (!el) return;
        el.classList.remove('is-entering');
        void el.offsetWidth;
        el.classList.add('is-entering');
    }

    function setView(nextView) {
        if (!['month', 'week', 'day'].includes(nextView)) nextView = 'month';
        activeView = nextView;
        try { sessionStorage.setItem('cuentaclara_cal_view', nextView); } catch (_) {}

        viewTabs.forEach(tab => {
            tab.setAttribute('aria-selected', tab.getAttribute('data-view') === nextView ? 'true' : 'false');
        });

        closeQuickPopover();
        if (nextView === 'day') {
            closeDayPanel();
        }

        if (viewMonthEl) viewMonthEl.classList.toggle('hidden', nextView !== 'month');
        if (viewWeekEl) viewWeekEl.classList.toggle('hidden', nextView !== 'week');
        if (viewDayEl) viewDayEl.classList.toggle('hidden', nextView !== 'day');

        const anchor = getDefaultAnchorDay();
        if (nextView === 'month') {
            animateViewPanel(viewMonthEl);
        } else if (nextView === 'week') {
            renderWeekView(anchor);
            animateViewPanel(viewWeekEl);
        } else if (nextView === 'day') {
            renderDayView(anchor);
            animateViewPanel(viewDayEl);
        }
    }

    viewTabs.forEach(tab => {
        tab.addEventListener('click', () => setView(tab.getAttribute('data-view')));
    });

    // Navegación Anterior / Hoy / Siguiente adaptada a la vista activa
    if (navPrevBtn) {
        navPrevBtn.addEventListener('click', (e) => {
            if (activeView === 'week') {
                const targetDay = getDefaultAnchorDay() - 7;
                if (targetDay >= 1) {
                    e.preventDefault();
                    renderWeekView(targetDay);
                    animateViewPanel(viewWeekEl);
                }
            } else if (activeView === 'day') {
                const targetDay = getDefaultAnchorDay() - 1;
                if (targetDay >= 1) {
                    e.preventDefault();
                    renderDayView(targetDay);
                    animateViewPanel(viewDayEl);
                }
            }
        });
    }

    if (navNextBtn) {
        navNextBtn.addEventListener('click', (e) => {
            if (activeView === 'week') {
                const targetDay = getDefaultAnchorDay() + 7;
                if (targetDay <= daysInCurrentMonth) {
                    e.preventDefault();
                    renderWeekView(targetDay);
                    animateViewPanel(viewWeekEl);
                }
            } else if (activeView === 'day') {
                const targetDay = getDefaultAnchorDay() + 1;
                if (targetDay <= daysInCurrentMonth) {
                    e.preventDefault();
                    renderDayView(targetDay);
                    animateViewPanel(viewDayEl);
                }
            }
        });
    }

    if (navTodayBtn) {
        navTodayBtn.addEventListener('click', (e) => {
            const currentRealMonth = shell.getAttribute('data-current-real-month');
            if (currentRealMonth && currentRealMonth === selectedMonth) {
                const todayCell = monthGrid ? monthGrid.querySelector('.cc-day-cell.is-today[data-day]') : null;
                if (todayCell) {
                    e.preventDefault();
                    const todayDay = parseInt(todayCell.getAttribute('data-day'), 10);
                    if (activeView === 'week') {
                        renderWeekView(todayDay);
                        animateViewPanel(viewWeekEl);
                    } else if (activeView === 'day') {
                        renderDayView(todayDay);
                        animateViewPanel(viewDayEl);
                    } else {
                        openDayPanel(todayDay);
                    }
                }
            }
        });
    }

    // =========================================================================
    // PASO 6: Navegación con flechas del teclado y transición direccional de mes
    // =========================================================================
    if (monthGrid) {
        monthGrid.addEventListener('keydown', (e) => {
            const cell = e.target.closest('.cc-day-cell[data-day]');
            if (!cell) return;
            const currentDay = parseInt(cell.getAttribute('data-day'), 10);
            if (!currentDay) return;

            let nextDay = null;
            if (e.key === 'ArrowRight') nextDay = currentDay + 1;
            else if (e.key === 'ArrowLeft') nextDay = currentDay - 1;
            else if (e.key === 'ArrowDown') nextDay = currentDay + 7;
            else if (e.key === 'ArrowUp') nextDay = currentDay - 7;
            else if (e.key === 'Home') nextDay = 1;
            else if (e.key === 'End') nextDay = daysInCurrentMonth;
            else if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                openDayPanel(currentDay);
                return;
            } else if (e.key === '+') {
                e.preventDefault();
                openQuickPopover(currentDay, cell);
                return;
            }

            if (nextDay !== null) {
                e.preventDefault();
                const clamped = Math.max(1, Math.min(daysInCurrentMonth, nextDay));
                const nextCell = monthGrid.querySelector(`.cc-day-cell[data-day="${clamped}"]`);
                if (nextCell) nextCell.focus();
            }
        });
    }

    if (navPrevBtn) {
        navPrevBtn.addEventListener('click', (e) => {
            if (!e.defaultPrevented) {
                try { sessionStorage.setItem('cuentaclara_cal_dir', 'prev'); } catch (_) {}
            }
        });
    }
    if (navNextBtn) {
        navNextBtn.addEventListener('click', (e) => {
            if (!e.defaultPrevented) {
                try { sessionStorage.setItem('cuentaclara_cal_dir', 'next'); } catch (_) {}
            }
        });
    }

    // Compatibilidad con llamadas globales
    window.CuentaClaraCalendar = {
        openDayPanel,
        closeDayPanel,
        openQuickPopover,
        closeQuickPopover,
        setView,
        getAllMonthDays,
        readDayData,
        formatCurrency,
        getState: () => ({ activeDayNum, activeView, selectedYear, selectedMonthNum, selectedMonth, todayIso })
    };

    // --- Filter Pills con conteos en vivo ---
    let activeCalFilter = 'all';

    function updateFilterCounts() {
        if (!monthGrid) return;
        const pendingCount = monthGrid.querySelectorAll('.cc-chip-pending').length;
        const paidCount = monthGrid.querySelectorAll('.cc-chip-paid').length;
        const expenseCount = monthGrid.querySelectorAll('.cc-chip-expense').length;
        const allCount = pendingCount + paidCount + expenseCount;

        const elAll = document.getElementById('cc-count-all');
        const elPending = document.getElementById('cc-count-pending');
        const elPaid = document.getElementById('cc-count-paid');
        const elExpense = document.getElementById('cc-count-expense');
        if (elAll) elAll.textContent = String(allCount);
        if (elPending) elPending.textContent = String(pendingCount);
        if (elPaid) elPaid.textContent = String(paidCount);
        if (elExpense) elExpense.textContent = String(expenseCount);
    }

    function applyCalendarFilter(filter) {
        activeCalFilter = filter || 'all';
        const pillButtons = document.querySelectorAll('#cc-filter-pills .cc-filter-pill[data-cal-filter]');
        pillButtons.forEach(btn => {
            const isMatch = btn.dataset.calFilter === activeCalFilter;
            btn.classList.toggle('is-active', isMatch);
            btn.setAttribute('aria-pressed', isMatch ? 'true' : 'false');
        });

        const chips = document.querySelectorAll('#cc-cal-view-month .cc-chip, #cc-cal-view-week .cc-chip');
        chips.forEach(chip => {
            if (activeCalFilter === 'all') {
                chip.classList.remove('is-dimmed');
                return;
            }
            const targetClass =
                activeCalFilter === 'pending' ? 'cc-chip-pending' :
                activeCalFilter === 'paid' ? 'cc-chip-paid' :
                'cc-chip-expense';
            chip.classList.toggle('is-dimmed', !chip.classList.contains(targetClass));
        });
    }

    document.querySelectorAll('#cc-filter-pills .cc-filter-pill[data-cal-filter]').forEach(btn => {
        btn.addEventListener('click', () => {
            applyCalendarFilter(btn.dataset.calFilter || 'all');
        });
    });

    function polishChipTitles() {
        document.querySelectorAll('.cc-chip').forEach(chip => {
            const titleEl = chip.querySelector('.cc-chip-title');
            if (!titleEl) return;
            const fullText = chip.getAttribute('title') || titleEl.textContent.trim();
            if (fullText) {
                chip.setAttribute('title', fullText);
                titleEl.textContent = cleanDisplayTitle(fullText);
            }
        });
    }

    populateOutsideMonthDays();
    polishChipTitles();
    updateFilterCounts();
    applyCalendarFilter(activeCalFilter);

    // Aplicar fundido + desplazamiento corto al cambiar de mes
    try {
        const navDir = sessionStorage.getItem('cuentaclara_cal_dir');
        if (navDir === 'next' && viewMonthEl) {
            viewMonthEl.classList.add('cc-enter-next');
        } else if (navDir === 'prev' && viewMonthEl) {
            viewMonthEl.classList.add('cc-enter-prev');
        }
        sessionStorage.removeItem('cuentaclara_cal_dir');
    } catch (_) {}

    // Restaurar vista elegida si el usuario navegó de mes en Semana/Día
    try {
        const savedView = sessionStorage.getItem('cuentaclara_cal_view');
        if (savedView === 'week' || savedView === 'day') {
            setView(savedView);
            applyCalendarFilter(activeCalFilter);
        }
    } catch (_) {}
})();
