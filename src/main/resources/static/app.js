/* ============================================================
   BankRadar — app.js
   Updated UI + gRPC monitor + working alert filters
   ============================================================ */

const STATE = {
  token: null,
  username: null,
  role: null,
  ws: null,
  sseCtrl: null,
  txCount: 0,
  alertCount: 0,
  sentTransactions: []
};

const ALERT_STATE = {
  alerts: [],
  seen: new Set()
};

const $ = id => document.getElementById(id);

function authHeaders() {
  const token = STATE.token || sessionStorage.getItem('br_t') || sessionStorage.getItem('token');
  return token ? { Authorization: 'Bearer ' + token } : {};
}

function showToast(msg, type = 'info', duration = 4000) {
  const t = $('toast');
  if (!t) {
    alert(msg);
    return;
  }

  t.innerHTML = msg;
  t.style.cssText = `
    display:block;
    position:fixed;
    bottom:1.5rem;
    right:1.5rem;
    padding:1rem 1.4rem;
    border-radius:16px;
    color:#fff;
    font-size:.95rem;
    z-index:9999;
    box-shadow:0 18px 42px rgba(0,0,0,.22);
    max-width:440px;
    font-weight:700;
    background:${type === 'ok' ? '#168a4a' : type === 'err' ? '#d0001b' : type === 'warn' ? '#c77900' : '#780014'};
  `;

  clearTimeout(t._t);
  t._t = setTimeout(() => t.style.display = 'none', duration);
}

async function callApi(method, path, body) {
  const headers = { 'Content-Type': 'application/json', ...authHeaders() };

  const res = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  });

  if (!res.ok) {
    let msg = '';

    try {
      const j = await res.json();
      msg = j.error || j.message || JSON.stringify(j);
    } catch (_) {}

    throw new Error(`HTTP ${res.status} ${msg}`);
  }

  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/* ════════════ AUTH ════════════ */

function fillLogin(username) {
  $('loginUsername').value = username;
  $('loginPassword').value = '';
  $('loginPassword').focus();
}

async function login() {
  const username = $('loginUsername').value.trim();
  const password = $('loginPassword').value.trim();

  if (!username || !password) {
    showToast('İstifadəçi adı və şifrəni daxil edin', 'err');
    return;
  }

  try {
    const res = await callApi('POST', '/api/auth/login', { username, password });

    STATE.token = res.token;
    STATE.username = res.fullName || res.username || username;
    STATE.role = res.role;

    sessionStorage.setItem('br_t', res.token);
    sessionStorage.setItem('br_u', STATE.username);
    sessionStorage.setItem('br_r', STATE.role);

    afterLogin();
    showToast(`Xoş gəldiniz, ${STATE.username}! (${STATE.role})`, 'ok');
  } catch (e) {
    const el = $('loginError');
    el.textContent = 'Giriş uğursuz: ' + e.message;
    el.hidden = false;
  }
}

async function registerUser() {
  const fullName = $('registerFullName').value.trim();
  const username = $('registerUsername').value.trim();
  const password = $('registerPassword').value.trim();
  const role = $('registerRole').value;

  if (!fullName || !username || !password || !role) {
    showToast('Qeydiyyat üçün bütün xanaları doldurun', 'err');
    return;
  }

  try {
    const res = await callApi('POST', '/api/auth/register', {
      fullName,
      username,
      password,
      role
    });

    showToast(`${res.username} yaradıldı. İndi şifrə ilə daxil olun.`, 'ok');

    $('loginUsername').value = res.username;
    $('loginPassword').value = '';
    $('loginPassword').focus();
  } catch (e) {
    showToast('Qeydiyyat xətası: ' + e.message, 'err');
  }
}

function afterLogin() {
  $('loginView').hidden = true;
  $('dashboardView').hidden = false;
  $('userArea').hidden = false;

  $('userName').textContent = STATE.username;
  $('userRole').textContent = STATE.role;

  connectWebSocket();
  connectSSE();
  refreshHealth();
  loadAllTransactions();
}

