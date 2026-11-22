'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const net = require('net');
const express = require('express');
const cors = require('cors');
const pino = require('pino');
const { Boom } = require('@hapi/boom');
const {
    default: makeWASocket,
    useMultiFileAuthState,
    DisconnectReason,
    fetchLatestBaileysVersion,
    Browsers
} = require('@whiskeysockets/baileys');

const connectorsStore = require('./db/connectorsStore');
const googleService = require('./services/googleService');
const agentTools = require('./services/agentTools');

const PORT = Number(process.env.PORT || 3000);
const BRIDGE_SECRET = (process.env.BRIDGE_SECRET || '').trim();
const ALLOWED_ORIGIN = (process.env.ALLOWED_ORIGIN || '').trim();
const rawStorageDir = (
    process.env.STORAGE_DIR ||
    process.env.RAILWAY_VOLUME_MOUNT_PATH ||
    ''
).trim();
const STORAGE_ROOT = rawStorageDir ? path.resolve(rawStorageDir) : __dirname;
const SESSIONS_ROOT = path.resolve(STORAGE_ROOT, 'sessions');
fs.mkdirSync(SESSIONS_ROOT, { recursive: true });

const app = express();
app.set('trust proxy', 1);
app.use(
    cors(
        ALLOWED_ORIGIN
            ? { origin: ALLOWED_ORIGIN.split(',').map((s) => s.trim()), credentials: false }
            : { origin: true }
    )
);
app.use(express.json({ limit: '512kb' }));
app.use(express.static(path.join(__dirname, 'public')));

// Active Baileys sockets & runtime state keyed by sanitized userId
const sessions = new Map();
// User-specific WhatsApp contact/prompt metadata pushed from TapPilot Android app
const userConfigs = new Map();
// Recent message ID deduplication cache (prevents duplicate replies on reconnect)
const processedMessages = new Map();

function sanitizeUserId(raw) {
    const clean = String(raw || '').trim();
    if (!/^[a-zA-Z0-9_-]{1,40}$/.test(clean)) return null;
    return clean;
}

function getSessionDir(userId) {
    const resolved = path.resolve(SESSIONS_ROOT, userId);
    if (!resolved.startsWith(SESSIONS_ROOT + path.sep)) {
        throw new Error('Invalid session path.');
    }
    fs.mkdirSync(resolved, { recursive: true });
    return resolved;
}

function cleanDigitsPhone(raw) {
    return String(raw || '').replace(/\D/g, '');
}

function isBlockedPrivateOrLoopbackHost(hostname) {
    const host = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '');
    if (!host) return true;
    if (host === 'localhost' || host.endsWith('.local') || host === '::1') return true;
    if (net.isIPv4(host)) {
        const parts = host.split('.').map(Number);
        if (parts[0] === 127 || parts[0] === 10 || parts[0] === 0) return true;
        if (parts[0] === 169 && parts[1] === 254) return true;
        if (parts[0] === 192 && parts[1] === 168) return true;
        if (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) return true;
    }
    if (net.isIPv6(host) && (host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80'))) {
        return true;
    }
    return false;
}

function validateSafeWebhookUrl(rawUrl) {
    try {
        const parsed = new URL(String(rawUrl || '').trim());
        if (parsed.protocol !== 'https:') return null;
        if (isBlockedPrivateOrLoopbackHost(parsed.hostname)) return null;
        return parsed.toString();
    } catch (_) {
        return null;
    }
}

function verifyBridgeSecret(req, res, next) {
    if (!BRIDGE_SECRET) return next();
    const incoming = String(req.header('X-Bridge-Secret') || '').trim();
    const a = Buffer.from(incoming, 'utf8');
    const b = Buffer.from(BRIDGE_SECRET, 'utf8');
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
        return res.status(401).json({ error: 'Unauthorized: invalid X-Bridge-Secret header.' });
    }
    return next();
}

// Per-chat sliding conversation history keyed by `${userId}:${sender}`
const chatHistories = new Map();
// Short debounce buffer to group rapid back-to-back messages from the same sender
const pendingChatBatches = new Map();

const DEFAULT_HUMAN_PROMPT =
    'মানুষের মতো স্বাভাবিকভাবে সংক্ষিপ্ত ও আন্তরিক বাংলা/Banglish-এ রিপ্লাই দাও। নিজেকে কখনো AI, সহকারী বা বট বলবে না; সামনের মানুষ যেভাবে (তুমি/আপনি) কথা বলবে সেভাবেই সহজ ও প্রাসঙ্গিক উত্তর দেবে।';

