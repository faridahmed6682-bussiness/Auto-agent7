'use strict';

const userIdInput = document.getElementById('userIdInput');
const refreshHubBtn = document.getElementById('refreshHubBtn');
const feedbackText = document.getElementById('feedbackText');
const toolsCountBadge = document.getElementById('toolsCountBadge');
const activeToolsChips = document.getElementById('activeToolsChips');

// Google elements
const googleBadge = document.getElementById('googleBadge');
const googleMetaText = document.getElementById('googleMetaText');
const gmailAutoToggle = document.getElementById('gmailAutoToggle');
const connectGoogleBtn = document.getElementById('connectGoogleBtn');
const runGmailAutoBtn = document.getElementById('runGmailAutoBtn');
const disconnectGoogleBtn = document.getElementById('disconnectGoogleBtn');

// WhatsApp elements
const pairForm = document.getElementById('pairForm');
const phoneInput = document.getElementById('phoneInput');
const pairBtn = document.getElementById('pairBtn');
const statusBadge = document.getElementById('statusBadge');
const waMetaText = document.getElementById('waMetaText');
const waAutoToggle = document.getElementById('waAutoToggle');
const codeSection = document.getElementById('codeSection');
const pairingCodeValue = document.getElementById('pairingCodeValue');
const copyBtn = document.getElementById('copyBtn');
const connectedBox = document.getElementById('connectedBox');
const connectedDetail = document.getElementById('connectedDetail');
const disconnectBtn = document.getElementById('disconnectBtn');

// Custom tool elements
const customBadge = document.getElementById('customBadge');
const customMetaText = document.getElementById('customMetaText');
const customToolForm = document.getElementById('customToolForm');
const customProviderSelect = document.getElementById('customProviderSelect');
const customCredInput = document.getElementById('customCredInput');

// Smart Assistant elements
const smartSyncToggle = document.getElementById('smartSyncToggle');
const assistantForm = document.getElementById('assistantForm');
const assistantInput = document.getElementById('assistantInput');
const assistantOutput = document.getElementById('assistantOutput');

let pollTimer = null;

function currentUserId() {
    return (userIdInput.value || 'tappilot_main').trim();
}

function setFeedback(msg) {
    if (!msg) {
        feedbackText.hidden = true;
        feedbackText.textContent = '';
        return;
    }
    feedbackText.textContent = msg;
    feedbackText.hidden = false;
}

function renderBadge(el, state) {
    el.classList.remove('badge-connected', 'badge-pairing', 'badge-disconnected');
    if (state === 'connected') {
        el.textContent = 'Connected ✅';
        el.classList.add('badge-connected');
    } else if (state === 'pairing') {
        el.textContent = 'Pairing...';
        el.classList.add('badge-pairing');
    } else {
        el.textContent = 'Disconnected';
        el.classList.add('badge-disconnected');
    }
}

async function refreshUnifiedHub() {
    const userId = currentUserId();
    try {
        const resp = await fetch(`/api/connectors/${encodeURIComponent(userId)}`);
        if (!resp.ok) return;
        const data = await resp.json();

        // Active tools from getUserTools(userId)
        activeToolsChips.textContent = '';
        const tools = data.tools || [];
        toolsCountBadge.textContent = `${tools.length} Active`;
        toolsCountBadge.className = `badge ${tools.length > 0 ? 'badge-connected' : 'badge-disconnected'}`;
        if (tools.length === 0) {
            const span = document.createElement('span');
            span.className = 'subtitle';
            span.textContent = 'No connectors active yet — click Connect below.';
            activeToolsChips.appendChild(span);
        } else {
            tools.forEach((t) => {
                const chip = document.createElement('span');
                chip.className = 'tool-chip';
                chip.textContent = t.label;
                activeToolsChips.appendChild(chip);
            });
        }

        const byProvider = new Map((data.connectors || []).map((c) => [c.provider, c]));
        const google = byProvider.get('google');
        if (google?.is_connected) {
            renderBadge(googleBadge, 'connected');
            googleMetaText.textContent = `Connected: ${google.scopes_or_meta?.email || 'Google Workspace'} (Gmail + Calendar + Drive)`;
            connectGoogleBtn.hidden = true;
            runGmailAutoBtn.hidden = false;
            disconnectGoogleBtn.hidden = false;
        } else {
            renderBadge(googleBadge, 'disconnected');
            googleMetaText.textContent = data.googleOAuthConfigured
                ? 'Ready for 1-click Google OAuth 2.0 (Gmail + Calendar + Drive)'
                : `Add GOOGLE_CLIENT_ID & GOOGLE_CLIENT_SECRET in .env (Redirect URI: ${data.expectedRedirectUri || ''})`;
            connectGoogleBtn.hidden = false;
            runGmailAutoBtn.hidden = true;
            disconnectGoogleBtn.hidden = true;
        }
        gmailAutoToggle.checked = Boolean(google?.automation_rules?.gmailAutoReply);

        const wa = byProvider.get('whatsapp');
        waAutoToggle.checked = wa?.automation_rules?.whatsappAutoReply !== false;
        smartSyncToggle.checked = wa?.automation_rules?.smartAssistantSync !== false;

        const tg = byProvider.get('telegram');
        const wh = byProvider.get('webhook');
        if (tg?.is_connected || wh?.is_connected) {
            renderBadge(customBadge, 'connected');
            const labels = [];
            if (tg?.is_connected) labels.push('Telegram Bot');
            if (wh?.is_connected) labels.push(`Webhook (${wh.scopes_or_meta?.host || 'HTTPS'})`);
            customMetaText.textContent = `Connected: ${labels.join(' • ')}`;
        } else {
            renderBadge(customBadge, 'disconnected');
        }
    } catch (_) {}

    await pollWaStatusOnce();
}

