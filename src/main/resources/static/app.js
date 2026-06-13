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
  t._t = setTimeout(() => {
    t.style.display = 'none';
  }, duration);
}

async function callApi(method, path, body) {
  const headers = {
    'Content-Type': 'application/json',
    ...authHeaders()
  };

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
    const res = await callApi('POST', '/api/auth/login', {
      username,
      password
    });

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
  refreshRiskService();
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
    sseCtrl: null,
    txCount: 0,
    alertCount: 0,
    sentTransactions: []
  });

  ALERT_STATE.alerts = [];
  ALERT_STATE.seen.clear();

  sessionStorage.clear();

  $('loginView').hidden = false;
  $('dashboardView').hidden = true;
  $('userArea').hidden = true;

  clearAlerts();
  updateSummaryCounts();
}

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
    });
  });
}

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
    box.innerHTML = `${icon} ${status}`;
    box.hidden = false;

    showToast(
        `${icon} Tranzaksiya göndərildi: <strong>${status}</strong>`,
        cls === 'normal' ? 'ok' : cls === 'suspicious' ? 'warn' : 'err'
    );

    setTimeout(loadAllTransactions, 1500);
    setTimeout(refreshRiskService, 700);
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

    const normal = STATE.sentTransactions.filter(t => t.riskStatus === 'NORMAL');

    renderTxColumn(
        'listNormal',
        normal,
        'normal',
        'Normal əməliyyat yoxdur'
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
  if (n == null || Number.isNaN(Number(n))) {
    return '—';
  }

  return new Intl.NumberFormat('az-AZ', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(Number(n));
}

function updateSummaryCounts() {
  const txEl = $('metricDbCount');
  const warningEl = $('metricRedisCount');

  const txCount = STATE.txCount || STATE.sentTransactions.length || 0;
  const warningCount = STATE.alertCount || ALERT_STATE.alerts.length || 0;

  if (txEl) {
    txEl.textContent = `${txCount}`;
  }

  if (warningEl) {
    warningEl.textContent = `${warningCount}`;
  }
}

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
          addWarning(JSON.parse(msg.body), 'WS');
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

    if (!res.ok) {
      throw new Error(res.status);
    }

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
            addWarning(JSON.parse(m[1]), 'SSE');
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

function warningKey(item) {
  const id = item.transactionId || item.txId || item.id || '';
  const status = item.status || item.riskStatus || '';
  const score = item.riskScore || item.score || '';

  return `${id}:${status}:${score}`;
}

function addWarning(item, source = 'LIVE') {
  const normalized = normalizeWarning(item, source);
  const key = warningKey(normalized);

  if (ALERT_STATE.seen.has(key)) {
    return;
  }

  ALERT_STATE.seen.add(key);
  ALERT_STATE.alerts.unshift(normalized);

  while (ALERT_STATE.alerts.length > 80) {
    const removed = ALERT_STATE.alerts.pop();
    ALERT_STATE.seen.delete(warningKey(removed));
  }

  STATE.alertCount = ALERT_STATE.alerts.length;

  updateSummaryCounts();
  renderAlerts();

  if (document.querySelector('#tab-history.active')) {
    setTimeout(loadAllTransactions, 800);
  }
}

function normalizeWarning(item, source) {
  return {
    ...item,
    source,
    status: String(item.status || item.riskStatus || 'SUSPICIOUS').toUpperCase(),
    transactionId: item.transactionId || item.txId || item.id || '',
    customerId: item.customerId || item.customer || '—',
    amount: item.amount,
    currency: item.currency || 'AZN',
    country: item.country || '—',
    riskScore: item.riskScore ?? item.score ?? 0,
    reasons: item.reasons || item.reason || '—',
    timestamp: item.timestamp || item.createdAt || new Date().toISOString()
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

  const filtered = ALERT_STATE.alerts.filter(item => {
    const status = String(item.status || '').toUpperCase();

    if (status === 'FRAUD') return showFraud;
    if (status === 'SUSPICIOUS') return showSuspicious;

    return false;
  });

  if (filtered.length === 0) {
    list.innerHTML = `<p class="center muted">Uyğun xəbərdarlıq yoxdur. Filterləri dəyişin və ya riskli tranzaksiya göndərin.</p>`;
    return;
  }

  list.innerHTML = filtered.map(item => {
    const status = String(item.status).toUpperCase();
    const cssStatus = status === 'FRAUD' ? 'fraud' : 'suspicious';

    const ts = item.timestamp
        ? new Date(item.timestamp).toLocaleTimeString('az-AZ')
        : new Date().toLocaleTimeString('az-AZ');

    return `
      <article class="alert-item ${cssStatus}">
        <div class="alert-head">
          <span class="badge ${cssStatus}">${status}</span>
          <span class="src-badge">${item.source || 'LIVE'}</span>
          <span class="ts">${ts}</span>
        </div>

        <div class="alert-body">
          <div><strong>Tx:</strong> ${String(item.transactionId || '').slice(0, 10)}...</div>
          <div><strong>Müştəri:</strong> ${item.customerId || '—'}</div>
          <div><strong>Məbləğ:</strong> ${fmt(item.amount)} ${item.currency || ''}</div>
          <div><strong>Ölkə:</strong> ${item.country || '—'}</div>
          <div><strong>Risk:</strong> ${item.riskScore ?? 0}/100</div>
          <div><strong>Səbəb:</strong> ${item.reasons || '—'}</div>
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
    list.innerHTML = `<p class="center muted">Xəbərdarlıq yoxdur.</p>`;
  }
}

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
    const res = await callApi('POST', '/api/ai/chat', {
      message: msg
    });

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

function toUserRiskServiceState(state) {
  const s = String(state || '').toUpperCase();

  if (s === 'CLOSED') return 'Sistem aktiv';
  if (s === 'OPEN') return 'Xidmət məhdudlaşıb';
  if (s === 'HALF_OPEN') return 'Yoxlanılır';
  if (s === 'DISABLED') return 'Deaktivdir';

  return 'Yoxlanılır';
}

async function refreshRiskService() {
  try {
    const cb = await fetch('/actuator/circuitbreakers', {
      headers: authHeaders()
    }).then(r => r.json());

    const grok = cb.circuitBreakers?.grokFraudClient;

    if (!grok) {
      if ($('cbStateValue')) {
        $('cbStateValue').textContent = 'Məlumat yoxdur';
      }

      return;
    }

    const state = grok.state || 'UNKNOWN';
    const userState = toUserRiskServiceState(state);

    if ($('cbStateValue')) {
      $('cbStateValue').textContent = userState;
      $('cbStateValue').style.color =
          state === 'CLOSED'
              ? '#168a4a'
              : state === 'OPEN'
                  ? '#d0001b'
                  : '#c77900';
    }
  } catch (_) {
    if ($('cbStateValue')) {
      $('cbStateValue').textContent = 'Yoxlanılır';
    }
  }
}

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
  if (STATE.token) {
    refreshRiskService();
  }
}, 5000);