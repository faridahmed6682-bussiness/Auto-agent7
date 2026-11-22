'use strict';

const crypto = require('crypto');
const store = require('../db/connectorsStore');

const GOOGLE_SCOPES = [
    'https://www.googleapis.com/auth/userinfo.email',
    'https://www.googleapis.com/auth/gmail.modify',
    'https://www.googleapis.com/auth/calendar',
    'https://www.googleapis.com/auth/drive.readonly'
];

// Signed OAuth state secret (persisted via BRIDGE_SECRET or ephemeral per process)
const STATE_SECRET = (
    process.env.OAUTH_STATE_SECRET ||
    process.env.BRIDGE_SECRET ||
    crypto.randomBytes(32).toString('hex')
).trim();

function getGoogleOAuthConfig(req) {
    const clientId = (process.env.GOOGLE_CLIENT_ID || '').trim();
    const clientSecret = (process.env.GOOGLE_CLIENT_SECRET || '').trim();
    const configuredBase = (process.env.PUBLIC_BASE_URL || '').trim().replace(/\/+$/, '');
    const proto = req?.headers?.['x-forwarded-proto'] || req?.protocol || 'http';
    const host = req?.headers?.['x-forwarded-host'] || req?.get?.('host') || `localhost:${process.env.PORT || 3000}`;
    const detectedBase = configuredBase || `${proto}://${host}`;
    const redirectUri = (
        process.env.GOOGLE_REDIRECT_URI || `${detectedBase}/api/connectors/google/callback`
    ).trim();

    return {
        clientId,
        clientSecret,
        redirectUri,
        isConfigured: Boolean(clientId && clientSecret)
    };
}

function createSignedState(userId) {
    const payload = Buffer.from(
        JSON.stringify({
            userId,
            nonce: crypto.randomBytes(12).toString('hex'),
            exp: Date.now() + 10 * 60 * 1000
        }),
        'utf8'
    ).toString('base64url');
    const sig = crypto
        .createHmac('sha256', STATE_SECRET)
        .update(payload)
        .digest('base64url');
    return `${payload}.${sig}`;
}

function verifySignedState(stateToken) {
    if (!stateToken || typeof stateToken !== 'string' || !stateToken.includes('.')) return null;
    const [payload, sig] = stateToken.split('.');
    if (!payload || !sig) return null;
    const expected = crypto
        .createHmac('sha256', STATE_SECRET)
        .update(payload)
        .digest('base64url');
    const a = Buffer.from(sig, 'utf8');
    const b = Buffer.from(expected, 'utf8');
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
    try {
        const parsed = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
        if (!parsed?.userId || typeof parsed.exp !== 'number' || Date.now() > parsed.exp) {
            return null;
        }
        return parsed.userId;
    } catch (_) {
        return null;
    }
}

function buildGoogleAuthUrl(req, userId) {
    const cfg = getGoogleOAuthConfig(req);
    if (!cfg.isConfigured) {
        return {
            configured: false,
            error:
                'GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are not set in whatsapp-server/.env yet. Add them from Google Cloud Console -> Credentials.'
        };
    }
    const state = createSignedState(userId);
    const params = new URLSearchParams({
        client_id: cfg.clientId,
        redirect_uri: cfg.redirectUri,
        response_type: 'code',
        scope: GOOGLE_SCOPES.join(' '),
        access_type: 'offline',
        prompt: 'consent',
        include_granted_scopes: 'true',
        state
    });
    return {
        configured: true,
        authUrl: `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`,
        redirectUri: cfg.redirectUri
    };
}

