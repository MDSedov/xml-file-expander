const planForm = document.querySelector('#plan-form');
const fileInput = document.querySelector('#xml-file');
const fileLabel = document.querySelector('#file-label');
const planButton = document.querySelector('#plan-button');
const planWorking = document.querySelector('#plan-working');
const planError = document.querySelector('#plan-error');
const planPanel = document.querySelector('#plan-panel');
const jobPanel = document.querySelector('#job-panel');
const jobError = document.querySelector('#job-error');
const startButton = document.querySelector('#start-button');
const resetButton = document.querySelector('#reset-button');
const targetSizeGroup = document.querySelector('#target-size-group');
const fixedCopiesGroup = document.querySelector('#fixed-copies-group');
const uniqueFields = document.querySelector('#unique-fields');

let currentPlanId = null;
let pollTimer = null;

function invalidatePlan() {
    if (!currentPlanId) return;
    const previousPlanId = currentPlanId;
    currentPlanId = null;
    planPanel.classList.add('hidden');
    startButton.disabled = true;
    fetch(`/api/plans/${previousPlanId}`, {method: 'DELETE'}).catch(() => {});
}
planForm.addEventListener('input', invalidatePlan);
planForm.addEventListener('change', invalidatePlan);

const copyStrategy = document.querySelector('#copy-strategy');
function updateCopyStrategy() {
    const branches = copyStrategy.value === 'branches';
    for (const field of [uniqueFields, document.querySelector('textarea[name="paths"]')]) {
        field.disabled = branches;
        field.closest('label').classList.toggle('hidden', branches);
    }
    const references = document.querySelector('textarea[name="branchReferences"]');
    references.disabled = !branches;
    document.querySelector('#branch-references-label').classList.toggle('hidden', !branches);
    document.querySelector('#strategy-description').textContent = branches
        ? 'Корни сохраняются, подразделения и должности копируются вместе со связями. Размер округляется до полной копии структуры.'
        : 'Каждая запись копируется отдельно. Связи между записями автоматически не переназначаются.';
}
copyStrategy.addEventListener('change', updateCopyStrategy);
updateCopyStrategy();

document.querySelectorAll('input[name="mode"]').forEach(input => {
    input.addEventListener('change', () => {
        const fixed = input.value === 'fixed' && input.checked;
        targetSizeGroup.classList.toggle('hidden', fixed);
        fixedCopiesGroup.classList.toggle('hidden', !fixed);
    });
});

fileInput.addEventListener('change', () => {
    const file = fileInput.files[0];
    fileLabel.textContent = file ? file.name : 'Выбрать XML-файл';
});

document.querySelector('#fill-unique-fields').addEventListener('click', () => {
    invalidatePlan();
    uniqueFields.value = [
        'IDOBJ',
        'DYN_ATTR/HRP9110/item/OBJID',
        'IDPERS'
    ].join('\n');
});

planForm.addEventListener('submit', async event => {
    event.preventDefault();
    hideError(planError);
    hideError(jobError);
    clearInterval(pollTimer);
    jobPanel.classList.add('hidden');

    if (!fileInput.files.length) {
        showError(planError, 'Выберите XML-файл.');
        return;
    }

    if (currentPlanId) {
        fetch(`/api/plans/${currentPlanId}`, {method: 'DELETE'}).catch(() => {});
        currentPlanId = null;
    }

    planButton.disabled = true;
    planWorking.classList.remove('hidden');
    planPanel.classList.add('hidden');

    try {
        const response = await fetch('/api/plans', {
            method: 'POST',
            body: new FormData(planForm)
        });
        const data = await readJson(response);
        currentPlanId = data.planId;
        renderPlan(data);
    } catch (error) {
        showError(planError, error.message);
    } finally {
        planButton.disabled = false;
        planWorking.classList.add('hidden');
    }
});

startButton.addEventListener('click', async () => {
    hideError(jobError);
    if (!currentPlanId) {
        showError(jobError, 'Сначала постройте план.');
        return;
    }

    startButton.disabled = true;
    try {
        const response = await fetch('/api/jobs', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                planId: currentPlanId,
                outputPath: document.querySelector('#output-path').value,
                overwrite: document.querySelector('#overwrite').checked
            })
        });
        const job = await readJson(response);
        currentPlanId = null;
        renderJob(job);
        pollTimer = setInterval(() => pollJob(job.jobId), 900);
    } catch (error) {
        showError(jobError, error.message);
        startButton.disabled = false;
    }
});

resetButton.addEventListener('click', async () => {
    clearInterval(pollTimer);
    if (currentPlanId) {
        await fetch(`/api/plans/${currentPlanId}`, {method: 'DELETE'}).catch(() => {});
    }
    currentPlanId = null;
    planPanel.classList.add('hidden');
    jobPanel.classList.add('hidden');
    startButton.disabled = false;
    fileInput.value = '';
    fileLabel.textContent = 'Выбрать XML-файл';
    window.scrollTo({top: 0, behavior: 'smooth'});
});