function normalizeSystemPrompt(rawPrompt) {
    const clean = String(rawPrompt || '').trim();
    if (!clean || clean.includes('ভদ্র ও সহায়ক AI সহকারী')) {
        return DEFAULT_HUMAN_PROMPT;
    }
    return clean;
}

function getOrDefaultConfig(userId) {
    if (!userConfigs.has(userId)) {
        const existingWa = connectorsStore.getConnectorWithCredentials(userId, 'whatsapp');
        userConfigs.set(userId, {
            autoReplyEnabled: existingWa?.automation_rules?.whatsappAutoReply ?? true,
            systemPrompt: normalizeSystemPrompt(existingWa?.automation_rules?.systemPrompt),
            contacts: Array.isArray(existingWa?.automation_rules?.contacts)
                ? existingWa.automation_rules.contacts
                : [],
            totalRepliesSent: 0,
            recentReplies: []
        });
    }
    return userConfigs.get(userId);
}

function getChatHistory(userId, sender) {
    const key = `${userId}:${sender}`;
    return chatHistories.get(key) || [];
}

function appendChatHistory(userId, sender, userMsg, assistantReply) {
    const key = `${userId}:${sender}`;
    const existing = chatHistories.get(key) || [];
    const updated = [
        ...existing,
        { role: 'user', content: String(userMsg || '').slice(0, 600) },
        { role: 'assistant', content: String(assistantReply || '').slice(0, 600) }
    ].slice(-12);
    chatHistories.set(key, updated);
}

function wasRecentlyProcessed(messageId) {
    const now = Date.now();
    for (const [key, ts] of processedMessages.entries()) {
        if (now - ts > 5 * 60 * 1000) processedMessages.delete(key);
    }
    if (!messageId) return false;
    if (processedMessages.has(messageId)) return true;
    processedMessages.set(messageId, now);
    return false;
}

function extractIncomingText(msg) {
    const content = msg?.message;
    if (!content) return '';
    return (
        content.conversation ||
        content.extendedTextMessage?.text ||
        content.imageMessage?.caption ||
        content.videoMessage?.caption ||
        content.buttonsResponseMessage?.selectedDisplayText ||
        content.listResponseMessage?.title ||
        ''
    ).trim();
}

/**
 * Modular AI Agent hook invoked for incoming WhatsApp messages.
 * Automatically leverages connected Google Calendar / Gmail tools if Smart Assistant Sync is ON.
 */
async function runAIAgent({ userId, sender, pushName, text, ownerName = '' }) {
    const cfg = getOrDefaultConfig(userId);
    const senderDigits = sender.split('@')[0] || '';
    const displayName = (pushName || senderDigits || 'Friend').trim();
    const history = getChatHistory(userId, sender);

    const matchedContact = (cfg.contacts || []).find((c) => {
        const contactName = String(c.name || '').trim().toLowerCase();
        if (!contactName) return false;
        return (
            displayName.toLowerCase().includes(contactName) ||
            senderDigits.includes(contactName)
        );
    });

    const relationshipHint = matchedContact
        ? `Relationship Contact Match -> Name: ${matchedContact.name} | Relationship: ${matchedContact.relationship} | Tone/Note: ${matchedContact.styleNote || 'Normal warm human tone'}`
        : `Sender Name: ${displayName} (Number: ${senderDigits})`;

    const effectivePrompt = normalizeSystemPrompt(cfg.systemPrompt);
    const waRecord = connectorsStore.getConnectorWithCredentials(userId, 'whatsapp');
    const smartSyncOn = waRecord?.automation_rules?.smartAssistantSync !== false;

    if (smartSyncOn) {
        const result = await agentTools.runUnifiedAssistant({
            userId,
            message: text,
            channel: 'whatsapp',
            extraContext: `${effectivePrompt}\n${relationshipHint}`,
            history,
            ownerName
        });
        if (result.reply) {
            appendChatHistory(userId, sender, text, result.reply);
        }
        return result.reply;
    }

    const humanDirectPrompt = [
        `You are texting on WhatsApp on behalf of ${ownerName || 'the phone owner'} as a real Bangladeshi human.`,
        'Never say you are an AI or bot. Reply naturally in 1–2 short conversational sentences matching the sender’s tone (তুমি/আপনি).',
        effectivePrompt,
        relationshipHint
    ].join('\n\n');

    const aiReply = await agentTools.callLLM(humanDirectPrompt, text, history);
    if (aiReply) {
        appendChatHistory(userId, sender, text, aiReply);
        return aiReply;
    }

    if (matchedContact) {
        return `হ্যালো ${matchedContact.name}, একটু ব্যস্ত ছিলাম। একটু পর কথা বলছি।`;
    }
    return 'এইতো একটু কাজে আছি, একটু পর রিপ্লাই দিচ্ছি।';
}