function logout() {
  if (STATE.ws) {
    try {
      STATE.ws.disconnect();
    } catch (_) {}
  }

  if (STATE.sseCtrl) {
    STATE.sseCtrl.abort();
  }

  Object.assign(STATE, {
    token: null,
    username: null,
    role: null,
    ws: null,
    sseCtrl: null
  });

  ALERT_STATE.alerts = [];
  ALERT_STATE.seen.clear();

  sessionStorage.clear();

  $('loginView').hidden = false;
  $('dashboardView').hidden = true;
  $('userArea').hidden = true;

  clearAlerts();
}

/* ════════════ TABS ════════════ */

function initTabs() {
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      const name = tab.dataset.tab;

      document.querySelectorAll('.tab').forEach(t => {
        t.classList.toggle('active', t === tab);
      });

      document.querySelectorAll('.tab-content').forEach(c => {
        c.classList.toggle('active', c.id === 'tab-' + name);
      });

      if (name === 'history') {
        loadAllTransactions();
      }

      if (name === 'alerts') {
        renderAlerts();
      }

      if (name === 'health') {
        refreshHealth();
        refreshCB();
      }
    });
  });
}

/* ════════════ TRANSACTION ════════════ */

async function submitTransaction() {
  const tx = {
    customerId: $('txCustomerId').value.trim(),
    amount: parseFloat($('txAmount').value),
    currency: $('txCurrency').value,
    country: $('txCountry').value,
    merchantCategory: $('txMerchant').value
  };

  const box = $('txResult');
  box.hidden = true;

  if (!tx.customerId || !tx.amount || tx.amount <= 0) {
    box.className = 'classify-result fraud';
    box.innerHTML = `XƏTA<small>Müştəri ID və düzgün məbləğ daxil edin.</small>`;
    box.hidden = false;
    return;
  }

  try {
    const res = await callApi('POST', '/api/transactions', tx);

    let status = 'NORMAL';
    let cls = 'normal';
    let icon = '✅';

    if (tx.amount >= 50000 || (tx.merchantCategory === 'CRYPTO' && tx.amount >= 30000)) {
      status = 'FRAUD';
      cls = 'fraud';
      icon = '🚨';
    } else if (
        tx.amount >= 10000 ||
        tx.country === 'RU' ||
        tx.country === 'US' ||
        tx.merchantCategory === 'JEWELRY' ||
        tx.merchantCategory === 'CRYPTO'
    ) {
      status = 'SUSPICIOUS';
      cls = 'suspicious';
      icon = '⚠️';
    }

    STATE.sentTransactions.push({
      ...tx,
      id: res.id,
      riskStatus: status
    });

    STATE.txCount++;
    updateSummaryCounts();

    box.className = `classify-result ${cls}`;
    box.innerHTML = `${icon} ${status}<small>Final status backend Risk Engine tərəfindən təyin olunur.</small>`;
    box.hidden = false;

    showToast(`${icon} Tranzaksiya göndərildi: <strong>${status}</strong>`, cls === 'normal' ? 'ok' : cls === 'suspicious' ? 'warn' : 'err');

    setTimeout(loadAllTransactions, 1500);
    setTimeout(refreshHealth, 700);
  } catch (e) {
    box.className = 'classify-result fraud';
    box.innerHTML = `XƏTA<small>${e.message}</small>`;
    box.hidden = false;

    if (e.message.includes('403') || e.message.includes('401')) {
      showToast('Sessiya bitib — yenidən daxil olun', 'err');
      setTimeout(logout, 1500);
    }
  }
}

function loadPreset(type) {
  const n = Math.floor(Math.random() * 999);

  const presets = {
    normal: {
      cust: 'CUST-' + n,
      amt: 85,
      cur: 'AZN',
      ctry: 'AZ',
      cat: 'GROCERY'
    },
    suspicious: {
      cust: 'CUST-' + n,
      amt: 12000,
      cur: 'AZN',
      ctry: 'RU',
      cat: 'ELECTRONICS'
    },
    fraud: {
      cust: 'CUST-' + n,
      amt: 75000,
      cur: 'AZN',
      ctry: 'US',
      cat: 'CRYPTO'
    }
  };

  const p = presets[type];
  if (!p) return;

  $('txCustomerId').value = p.cust;
  $('txAmount').value = p.amt;
  $('txCurrency').value = p.cur;
  $('txCountry').value = p.ctry;
  $('txMerchant').value = p.cat;
}