async function pollWaStatusOnce() {
    try {
        const resp = await fetch(`/api/whatsapp/status/${encodeURIComponent(currentUserId())}`);
        if (!resp.ok) return;
        const data = await resp.json();
        renderBadge(statusBadge, data.status);
        if (data.status === 'connected') {
            codeSection.hidden = true;
            connectedBox.hidden = false;
            connectedDetail.textContent = `Number: ${data.phoneNumber || 'Linked'} • Replies Sent: ${data.totalRepliesSent || 0}`;
            waMetaText.textContent = `Linked to ${data.phoneNumber || 'WhatsApp'}`;
            stopPolling();
        } else {
            connectedBox.hidden = true;
        }
    } catch (_) {}
}

function startPolling() {
    stopPolling();
    pollTimer = setInterval(refreshUnifiedHub, 2500);
}
function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

connectGoogleBtn.addEventListener('click', async () => {
    setFeedback('');
    try {
        const resp = await fetch(`/api/connectors/google/auth-url?userId=${encodeURIComponent(currentUserId())}`);
        const data = await resp.json();
        if (!resp.ok || !data.authUrl) {
            setFeedback(data.error || 'Google OAuth not configured.');
            return;
        }
        window.open(data.authUrl, '_blank', 'width=520,height=680');
        setFeedback('Complete sign-in in the Google OAuth popup — status will turn Connected ✅ automatically.');
        startPolling();
    } catch (err) {
        setFeedback(err.message);
    }
});

runGmailAutoBtn.addEventListener('click', async () => {
    setFeedback('Scanning unread Gmail & generating AI drafts...');
    try {
        const resp = await fetch('/api/connectors/gmail/run-auto', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: currentUserId() })
        });
        const data = await resp.json();
        setFeedback(data.summary || data.error || 'Done.');
    } catch (err) {
        setFeedback(err.message);
    }
});

disconnectGoogleBtn.addEventListener('click', async () => {
    await fetch('/api/connectors/disconnect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: currentUserId(), provider: 'google' })
    });
    refreshUnifiedHub();
});

gmailAutoToggle.addEventListener('change', async () => {
    await fetch('/api/connectors/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            userId: currentUserId(),
            provider: 'google',
            gmailAutoReply: gmailAutoToggle.checked
        })
    });
    refreshUnifiedHub();
});

waAutoToggle.addEventListener('change', async () => {
    await fetch('/api/connectors/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            userId: currentUserId(),
            provider: 'whatsapp',
            whatsappAutoReply: waAutoToggle.checked
        })
    });
    refreshUnifiedHub();
});

smartSyncToggle.addEventListener('change', async () => {
    await fetch('/api/connectors/rules', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            userId: currentUserId(),
            provider: 'whatsapp',
            smartAssistantSync: smartSyncToggle.checked
        })
    });
});

pairForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    setFeedback('');
    const phoneNumber = phoneInput.value.replace(/\D/g, '');
    if (phoneNumber.length < 10) {
        setFeedback('Enter a valid WhatsApp number with country code (e.g. 88017XXXXXXXX).');
        return;
    }
    pairBtn.disabled = true;
    pairBtn.textContent = 'Generating Pairing Code...';
    try {
        const resp = await fetch('/api/whatsapp/pair', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: currentUserId(), phoneNumber })
        });
        const data = await resp.json();
        if (!resp.ok) {
            setFeedback(data.error || 'Failed to get pairing code.');
            return;
        }
        renderBadge(statusBadge, data.status);
        if (data.pairingCode) {
            pairingCodeValue.textContent = data.pairingCode;
            codeSection.hidden = false;
            startPolling();
        }
    } catch (err) {
        setFeedback(err.message);
    } finally {
        pairBtn.disabled = false;
        pairBtn.textContent = 'Connect WhatsApp / Get Pairing Code';
    }
});

copyBtn.addEventListener('click', async () => {
    const code = pairingCodeValue.textContent.trim();
    if (!code) return;
    await navigator.clipboard.writeText(code);
    copyBtn.textContent = 'Copied!';
    setTimeout(() => { copyBtn.textContent = 'Copy Code'; }, 2000);
});

disconnectBtn.addEventListener('click', async () => {
    await fetch('/api/whatsapp/disconnect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: currentUserId() })
    });
    refreshUnifiedHub();
});

customToolForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    setFeedback('');
    const resp = await fetch('/api/connectors/custom', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            userId: currentUserId(),
            provider: customProviderSelect.value,
            credentialValue: customCredInput.value
        })
    });
    const data = await resp.json();
    if (!resp.ok) {
        setFeedback(data.error || 'Failed to save custom tool.');
        return;
    }
    customCredInput.value = '';
    setFeedback('Tool saved & Connected ✅');
    refreshUnifiedHub();
});

assistantForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const message = assistantInput.value.trim();
    if (!message) return;
    assistantOutput.hidden = false;
    assistantOutput.textContent = 'Thinking with your connected tools...';
    const resp = await fetch('/api/assistant/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: currentUserId(), message })
    });
    const data = await resp.json();
    assistantOutput.textContent = data.reply || data.error || 'No response.';
});

refreshHubBtn.addEventListener('click', refreshUnifiedHub);
refreshUnifiedHub();