async function createBaileysSession(userId, phoneNumberDigits = '') {
    const existing = sessions.get(userId);
    if (existing?.sock && existing.status === 'connected') {
        return existing;
    }

    if (existing?.sock) {
        try {
            existing.sock.end(undefined);
        } catch (_) {}
    }

    const sessionDir = getSessionDir(userId);
    const { state, saveCreds } = await useMultiFileAuthState(sessionDir);
    const { version } = await fetchLatestBaileysVersion();

    const sessionRecord = {
        userId,
        sock: null,
        status: 'pairing',
        phoneNumber: phoneNumberDigits || existing?.phoneNumber || '',
        pairingCode: '',
        reconnectTimer: null
    };
    sessions.set(userId, sessionRecord);

    const sock = makeWASocket({
        version,
        auth: state,
        logger: pino({ level: 'silent' }),
        printQRInTerminal: false,
        browser: Browsers.ubuntu('Chrome'),
        syncFullHistory: false,
        markOnlineOnConnect: true
    });
    sessionRecord.sock = sock;

    sock.ev.on('creds.update', saveCreds);

    sock.ev.on('connection.update', async (update) => {
        const { connection, lastDisconnect } = update;
        if (connection === 'open') {
            sessionRecord.status = 'connected';
            sessionRecord.pairingCode = '';
            const rawJid = sock.user?.id || '';
            const linkedDigits = rawJid.split(':')[0].split('@')[0];
            if (linkedDigits) sessionRecord.phoneNumber = linkedDigits;

            connectorsStore.upsertConnector({
                userId,
                provider: 'whatsapp',
                isConnected: true,
                scopesOrMeta: {
                    phoneNumber: sessionRecord.phoneNumber,
                    authMode: 'baileys_pairing_code'
                },
                credentials: {
                    sessionDirName: userId,
                    linkedJid: rawJid
                }
            });
            console.log(`[WhatsApp][${userId}] Connected ✅ (${sessionRecord.phoneNumber})`);
        } else if (connection === 'close') {
            const statusCode = new Boom(lastDisconnect?.error)?.output?.statusCode;
            const loggedOut = statusCode === DisconnectReason.loggedOut;
            console.log(`[WhatsApp][${userId}] Closed (code=${statusCode}, loggedOut=${loggedOut})`);

            if (sessionRecord.pausedByUser) {
                sessionRecord.status = 'paused';
                clearTimeout(sessionRecord.reconnectTimer);
            } else if (loggedOut) {
                sessionRecord.status = 'disconnected';
                sessionRecord.pairingCode = '';
                fs.rmSync(sessionDir, { recursive: true, force: true });
                sessions.delete(userId);
                connectorsStore.disconnectConnector(userId, 'whatsapp');
            } else {
                sessionRecord.status = state.creds.registered ? 'disconnected' : 'pairing';
                clearTimeout(sessionRecord.reconnectTimer);
                sessionRecord.reconnectTimer = setTimeout(() => {
                    createBaileysSession(userId, sessionRecord.phoneNumber).catch((err) =>
                        console.error(`[WhatsApp][${userId}] Reconnect failed:`, err.message)
                    );
                }, 3000);
            }
        }
    });

    sock.ev.on('messages.upsert', async ({ messages, type }) => {
        if (type !== 'notify') return;
        const cfg = getOrDefaultConfig(userId);
        if (!cfg.autoReplyEnabled) return;

        for (const msg of messages) {
            try {
                if (!msg?.message) continue;
                if (msg.key?.fromMe) continue;
                const sender = msg.key?.remoteJid || '';
                if (!sender || sender === 'status@broadcast' || sender.endsWith('@g.us')) continue;
                if (wasRecentlyProcessed(msg.key?.id)) continue;

                // Skip old backlog messages received before server connected
                const rawTs = Number(msg.messageTimestamp || 0);
                if (rawTs > 0 && Date.now() / 1000 - rawTs > 120) continue;

                const text = extractIncomingText(msg);
                if (!text) continue;

                const batchKey = `${userId}:${sender}`;
                const existingBatch = pendingChatBatches.get(batchKey);
                if (existingBatch) {
                    clearTimeout(existingBatch.timer);
                    existingBatch.texts.push(text);
                    if (msg.pushName) existingBatch.pushName = msg.pushName;
                } else {
                    pendingChatBatches.set(batchKey, {
                        texts: [text],
                        pushName: msg.pushName || '',
                        timer: null
                    });
                }

                const batch = pendingChatBatches.get(batchKey);
                batch.timer = setTimeout(async () => {
                    pendingChatBatches.delete(batchKey);
                    const combinedText = batch.texts.join('\n').trim();
                    if (!combinedText) return;

                    try {
                        try {
                            await sock.sendPresenceUpdate('composing', sender);
                        } catch (_) {}

                        const ownerName = sock.user?.name || '';
                        const reply = await runAIAgent({
                            userId,
                            sender,
                            pushName: batch.pushName,
                            text: combinedText,
                            ownerName
                        });
                        if (!reply) return;

                        await sock.sendMessage(sender, { text: reply });
                        try {
                            await sock.sendPresenceUpdate('paused', sender);
                        } catch (_) {}

                        cfg.totalRepliesSent += 1;
                        cfg.recentReplies = [
                            `${batch.pushName || sender.split('@')[0]}: ${reply.slice(0, 70)}`,
                            ...cfg.recentReplies
                        ].slice(0, 15);
                    } catch (innerErr) {
                        console.error(`[WhatsApp][${userId}] Auto-reply error:`, innerErr.message);
                    }
                }, 1800);
            } catch (err) {
                console.error(`[WhatsApp][${userId}] Message queue error:`, err.message);
            }
        }
    });

    if (!state.creds.registered && phoneNumberDigits) {
        await new Promise((resolve) => setTimeout(resolve, 1500));
        if (typeof sock.requestPairingCode === 'function') {
            const code = await sock.requestPairingCode(phoneNumberDigits);
            sessionRecord.pairingCode = code;
            sessionRecord.status = 'pairing';
        }
    } else if (state.creds.registered) {
        sessionRecord.status = 'connected';
        connectorsStore.upsertConnector({
            userId,
            provider: 'whatsapp',
            isConnected: true,
            scopesOrMeta: { phoneNumber: sessionRecord.phoneNumber || 'Linked' }
        });
    }

    return sessionRecord;
}