/* ════════════ TRANSACTIONS DB ════════════ */

async function loadAllTransactions() {
  try {
    const res = await callApi('POST', '/graphql', {
      query: `{ flaggedTransactions { id customerId amount currency country merchantCategory riskScore riskStatus } }`
    });

    if (res?.errors) {
      const msg = res.errors[0]?.message || 'Naməlum';
      ['listNormal', 'listSusp', 'listFraud'].forEach(id => {
        $(id).innerHTML = `<p class="center muted small">GraphQL: ${msg}</p>`;
      });
      return;
    }

    const flagged = res?.data?.flaggedTransactions || [];

    const suspicious = flagged.filter(t => t.riskStatus === 'SUSPICIOUS');
    const fraud = flagged.filter(t => t.riskStatus === 'FRAUD');

    const normal = STATE.sentTransactions.filter(t => {
      return t.riskStatus === 'NORMAL';
    });

    renderTxColumn(
        'listNormal',
        normal,
        'normal',
        'Lokal göndərilmiş NORMAL əməliyyatlar burada görünür. Riskli əməliyyatlar GraphQL-dən gəlir.'
    );

    renderTxColumn('listSusp', suspicious, 'suspicious');
    renderTxColumn('listFraud', fraud, 'fraud');

    $('cntNormal').textContent = normal.length;
    $('cntSusp').textContent = suspicious.length;
    $('cntFraud').textContent = fraud.length;

    const total = normal.length + suspicious.length + fraud.length;
    STATE.txCount = Math.max(STATE.txCount, total);
    updateSummaryCounts();
  } catch (e) {
    ['listNormal', 'listSusp', 'listFraud'].forEach(id => {
      $(id).innerHTML = `<p class="center muted small">Yüklənmədi: ${e.message}</p>`;
    });
  }
}

function renderTxColumn(elementId, list, type, emptyMsg) {
  const el = $(elementId);
  if (!el) return;

  if (!list || list.length === 0) {
    el.innerHTML = `<p class="center muted small">${emptyMsg || 'Əməliyyat yoxdur'}</p>`;
    return;
  }

  el.innerHTML = list.map(tx => `
    <div class="tx-row">
      <strong>${(tx.id || '').slice(0, 8)}... · ${tx.customerId || '—'}</strong>
      <div class="tx-meta">
        <span class="tx-amt ${type}">${fmt(tx.amount)} ${tx.currency || ''}</span>
        <span>· ${tx.country || '—'}</span>
        <span>· ${tx.merchantCategory || '—'}</span>
        ${tx.riskScore != null ? `<span>· Risk: ${tx.riskScore}/100</span>` : ''}
      </div>
    </div>
  `).join('');
}