async function exchangeCodeAndSave(req, code, stateToken) {
    const userId = verifySignedState(stateToken);
    if (!userId) {
        throw new Error('Invalid or expired OAuth state parameter.');
    }
    const cfg = getGoogleOAuthConfig(req);
    if (!cfg.isConfigured) {
        throw new Error('Google OAuth client credentials missing on server.');
    }

    const tokenResp = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            code: String(code),
            client_id: cfg.clientId,
            client_secret: cfg.clientSecret,
            redirect_uri: cfg.redirectUri,
            grant_type: 'authorization_code'
        })
    });
    const tokenJson = await tokenResp.json();
    if (!tokenResp.ok || !tokenJson.access_token) {
        throw new Error(tokenJson.error_description || tokenJson.error || 'OAuth token exchange failed.');
    }

    // Fetch authenticated Google email
    let email = '';
    try {
        const profileResp = await fetch('https://www.googleapis.com/oauth2/v2/userinfo', {
            headers: { Authorization: `Bearer ${tokenJson.access_token}` }
        });
        if (profileResp.ok) {
            const profile = await profileResp.json();
            email = String(profile.email || '');
        }
    } catch (_) {}

    const existing = store.getConnectorWithCredentials(userId, 'google');
    const refreshToken = tokenJson.refresh_token || existing?.credentials?.refresh_token || '';
    const expiresInSec = Number(tokenJson.expires_in || 3600);

    const saved = store.upsertConnector({
        userId,
        provider: 'google',
        isConnected: true,
        scopesOrMeta: {
            email,
            services: ['gmail', 'calendar', 'drive'],
            scopes: String(tokenJson.scope || GOOGLE_SCOPES.join(' ')).split(/\s+/).filter(Boolean)
        },
        credentials: {
            access_token: tokenJson.access_token,
            refresh_token: refreshToken,
            expiry_date: Date.now() + expiresInSec * 1000
        }
    });

    return saved;
}

async function getValidAccessToken(userId) {
    const rec = store.getConnectorWithCredentials(userId, 'google');
    if (!rec || !rec.is_connected || !rec.credentials?.access_token) {
        throw new Error('Google connector is not linked for this user.');
    }
    const creds = rec.credentials;
    if (!creds.expiry_date || Date.now() < creds.expiry_date - 60_000) {
        return creds.access_token;
    }
    if (!creds.refresh_token) {
        throw new Error('Google access token expired and no refresh token is stored. Reconnect Google.');
    }

    const cfg = getGoogleOAuthConfig(null);
    if (!cfg.isConfigured) {
        throw new Error('GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET missing for token refresh.');
    }

    const resp = await fetch('https://oauth2.googleapis.com/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            client_id: cfg.clientId,
            client_secret: cfg.clientSecret,
            refresh_token: creds.refresh_token,
            grant_type: 'refresh_token'
        })
    });
    const refreshed = await resp.json();
    if (!resp.ok || !refreshed.access_token) {
        throw new Error(refreshed.error_description || 'Failed to refresh Google access token.');
    }

    store.upsertConnector({
        userId,
        provider: 'google',
        isConnected: true,
        credentials: {
            access_token: refreshed.access_token,
            refresh_token: creds.refresh_token,
            expiry_date: Date.now() + Number(refreshed.expires_in || 3600) * 1000
        }
    });
    return refreshed.access_token;
}

async function listRecentEmails(userId, maxResults = 5) {
    const token = await getValidAccessToken(userId);
    const listUrl = `https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=${Math.min(maxResults, 10)}&q=${encodeURIComponent('in:inbox -from:me')}`;
    const listResp = await fetch(listUrl, {
        headers: { Authorization: `Bearer ${token}` }
    });
    const listJson = await listResp.json();
    if (!listResp.ok) {
        throw new Error(listJson.error?.message || 'Failed to list Gmail messages.');
    }
    const messages = listJson.messages || [];
    const results = [];
    for (const item of messages.slice(0, maxResults)) {
        const msgResp = await fetch(
            `https://gmail.googleapis.com/gmail/v1/users/me/messages/${encodeURIComponent(item.id)}?format=metadata&metadataHeaders=From&metadataHeaders=Subject`,
            { headers: { Authorization: `Bearer ${token}` } }
        );
        if (!msgResp.ok) continue;
        const msgData = await msgResp.json();
        const headers = msgData.payload?.headers || [];
        const from = headers.find((h) => h.name === 'From')?.value || '';
        const subject = headers.find((h) => h.name === 'Subject')?.value || '(No Subject)';
        results.push({
            id: msgData.id,
            threadId: msgData.threadId,
            from,
            subject,
            snippet: msgData.snippet || ''
        });
    }
    return results;
}