// ============================================================================
// UNIFIED CONNECTOR HUB ROUTES
// ============================================================================

// GET /api/connectors/:userId -> returns unified connectors table + getUserTools(userId)
app.get('/api/connectors/:userId', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.params.userId);
    if (!userId) return res.status(400).json({ error: 'Invalid userId.' });

    // Sync live WhatsApp socket state into unified response
    const waRuntime = sessions.get(userId);
    if (waRuntime?.status === 'connected') {
        connectorsStore.upsertConnector({
            userId,
            provider: 'whatsapp',
            isConnected: true,
            scopesOrMeta: { phoneNumber: waRuntime.phoneNumber || 'Linked' }
        });
    }

    const saved = connectorsStore.listConnectorsByUser(userId);
    const byProvider = new Map(saved.map((c) => [c.provider, c]));

    const providers = ['google', 'whatsapp', 'telegram', 'webhook'];
    const connectors = providers.map((provider) => {
        const rec = byProvider.get(provider);
        return (
            rec || {
                user_id: userId,
                provider,
                scopes_or_meta: {},
                automation_rules: connectorsStore.defaultRulesFor(provider),
                is_connected: false,
                updated_at: null
            }
        );
    });

    const tools = agentTools.getUserTools(userId);
    const googleSetup = googleService.getGoogleOAuthConfig(req);

    return res.json({
        userId,
        googleOAuthConfigured: googleSetup.isConfigured,
        expectedRedirectUri: googleSetup.redirectUri,
        connectors,
        tools
    });
});