function fmt(n) {
  if (n == null || Number.isNaN(Number(n))) return '—';

  return new Intl.NumberFormat('az-AZ', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(Number(n));
}

function updateSummaryCounts() {
  const txEl = $('metricDbCount');
  const alertEl = $('metricRedisCount');

  if (txEl) txEl.textContent = `${STATE.txCount || STATE.sentTransactions.length || 0} tx`;
  if (alertEl) alertEl.textContent = `${STATE.alertCount || ALERT_STATE.alerts.length || 0} alert`;
}

/* ════════════ WEBSOCKET ════════════ */

function connectWebSocket() {
  const el = $('wsStatus');
  if (!el) return;

  el.className = 'status-pill';
  el.innerHTML = '<span class="dot"></span> WebSocket qoşulur...';

  try {
    const sock = new SockJS('/ws');
    const client = Stomp.over(sock);

    client.debug = null;

    client.connect({}, () => {
      el.className = 'status-pill on';
      el.innerHTML = '<span class="dot"></span> WebSocket aktiv';

      client.subscribe('/topic/alerts', msg => {
        try {
          addAlert(JSON.parse(msg.body), 'WS');
        } catch (_) {}
      });
    }, () => {
      el.className = 'status-pill error';
      el.innerHTML = '<span class="dot"></span> WebSocket bağlı deyil';
      setTimeout(connectWebSocket, 5000);
    });

    STATE.ws = client;
  } catch (_) {
    el.className = 'status-pill error';
    el.innerHTML = '<span class="dot"></span> WebSocket bağlı deyil';
    setTimeout(connectWebSocket, 5000);
  }
}

/* ════════════ SSE ════════════ */

async function connectSSE() {
  const el = $('sseStatus');
  if (!el) return;

  el.className = 'status-pill';
  el.innerHTML = '<span class="dot"></span> SSE qoşulur...';

  const ctrl = new AbortController();
  STATE.sseCtrl = ctrl;

  try {
    const res = await fetch('/api/alerts/stream', {
      headers: authHeaders(),
      signal: ctrl.signal
    });

    if (!res.ok) throw new Error(res.status);

    el.className = 'status-pill on';
    el.innerHTML = '<span class="dot"></span> SSE aktiv';

    const reader = res.body.getReader();
    const decoder = new TextDecoder();

    let buf = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buf += decoder.decode(value, { stream: true });

      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);

        const m = block.match(/^data:\s*(.+)$/m);
        if (m) {
          try {
            addAlert(JSON.parse(m[1]), 'SSE');
          } catch (_) {}
        }
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      el.className = 'status-pill error';
      el.innerHTML = '<span class="dot"></span> SSE bağlı deyil';
      setTimeout(connectSSE, 5000);
    }
  }
}

/* ════════════ ALERTS + FILTERS ════════════ */

function alertKey(alert) {
  const id = alert.transactionId || alert.txId || alert.id || '';
  const status = alert.status || alert.riskStatus || '';
  const score = alert.riskScore || alert.score || '';
  return `${id}:${status}:${score}`;
}

function addAlert(alert, source = 'LIVE') {
  const normalized = normalizeAlert(alert, source);
  const key = alertKey(normalized);

  if (ALERT_STATE.seen.has(key)) {
    return;
  }

  ALERT_STATE.seen.add(key);
  ALERT_STATE.alerts.unshift(normalized);

  while (ALERT_STATE.alerts.length > 80) {
    const removed = ALERT_STATE.alerts.pop();
    ALERT_STATE.seen.delete(alertKey(removed));
  }

  STATE.alertCount = ALERT_STATE.alerts.length;
  updateSummaryCounts();
  renderAlerts();

  if (document.querySelector('#tab-history.active')) {
    setTimeout(loadAllTransactions, 800);
  }
}

function normalizeAlert(alert, source) {
  return {
    ...alert,
    source,
    status: String(alert.status || alert.riskStatus || 'SUSPICIOUS').toUpperCase(),
    transactionId: alert.transactionId || alert.txId || alert.id || '',
    customerId: alert.customerId || alert.customer || '—',
    amount: alert.amount,
    currency: alert.currency || 'AZN',
    country: alert.country || '—',
    riskScore: alert.riskScore ?? alert.score ?? 0,
    reasons: alert.reasons || alert.reason || '—',
    timestamp: alert.timestamp || alert.createdAt || new Date().toISOString()
  };
}

function applyAlertFilters() {
  renderAlerts();
}

function renderAlerts() {
  const list = $('alertsList');
  if (!list) return;

  const showFraud = $('filterFraud')?.checked ?? true;
  const showSuspicious = $('filterSuspicious')?.checked ?? true;

  const filtered = ALERT_STATE.alerts.filter(alert => {
    const status = String(alert.status || '').toUpperCase();

    if (status === 'FRAUD') return showFraud;
    if (status === 'SUSPICIOUS') return showSuspicious;

    return false;
  });

  if (filtered.length === 0) {
    list.innerHTML = `<p class="center muted">Uyğun bildiriş yoxdur. Filterləri dəyişin və ya riskli tranzaksiya göndərin.</p>`;
    return;
  }

  list.innerHTML = filtered.map(alert => {
    const status = String(alert.status).toUpperCase();
    const cssStatus = status === 'FRAUD' ? 'fraud' : 'suspicious';
    const ts = alert.timestamp
        ? new Date(alert.timestamp).toLocaleTimeString('az-AZ')
        : new Date().toLocaleTimeString('az-AZ');

    return `
      <article class="alert-item ${cssStatus}">
        <div class="alert-head">
          <span class="badge ${cssStatus}">${status}</span>
          <span class="src-badge">${alert.source || 'LIVE'}</span>
          <span class="ts">${ts}</span>
        </div>

        <div class="alert-body">
          <div><strong>Tx:</strong> ${String(alert.transactionId || '').slice(0, 10)}...</div>
          <div><strong>Müştəri:</strong> ${alert.customerId || '—'}</div>
          <div><strong>Məbləğ:</strong> ${fmt(alert.amount)} ${alert.currency || ''}</div>
          <div><strong>Ölkə:</strong> ${alert.country || '—'}</div>
          <div><strong>Risk:</strong> ${alert.riskScore ?? 0}/100</div>
          <div><strong>Səbəb:</strong> ${alert.reasons || '—'}</div>
        </div>
      </article>
    `;
  }).join('');
}