function buildRawMimeEmail({ to, subject, body, threadId }) {
    const lines = [
        `To: ${to}`,
        `Subject: =?UTF-8?B?${Buffer.from(subject, 'utf8').toString('base64')}?=`,
        'MIME-Version: 1.0',
        'Content-Type: text/plain; charset="UTF-8"',
        '',
        body
    ];
    return Buffer.from(lines.join('\r\n'), 'utf8').toString('base64url');
}

async function createDraftOrSendReply(userId, { to, subject, body, threadId, sendImmediately = false }) {
    const token = await getValidAccessToken(userId);
    const raw = buildRawMimeEmail({ to, subject, body, threadId });
    const endpoint = sendImmediately
        ? 'https://gmail.googleapis.com/gmail/v1/users/me/messages/send'
        : 'https://gmail.googleapis.com/gmail/v1/users/me/drafts';
    const payload = sendImmediately
        ? { raw, threadId }
        : { message: { raw, threadId } };

    const resp = await fetch(endpoint, {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
    });
    const data = await resp.json();
    if (!resp.ok) {
        throw new Error(data.error?.message || 'Failed to create Gmail draft/reply.');
    }
    return {
        mode: sendImmediately ? 'sent' : 'draft',
        id: data.id
    };
}

async function listUpcomingEvents(userId, maxResults = 5) {
    const token = await getValidAccessToken(userId);
    const nowIso = new Date().toISOString();
    const url = `https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin=${encodeURIComponent(nowIso)}&singleEvents=true&orderBy=startTime&maxResults=${Math.min(maxResults, 10)}`;
    const resp = await fetch(url, {
        headers: { Authorization: `Bearer ${token}` }
    });
    const data = await resp.json();
    if (!resp.ok) {
        throw new Error(data.error?.message || 'Failed to read Google Calendar.');
    }
    return (data.items || []).map((ev) => ({
        id: ev.id,
        summary: ev.summary || '(Untitled Event)',
        start: ev.start?.dateTime || ev.start?.date || '',
        end: ev.end?.dateTime || ev.end?.date || ''
    }));
}

async function createCalendarEvent(userId, { summary, startIso, endIso, description = '' }) {
    const token = await getValidAccessToken(userId);
    const resp = await fetch(
        'https://www.googleapis.com/calendar/v3/calendars/primary/events',
        {
            method: 'POST',
            headers: {
                Authorization: `Bearer ${token}`,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                summary,
                description,
                start: { dateTime: startIso },
                end: { dateTime: endIso }
            })
        }
    );
    const data = await resp.json();
    if (!resp.ok) {
        throw new Error(data.error?.message || 'Failed to create Google Calendar event.');
    }
    return { id: data.id, summary: data.summary, htmlLink: data.htmlLink || '' };
}

async function searchDriveFiles(userId, query = '') {
    const token = await getValidAccessToken(userId);
    const safeQ = query
        ? `name contains '${String(query).replace(/['\\]/g, '')}' and trashed = false`
        : 'trashed = false';
    const url = `https://www.googleapis.com/drive/v3/files?pageSize=6&fields=files(id,name,mimeType,webViewLink)&q=${encodeURIComponent(safeQ)}`;
    const resp = await fetch(url, {
        headers: { Authorization: `Bearer ${token}` }
    });
    const data = await resp.json();
    if (!resp.ok) {
        throw new Error(data.error?.message || 'Failed to query Google Drive.');
    }
    return data.files || [];
}

module.exports = {
    GOOGLE_SCOPES,
    getGoogleOAuthConfig,
    buildGoogleAuthUrl,
    exchangeCodeAndSave,
    listRecentEmails,
    createDraftOrSendReply,
    listUpcomingEvents,
    createCalendarEvent,
    searchDriveFiles
};