// GET /api/connectors/google/auth-url?userId=...
app.get('/api/connectors/google/auth-url', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.query?.userId || 'tappilot_main');
    if (!userId) return res.status(400).json({ error: 'Invalid userId.' });

    const result = googleService.buildGoogleAuthUrl(req, userId);
    if (!result.configured) {
        return res.status(400).json({ error: result.error, redirectUri: googleService.getGoogleOAuthConfig(req).redirectUri });
    }
    return res.json({ authUrl: result.authUrl, redirectUri: result.redirectUri });
});

// GET /api/connectors/google/callback -> OAuth 2.0 callback (no X-Bridge-Secret since Google redirects user browser here)
app.get('/api/connectors/google/callback', async (req, res) => {
    const code = String(req.query?.code || '');
    const state = String(req.query?.state || '');
    const oauthErr = String(req.query?.error || '');

    if (oauthErr || !code || !state) {
        return res.status(400).type('html').send(
            '<!DOCTYPE html><html><body style="background:#080d14;color:#ef4444;font-family:sans-serif;text-align:center;padding:40px">' +
            '<h2>Google Connection Cancelled or Invalid</h2>' +
            '<p>Please return to TapPilot and try connecting again.</p>' +
            '</body></html>'
        );
    }

    try {
        await googleService.exchangeCodeAndSave(req, code, state);
        return res.status(200).type('html').send(
            '<!DOCTYPE html><html><body style="background:#080d14;color:#00e5a8;font-family:sans-serif;text-align:center;padding:48px">' +
            '<h1>Google Workspace Connected ✅</h1>' +
            '<p style="color:#e8eef7">Gmail, Google Calendar, and Google Drive tokens saved securely on your server.</p>' +
            '<p style="color:#8fa3bf">You can now close this tab and return to TapPilot.</p>' +
            '<script>setTimeout(function(){ try { window.close(); } catch(e){} }, 1500);</script>' +
            '</body></html>'
        );
    } catch (err) {
        console.error('[Google OAuth Callback Error]', err.message);
        return res.status(500).type('html').send(
            '<!DOCTYPE html><html><body style="background:#080d14;color:#ef4444;font-family:sans-serif;text-align:center;padding:40px">' +
            '<h2>Google Token Exchange Failed</h2>' +
            '<p>Check your GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, and Authorized Redirect URI in Google Cloud Console.</p>' +
            '</body></html>'
        );
    }
});

// POST /api/connectors/rules -> toggles Gmail Auto-Reply / Draft, WhatsApp AI Auto-Reply, Smart Assistant Sync
app.post('/api/connectors/rules', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    const provider = String(req.body?.provider || '').trim().toLowerCase();
    if (!userId || !['google', 'whatsapp', 'telegram', 'webhook'].includes(provider)) {
        return res.status(400).json({ error: 'Valid userId and provider are required.' });
    }

    const partial = {};
    if (typeof req.body?.gmailAutoReply === 'boolean') partial.gmailAutoReply = req.body.gmailAutoReply;
    if (req.body?.gmailReplyMode === 'draft' || req.body?.gmailReplyMode === 'send') {
        partial.gmailReplyMode = req.body.gmailReplyMode;
    }
    if (typeof req.body?.whatsappAutoReply === 'boolean') {
        partial.whatsappAutoReply = req.body.whatsappAutoReply;
        getOrDefaultConfig(userId).autoReplyEnabled = req.body.whatsappAutoReply;
    }
    if (typeof req.body?.calendarAssist === 'boolean') partial.calendarAssist = req.body.calendarAssist;
    if (typeof req.body?.smartAssistantSync === 'boolean') partial.smartAssistantSync = req.body.smartAssistantSync;
    if (typeof req.body?.systemPrompt === 'string' && req.body.systemPrompt.trim()) {
        partial.systemPrompt = req.body.systemPrompt.trim().slice(0, 1500);
        if (provider === 'whatsapp') getOrDefaultConfig(userId).systemPrompt = partial.systemPrompt;
    }

    const updated = connectorsStore.updateAutomationRules(userId, provider, partial);
    return res.json({ ok: true, connector: updated, tools: agentTools.getUserTools(userId) });
});