function clearAlerts() {
  ALERT_STATE.alerts = [];
  ALERT_STATE.seen.clear();
  STATE.alertCount = 0;
  updateSummaryCounts();

  const list = $('alertsList');
  if (list) {
    list.innerHTML = `<p class="center muted">Bildiriş yoxdur.</p>`;
  }
}

/* ════════════ gRPC STATUS MONITOR ════════════ */

async function checkGrpcStatus() {
  const statusEl = $('grpcStatusBadge');
  const serverEl = $('grpcServer');
  const msgEl = $('grpcMessage');
  const lastEl = $('grpcLastCheck');

  if (statusEl) statusEl.textContent = 'Yoxlanılır...';
  if (msgEl) msgEl.textContent = 'gRPC statusu yoxlanılır...';

  try {
    const res = await fetch('/api/grpc/status', {
      method: 'GET',
      headers: authHeaders()
    });

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }

    const data = await res.json();

    if (statusEl) statusEl.textContent = data.status || 'UP';
    if (serverEl) serverEl.textContent = data.server || 'localhost:9090';

    if (msgEl) {
      msgEl.innerHTML = `
        <strong>Status:</strong> ${data.status || 'UP'} ·
        <strong>Service:</strong> ${data.service || 'RiskStreamService'} ·
        ${data.message || 'gRPC daxili risk stream üçün aktivdir.'}
      `;
    }

    if (lastEl) {
      lastEl.textContent = new Date().toLocaleTimeString('az-AZ');
    }
  } catch (e) {
    if (statusEl) statusEl.textContent = 'YOXLAMA XƏTASI';

    if (msgEl) {
      msgEl.innerHTML = `
        gRPC status endpoint-i tapılmadı və ya cavab vermədi.
        Backend-də <code>/api/grpc/status</code> endpoint-i əlavə olunmalıdır.
        Xəta: ${e.message}
      `;
    }

    if (lastEl) {
      lastEl.textContent = new Date().toLocaleTimeString('az-AZ');
    }
  }
}

/* ════════════ AI CHAT ════════════ */

function askPreset(question) {
  $('chatInput').value = question;
  sendChat();
}

async function sendChat() {
  const input = $('chatInput');
  const msg = input.value.trim();

  if (!msg) return;

  input.value = '';
  addChatMsg(msg, 'user');

  const btn = $('sendBtn');
  btn.disabled = true;
  btn.textContent = '...';

  addChatMsg('Cavab hazırlanır...', 'ai', 'thinking');

  try {
    const res = await callApi('POST', '/api/ai/chat', { message: msg });

    document.querySelector('.thinking')?.remove();
    addChatMsg(res.answer || 'Cavab alınmadı', 'ai');
  } catch (e) {
    document.querySelector('.thinking')?.remove();
    addChatMsg('❌ ' + e.message, 'ai');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Göndər';
  }
}

function addChatMsg(text, role, cls = '') {
  const box = $('chatMessages');
  if (!box) return;

  const div = document.createElement('div');
  div.className = `chat-msg ${role} ${cls}`;

  const safeText = String(text)
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replace(/\n/g, '<br>');

  div.innerHTML = `
    <div class="chat-avatar ${role}">${role === 'ai' ? '🤖' : '👤'}</div>
    <div class="chat-bubble">${safeText}</div>
  `;

  box.appendChild(div);
  box.scrollTop = box.scrollHeight;
}