function renderPlan(plan) {
    document.querySelector('#plan-file-name').textContent = plan.originalFileName;
    document.querySelector('#metric-source').textContent = formatBytes(plan.originalSerializedBytes);
    document.querySelector('#metric-output').textContent = formatBytes(plan.estimatedOutputBytes);
    document.querySelector('#metric-copies').textContent = formatNumber(plan.fullExtraCopiesPerRecord);
    document.querySelector('#metric-copies-label').textContent = plan.copyStrategy === 'branches'
        ? 'Копий структуры' : 'Полных доп. копий';
    document.querySelector('#metric-records').textContent = formatBytes(plan.repeatableRecordBytes);
    document.querySelector('#output-path').value = plan.suggestedOutputPath;
    const branchSummary = document.querySelector('#branch-summary');
    branchSummary.classList.toggle('hidden', !plan.branchSummary);
    if (plan.branchSummary) {
        const summary = plan.branchSummary;
        branchSummary.textContent = `Сохраняются корни: ${formatNumber(summary.preservedRoots)}. `
            + `Сотрудников в каждой копии: ${formatNumber(summary.copiedPersonsPerCopy)}; `
            + `сотрудников без копий: ${formatNumber(summary.preservedPersons)}.`;
        if (!plan.fixedCopiesMode && plan.estimatedOutputBytes > plan.requestedTargetBytes) {
            branchSummary.textContent += ` Для целых ветвей размер увеличен на `
                + `${formatBytes(plan.estimatedOutputBytes - plan.requestedTargetBytes)}.`;
        }
    }

    const table = document.querySelector('#paths-table');
    table.replaceChildren();
    plan.targetPaths.forEach(path => {
        const row = document.createElement('tr');
        [
            path.path,
            formatNumber(path.recordCount),
            formatNumber(path.excludedRecordCount),
            formatBytes(path.recordBytes),
            `${path.byteSharePercent.toFixed(2)}%`
        ].forEach(value => {
            const cell = document.createElement('td');
            cell.textContent = value;
            row.appendChild(cell);
        });
        table.appendChild(row);
    });

    planPanel.classList.remove('hidden');
    startButton.disabled = false;
    planPanel.scrollIntoView({behavior: 'smooth', block: 'start'});
}

async function pollJob(jobId) {
    try {
        const response = await fetch(`/api/jobs/${jobId}`);
        const job = await readJson(response);
        renderJob(job);
        if (job.status === 'COMPLETED' || job.status === 'FAILED') {
            clearInterval(pollTimer);
        }
    } catch (error) {
        clearInterval(pollTimer);
        renderJobFailure(error.message);
    }
}

function renderJob(job) {
    jobPanel.classList.remove('hidden');
    document.querySelector('#job-message').textContent = job.message;
    document.querySelector('#progress-bar').style.width = `${job.progress}%`;
    document.querySelector('#progress-value').textContent = `${job.progress}%`;
    document.querySelector('#job-output-path').textContent = job.outputPath;

    const summary = document.querySelector('#result-summary');
    if (job.status === 'COMPLETED') {
        summary.textContent = `Готово: ${formatBytes(job.outputBytes)}, `
            + `${formatNumber(job.duplicatesWritten)} дубликатов, `
            + `${formatNumber(job.mutatedFields)} изменённых полей.`;
        summary.style.removeProperty('color');
        summary.style.removeProperty('background');
        summary.style.removeProperty('border-color');
        summary.classList.remove('hidden');
    } else if (job.status === 'FAILED') {
        renderJobFailure(job.error || 'Не удалось создать файл.');
    } else {
        summary.classList.add('hidden');
    }
}

function renderJobFailure(message) {
    document.querySelector('#job-message').textContent = 'Ошибка расширения';
    const summary = document.querySelector('#result-summary');
    summary.textContent = message;
    summary.classList.remove('hidden');
    summary.style.color = 'var(--danger)';
    summary.style.background = '#fff0f0';
    summary.style.borderColor = '#f2cccc';
}

async function readJson(response) {
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(data.error || `HTTP ${response.status}`);
    }
    return data;
}

function formatBytes(value) {
    if (value === null || value === undefined) return '—';
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let size = Number(value);
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
        size /= 1024;
        unit++;
    }
    const digits = unit === 0 ? 0 : size >= 100 ? 0 : size >= 10 ? 1 : 2;
    return `${size.toFixed(digits)} ${units[unit]}`;
}

function formatNumber(value) {
    return new Intl.NumberFormat('ru-RU').format(value ?? 0);
}

function showError(element, message) {
    element.textContent = message;
    element.classList.remove('hidden');
}

function hideError(element) {
    element.textContent = '';
    element.classList.add('hidden');
}