// POST /api/connectors/custom -> Connect Telegram Bot Token or HTTPS Webhook
app.post('/api/connectors/custom', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    const provider = String(req.body?.provider || '').trim().toLowerCase();
    const label = String(req.body?.label || '').trim().slice(0, 60);
    const credentialValue = String(req.body?.credentialValue || '').trim();

    if (!userId || !['telegram', 'webhook'].includes(provider)) {
        return res.status(400).json({ error: 'Provider must be telegram or webhook.' });
    }
    if (!credentialValue || credentialValue.length < 8) {
        return res.status(400).json({ error: 'Enter a valid Telegram bot token or HTTPS webhook URL.' });
    }

    if (provider === 'webhook') {
        const safeUrl = validateSafeWebhookUrl(credentialValue);
        if (!safeUrl) {
            return res.status(400).json({ error: 'Webhook must be a public HTTPS URL (local/private IPs are blocked).' });
        }
        const saved = connectorsStore.upsertConnector({
            userId,
            provider: 'webhook',
            isConnected: true,
            scopesOrMeta: { label: label || 'Automation Webhook', host: new URL(safeUrl).hostname },
            credentials: { webhookUrl: safeUrl }
        });
        return res.json({ ok: true, connector: saved, tools: agentTools.getUserTools(userId) });
    }

    // Telegram Bot token format check
    if (!/^\d{6,}:[A-Za-z0-9_-]{20,}$/.test(credentialValue)) {
        return res.status(400).json({ error: 'Invalid Telegram Bot Token format (expected 123456:ABC...).' });
    }
    const saved = connectorsStore.upsertConnector({
        userId,
        provider: 'telegram',
        isConnected: true,
        scopesOrMeta: { label: label || 'Telegram Bot' },
        credentials: { botToken: credentialValue }
    });
    return res.json({ ok: true, connector: saved, tools: agentTools.getUserTools(userId) });
});

// POST /api/connectors/disconnect -> Disconnect any provider cleanly
app.post('/api/connectors/disconnect', verifyBridgeSecret, async (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    const provider = String(req.body?.provider || '').trim().toLowerCase();
    if (!userId || !['google', 'whatsapp', 'telegram', 'webhook'].includes(provider)) {
        return res.status(400).json({ error: 'Valid userId and provider are required.' });
    }

    if (provider === 'whatsapp') {
        const record = sessions.get(userId);
        if (record) {
            clearTimeout(record.reconnectTimer);
            try { await record.sock?.logout(); } catch (_) {}
            try { record.sock?.end(undefined); } catch (_) {}
            sessions.delete(userId);
        }
        const sessionDir = getSessionDir(userId);
        fs.rmSync(sessionDir, { recursive: true, force: true });
    }

    const updated = connectorsStore.disconnectConnector(userId, provider);
    return res.json({ ok: true, connector: updated, tools: agentTools.getUserTools(userId) });
});

// POST /api/connectors/gmail/run-auto -> Summarizes latest inbox emails & drafts/sends AI reply
app.post('/api/connectors/gmail/run-auto', verifyBridgeSecret, async (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    if (!userId) return res.status(400).json({ error: 'Invalid userId.' });

    try {
        const googleRec = connectorsStore.getConnectorWithCredentials(userId, 'google');
        if (!googleRec?.is_connected) {
            return res.status(400).json({ error: 'Connect Google Workspace first.' });
        }
        const emails = await googleService.listRecentEmails(userId, 3);
        if (emails.length === 0) {
            return res.json({ processed: 0, results: [], summary: 'ইনবক্সে নতুন কোনো ইমেইল পাওয়া যায়নি।' });
        }

        const sendImmediately = googleRec.automation_rules?.gmailReplyMode === 'send';
        const results = [];
        for (const email of emails.slice(0, 2)) {
            const draftBody =
                (await agentTools.callLLM(
                    googleRec.automation_rules?.systemPrompt || 'Write a short, polite professional email reply.',
                    `From: ${email.from}\nSubject: ${email.subject}\nSnippet: ${email.snippet}`
                )) || `Thank you for reaching out regarding "${email.subject}". I have received your email and will respond shortly.`;

            const action = await googleService.createDraftOrSendReply(userId, {
                to: email.from,
                subject: email.subject.startsWith('Re:') ? email.subject : `Re: ${email.subject}`,
                body: draftBody,
                threadId: email.threadId,
                sendImmediately
            });
            results.push({
                from: email.from,
                subject: email.subject,
                mode: action.mode,
                preview: draftBody.slice(0, 120)
            });
        }

        return res.json({
            processed: results.length,
            results,
            summary: `${results.length}টি ইমেইলের জন্য AI ${sendImmediately ? 'রিপ্লাই পাঠানো' : 'ড্রাফট তৈরি'} হয়েছে।`
        });
    } catch (err) {
        return res.status(500).json({ error: err.message || 'Gmail automation failed.' });
    }
});

