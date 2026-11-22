'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const rawStorageDir = (
    process.env.STORAGE_DIR ||
    process.env.RAILWAY_VOLUME_MOUNT_PATH ||
    ''
).trim();
const DATA_DIR = rawStorageDir
    ? path.resolve(rawStorageDir, 'data')
    : path.resolve(__dirname, '..', 'data');
const DB_FILE = path.resolve(DATA_DIR, 'connectors.db.json');

fs.mkdirSync(DATA_DIR, { recursive: true });

// Derive symmetric encryption key for stored credentials in connectors.db.json.
// Uses BRIDGE_SECRET or ENCRYPTION_SECRET when set, or an ephemeral in-memory random key.
const RUNTIME_KEY_MATERIAL = (
    process.env.ENCRYPTION_SECRET ||
    process.env.BRIDGE_SECRET ||
    ''
).trim();

const EPHEMERAL_SECRET = crypto.randomBytes(32);
const ENCRYPTION_KEY = RUNTIME_KEY_MATERIAL
    ? crypto.createHash('sha256').update(RUNTIME_KEY_MATERIAL).digest()
    : EPHEMERAL_SECRET;

function encryptCredentials(obj) {
    if (!obj || typeof obj !== 'object') return null;
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', ENCRYPTION_KEY, iv);
    const plaintext = JSON.stringify(obj);
    const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    return {
        v: 1,
        iv: iv.toString('base64'),
        tag: tag.toString('base64'),
        data: encrypted.toString('base64')
    };
}

function decryptCredentials(payload) {
    if (!payload || typeof payload !== 'object') return null;
    // Support plain object fallback only if legacy unencrypted or decryptable
    if (!payload.iv || !payload.tag || !payload.data) return payload;
    try {
        const iv = Buffer.from(payload.iv, 'base64');
        const tag = Buffer.from(payload.tag, 'base64');
        const data = Buffer.from(payload.data, 'base64');
        const decipher = crypto.createDecipheriv('aes-256-gcm', ENCRYPTION_KEY, iv);
        decipher.setAuthTag(tag);
        const decrypted = Buffer.concat([decipher.update(data), decipher.final()]);
        return JSON.parse(decrypted.toString('utf8'));
    } catch (_) {
        return null;
    }
}

function readDb() {
    try {
        if (!fs.existsSync(DB_FILE)) return { connectors: [] };
        const raw = fs.readFileSync(DB_FILE, 'utf8');
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed.connectors)) return { connectors: [] };
        return parsed;
    } catch (_) {
        return { connectors: [] };
    }
}

function writeDb(db) {
    const tmpFile = `${DB_FILE}.tmp`;
    fs.writeFileSync(tmpFile, JSON.stringify(db, null, 2), 'utf8');
    fs.renameSync(tmpFile, DB_FILE);
}

function defaultRulesFor(provider) {
    return {
        gmailAutoReply: false,
        gmailReplyMode: 'draft', // 'draft' | 'send'
        whatsappAutoReply: true,
        calendarAssist: true,
        smartAssistantSync: true,
        systemPrompt:
            provider === 'whatsapp'
                ? 'তুমি একজন ভদ্র ও সহায়ক AI সহকারী। কাস্টমার বা বন্ধুর সাথে প্রাসঙ্গিক, সংক্ষিপ্ত ও স্বাভাবিক বাংলা/Banglish-এ কথা বলো।'
                : 'You are a polite, concise AI productivity assistant helping manage emails, calendar events, and messages.'
    };
}

/**
 * Common unified connectors table:
 * { user_id, provider, scopes_or_meta, credentials, automation_rules, is_connected, updated_at }
 */
function upsertConnector({ userId, provider, scopesOrMeta, credentials, isConnected, automationRules }) {
    const cleanUser = String(userId || '').trim();
    const cleanProvider = String(provider || '').trim().toLowerCase();
    if (!cleanUser || !cleanProvider) {
        throw new Error('userId and provider are required.');
    }

    const db = readDb();
    const idx = db.connectors.findIndex(
        (c) => c.user_id === cleanUser && c.provider === cleanProvider
    );
    const existing = idx >= 0 ? db.connectors[idx] : null;

    const mergedRules = {
        ...defaultRulesFor(cleanProvider),
        ...(existing?.automation_rules || {}),
        ...(automationRules || {})
    };

    const nextRecord = {
        user_id: cleanUser,
        provider: cleanProvider,
        scopes_or_meta:
            scopesOrMeta !== undefined
                ? scopesOrMeta
                : (existing?.scopes_or_meta || {}),
        credentials:
            credentials !== undefined
                ? (credentials === null ? null : encryptCredentials(credentials))
                : (existing?.credentials || null),
        automation_rules: mergedRules,
        is_connected:
            typeof isConnected === 'boolean'
                ? isConnected
                : Boolean(existing?.is_connected),
        updated_at: new Date().toISOString()
    };

    if (idx >= 0) {
        db.connectors[idx] = nextRecord;
    } else {
        db.connectors.push(nextRecord);
    }
    writeDb(db);
    return sanitizeConnector(nextRecord);
}

function getConnectorWithCredentials(userId, provider) {
    const db = readDb();
    const rec = db.connectors.find(
        (c) => c.user_id === userId && c.provider === provider
    );
    if (!rec) return null;
    return {
        ...rec,
        credentials: decryptCredentials(rec.credentials)
    };
}

function listConnectorsByUser(userId) {
    const db = readDb();
    return db.connectors
        .filter((c) => c.user_id === userId)
        .map(sanitizeConnector);
}

function updateAutomationRules(userId, provider, partialRules) {
    const cleanProvider = String(provider || '').trim().toLowerCase();
    return upsertConnector({
        userId,
        provider: cleanProvider,
        automationRules: partialRules
    });
}

function disconnectConnector(userId, provider) {
    const cleanProvider = String(provider || '').trim().toLowerCase();
    return upsertConnector({
        userId,
        provider: cleanProvider,
        credentials: null,
        isConnected: false,
        scopesOrMeta: {}
    });
}

function sanitizeConnector(rec) {
    return {
        user_id: rec.user_id,
        provider: rec.provider,
        scopes_or_meta: rec.scopes_or_meta || {},
        automation_rules: rec.automation_rules || defaultRulesFor(rec.provider),
        is_connected: Boolean(rec.is_connected),
        updated_at: rec.updated_at || null
    };
}

module.exports = {
    upsertConnector,
    getConnectorWithCredentials,
    listConnectorsByUser,
    updateAutomationRules,
    disconnectConnector,
    defaultRulesFor
};
