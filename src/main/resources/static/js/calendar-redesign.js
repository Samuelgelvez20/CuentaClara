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
                    const row = document.createElement('div');
                    row.className = 'cc-panel-item';
                    row.innerHTML = `
                        <div class="flex flex-col min-w-0">
                            <span class="text-xs font-bold text-primary truncate">${escapeHtml(tx.merchant)}</span>
                            <span class="text-[11px] text-on-surface-variant truncate">${escapeHtml(tx.category)}${tx.desc ? ' · ' + escapeHtml(tx.desc) : ''}</span>
                        </div>
                        <span class="text-xs font-extrabold text-secondary flex-shrink-0">${formatCurrency(tx.amount)}</span>
                    `;
                    txList.appendChild(row);
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
        if (window.innerWidth <= 1024 && sheetBackdrop) {
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
            if (!quickPopover.contains(e.target) && !e.target.closest('[data-quick-add-day]') && !e.target.closest('#cc-panel-add-btn')) {
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

    // Compatibilidad con llamadas globales
    window.CuentaClaraCalendar = {
        openDayPanel,
        closeDayPanel,
        openQuickPopover,
        closeQuickPopover,
        getAllMonthDays,
        readDayData,
        formatCurrency,
        getState: () => ({ activeDayNum, activeView, selectedYear, selectedMonthNum, selectedMonth, todayIso })
    };

    populateOutsideMonthDays();
})();