// POST /api/assistant/chat -> Smart Assistant powered by getUserTools(userId)
app.post('/api/assistant/chat', verifyBridgeSecret, async (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    const message = String(req.body?.message || '').trim().slice(0, 1000);
    if (!userId || !message) {
        return res.status(400).json({ error: 'userId and message are required.' });
    }

    try {
        const result = await agentTools.runUnifiedAssistant({
            userId,
            message,
            channel: 'assistant'
        });
        return res.json({
            reply: result.reply,
            toolsUsed: result.toolsUsed,
            availableTools: agentTools.getUserTools(userId)
        });
    } catch (err) {
        return res.status(500).json({ error: err.message || 'Assistant failed.' });
    }
});

// ============================================================================
// EXISTING WHATSAPP ROUTES (100% compatible with WhatsAppConnectorDialog)
// ============================================================================

app.post('/api/whatsapp/pair', verifyBridgeSecret, async (req, res) => {
    try {
        const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
        if (!userId) {
            return res.status(400).json({ error: 'Invalid userId (use letters, digits, _ or -).' });
        }
        const cleanNumber = cleanDigitsPhone(req.body?.phoneNumber);
        if (cleanNumber.length < 10 || cleanNumber.length > 15) {
            return res.status(400).json({ error: 'Valid phoneNumber with country code is required (e.g. 88017XXXXXXXX).' });
        }

        const record = await createBaileysSession(userId, cleanNumber);
        return res.json({
            userId,
            status: record.status,
            pairingCode: record.pairingCode || '',
            message:
                record.status === 'connected'
                    ? 'WhatsApp is already connected.'
                    : 'Pairing code ready. Enter it in WhatsApp > Linked Devices.'
        });
    } catch (err) {
        console.error('[POST /api/whatsapp/pair]', err);
        return res.status(500).json({ error: err.message || 'Failed to generate pairing code.' });
    }
});

app.get('/api/whatsapp/status/:userId', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.params.userId);
    if (!userId) {
        return res.status(400).json({ error: 'Invalid userId.' });
    }
    const record = sessions.get(userId);
    const cfg = getOrDefaultConfig(userId);
    let effectiveStatus = record?.status || 'disconnected';
    let phone = record?.phoneNumber || '';

    if (!record) {
        const savedWa = connectorsStore.getConnectorWithCredentials(userId, 'whatsapp');
        const hasCreds = fs.existsSync(path.join(getSessionDir(userId), 'creds.json'));
        if (hasCreds && savedWa?.automation_rules?.whatsappPaused) {
            effectiveStatus = 'paused';
            phone = savedWa?.scopes_or_meta?.phoneNumber || '';
        }
    }

    return res.json({
        userId,
        status: effectiveStatus,
        phoneNumber: phone,
        autoReplyEnabled: cfg.autoReplyEnabled,
        totalRepliesSent: cfg.totalRepliesSent,
        recentReplies: cfg.recentReplies
    });
});

app.post('/api/whatsapp/config', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    if (!userId) {
        return res.status(400).json({ error: 'Invalid userId.' });
    }
    const cfg = getOrDefaultConfig(userId);
    if (typeof req.body?.autoReplyEnabled === 'boolean') {
        cfg.autoReplyEnabled = req.body.autoReplyEnabled;
        connectorsStore.updateAutomationRules(userId, 'whatsapp', {
            whatsappAutoReply: req.body.autoReplyEnabled
        });
    }
    if (typeof req.body?.systemPrompt === 'string' && req.body.systemPrompt.trim()) {
        cfg.systemPrompt = normalizeSystemPrompt(req.body.systemPrompt.trim().slice(0, 1500));
        connectorsStore.updateAutomationRules(userId, 'whatsapp', {
            systemPrompt: cfg.systemPrompt
        });
    }
    if (Array.isArray(req.body?.contacts)) {
        cfg.contacts = req.body.contacts.slice(0, 100).map((c) => ({
            name: String(c?.name || '').slice(0, 80),
            relationship: String(c?.relationship || '').slice(0, 60),
            styleNote: String(c?.styleNote || '').slice(0, 240)
        }));
        connectorsStore.updateAutomationRules(userId, 'whatsapp', {
            contacts: cfg.contacts
        });
    }
    return res.json({ ok: true, userId, autoReplyEnabled: cfg.autoReplyEnabled });
});