/* ════════════ HEALTH + CIRCUIT BREAKER ════════════ */

async function refreshHealth() {
  try {
    const h = await fetch('/actuator/health').then(r => r.json());
    const c = h.components || {};

    ['metricStatus', 'metricStatus2'].forEach(id => {
      const el = $(id);
      if (!el) return;

      el.textContent = h.status;
      el.style.color = h.status === 'UP' ? '#168a4a' : '#d0001b';
    });

    setHealthValue(c, 'db', 'metricDb2');
    setHealthValue(c, 'redis', 'metricRedis2');
    setHealthValue(c, 'diskSpace', 'metricDisk');
  } catch (_) {
    ['metricStatus', 'metricStatus2'].forEach(id => {
      const el = $(id);
      if (el) {
        el.textContent = 'DOWN';
        el.style.color = '#d0001b';
      }
    });
  }

  refreshCB();
}

function setHealthValue(components, key, id) {
  const el = $(id);
  if (!el) return;

  const v = components[key]?.status || '—';
  el.textContent = v;
  el.style.color = v === 'UP' ? '#168a4a' : '#d0001b';
}

async function refreshCB() {
  try {
    const cb = await fetch('/actuator/circuitbreakers').then(r => r.json());
    const grok = cb.circuitBreakers?.grokFraudClient;

    if (!grok) {
      if ($('cbStateBig')) $('cbStateBig').textContent = 'YOX';
      if ($('cbStateValue')) $('cbStateValue').textContent = 'YOX';
      return;
    }

    const state = grok.state || 'UNKNOWN';

    if ($('cbStateValue')) {
      $('cbStateValue').textContent = state;
      $('cbStateValue').style.color =
          state === 'CLOSED' ? '#168a4a' :
              state === 'OPEN' ? '#d0001b' :
                  '#c77900';
    }

    if ($('cbStateBig')) {
      $('cbStateBig').textContent = state;
      $('cbStateBig').className = 'cb-state-display ' + state;
    }

    const metrics = grok.metrics || {};
    const success = metrics.numberOfSuccessfulCalls || 0;
    const failed = metrics.numberOfFailedCalls || 0;
    const rate = metrics.failureRate || 0;

    if ($('cbSuccess')) $('cbSuccess').textContent = success;
    if ($('cbFailed')) $('cbFailed').textContent = failed;
    if ($('cbFailRate')) $('cbFailRate').textContent = (rate < 0 ? 0 : rate).toFixed(1) + '%';
  } catch (_) {}
}

async function simulateCBFailure() {
  showToast(
      '6 borderline tranzaksiya göndərilir. Groq API uğursuzdursa, Circuit Breaker OPEN ola bilər.',
      'warn',
      6000
  );

  for (let i = 0; i < 6; i++) {
    try {
      await callApi('POST', '/api/transactions', {
        customerId: 'CB-TEST-' + Date.now() + '-' + i,
        amount: 12000 + i * 100,
        currency: 'AZN',
        country: 'RU',
        merchantCategory: 'CRYPTO'
      });
    } catch (e) {
      console.log('TX failed:', e.message);
    }

    await new Promise(r => setTimeout(r, 200));
  }

  showToast('Tranzaksiyalar göndərildi. CB vəziyyətini izləyin.', 'ok');

  [2000, 5000, 8000, 12000, 18000, 25000, 35000].forEach(t => {
    setTimeout(refreshCB, t);
  });
}

/* ════════════ BOOTSTRAP ════════════ */

window.addEventListener('DOMContentLoaded', () => {
  initTabs();

  const t = sessionStorage.getItem('br_t');

  if (t) {
    STATE.token = t;
    STATE.username = sessionStorage.getItem('br_u');
    STATE.role = sessionStorage.getItem('br_r');

    afterLogin();
  }
});

setInterval(() => {
  if (STATE.token && document.querySelector('#tab-health.active')) {
    refreshCB();
  }
}, 5000);