app.post('/api/whatsapp/disconnect', verifyBridgeSecret, async (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    if (!userId) {
        return res.status(400).json({ error: 'Invalid userId.' });
    }
    const record = sessions.get(userId);
    if (record) {
        clearTimeout(record.reconnectTimer);
        try { await record.sock?.logout(); } catch (_) {}
        try { record.sock?.end(undefined); } catch (_) {}
        sessions.delete(userId);
    }
    const sessionDir = getSessionDir(userId);
    fs.rmSync(sessionDir, { recursive: true, force: true });
    connectorsStore.disconnectConnector(userId, 'whatsapp');
    return res.json({ ok: true, status: 'disconnected' });
});

// POST /api/whatsapp/sleep -> Closes socket WITHOUT logging out so Railway Free can sleep & save credits
app.post('/api/whatsapp/sleep', verifyBridgeSecret, (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    if (!userId) return res.status(400).json({ error: 'Invalid userId.' });

    const record = sessions.get(userId);
    if (record) {
        record.pausedByUser = true;
        record.status = 'paused';
        clearTimeout(record.reconnectTimer);
        try { record.sock?.end(undefined); } catch (_) {}
        sessions.delete(userId);
    }
    connectorsStore.updateAutomationRules(userId, 'whatsapp', { whatsappPaused: true });
    return res.json({
        ok: true,
        status: 'paused',
        message: 'WhatsApp Bot paused cleanly. Saved session kept — Railway will now sleep to save credits!'
    });
});

// POST /api/whatsapp/resume -> Wakes server and reconnects WhatsApp using saved creds without re-pairing
app.post('/api/whatsapp/resume', verifyBridgeSecret, async (req, res) => {
    const userId = sanitizeUserId(req.body?.userId || 'tappilot_main');
    if (!userId) return res.status(400).json({ error: 'Invalid userId.' });

    const credsFile = path.join(getSessionDir(userId), 'creds.json');
    if (!fs.existsSync(credsFile)) {
        return res.status(400).json({ error: 'No saved WhatsApp session found. Please pair with your number once.' });
    }
    connectorsStore.updateAutomationRules(userId, 'whatsapp', { whatsappPaused: false });
    const record = await createBaileysSession(userId, '');
    record.pausedByUser = false;
    return res.json({
        ok: true,
        status: record.status,
        phoneNumber: record.phoneNumber || '',
        message: 'WhatsApp Bot resumed from saved session!'
    });
});

app.get('/health', (req, res) => {
    res.status(200).json({
        ok: true,
        status: 'up',
        storageDir: STORAGE_ROOT
    });
});

async function restoreActiveSessionsOnBoot() {
    try {
        const entries = fs.readdirSync(SESSIONS_ROOT, { withFileTypes: true });
        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const userId = sanitizeUserId(entry.name);
            if (!userId) continue;
            const credsFile = path.join(getSessionDir(userId), 'creds.json');
            if (!fs.existsSync(credsFile)) continue;

            const savedWa = connectorsStore.getConnectorWithCredentials(userId, 'whatsapp');
            if (savedWa?.automation_rules?.whatsappPaused) {
                console.log(`[Boot][${userId}] Skipping auto-restore (user paused bot).`);
                continue;
            }
            console.log(`[Boot][${userId}] Auto-restoring saved WhatsApp session...`);
            createBaileysSession(userId, '').catch((err) =>
                console.error(`[Boot][${userId}] Restore failed:`, err.message)
            );
        }
    } catch (err) {
        console.error('[Boot] Failed to scan sessions directory:', err.message);
    }
}

app.listen(PORT, '0.0.0.0', () => {
    console.log(`TapPilot Unified Connector & WhatsApp Baileys Server running on port ${PORT}`);
    if (!BRIDGE_SECRET) {
        console.log('Tip: Set BRIDGE_SECRET env var in production to protect API endpoints.');
    }
    restoreActiveSessionsOnBoot();
});
