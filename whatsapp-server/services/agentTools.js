'use strict';

const store = require('../db/connectorsStore');
const googleService = require('./googleService');

/**
 * Dynamically inspects the unified connectors store and returns the enabled AI tools
 * for the given userId.
 */
function getUserTools(userId) {
    const records = store.listConnectorsByUser(userId);
    const byProvider = new Map(records.map((r) => [r.provider, r]));

    const tools = [];
    const google = byProvider.get('google');
    if (google?.is_connected) {
        const scopes = google.scopes_or_meta?.scopes || [];
        const hasGmail = scopes.length === 0 || scopes.some((s) => s.includes('gmail'));
        const hasCal = scopes.length === 0 || scopes.some((s) => s.includes('calendar'));
        const hasDrive = scopes.length === 0 || scopes.some((s) => s.includes('drive'));

        if (hasGmail) {
            tools.push({
                id: 'gmail_read_summary',
                provider: 'google',
                label: 'Gmail Read & AI Summary',
                autoEnabled: Boolean(google.automation_rules?.gmailAutoReply)
            });
            tools.push({
                id: 'gmail_draft_reply',
                provider: 'google',
                label: `Gmail Auto-${google.automation_rules?.gmailReplyMode === 'send' ? 'Send' : 'Draft'}`,
                autoEnabled: Boolean(google.automation_rules?.gmailAutoReply)
            });
        }
        if (hasCal) {
            tools.push({
                id: 'calendar_schedule',
                provider: 'google',
                label: 'Google Calendar Check & Booking',
                autoEnabled: Boolean(google.automation_rules?.calendarAssist)
            });
        }
        if (hasDrive) {
            tools.push({
                id: 'drive_search',
                provider: 'google',
                label: 'Google Drive File Lookup',
                autoEnabled: true
            });
        }
    }

    const whatsapp = byProvider.get('whatsapp');
    if (whatsapp?.is_connected) {
        tools.push({
            id: 'whatsapp_auto_reply',
            provider: 'whatsapp',
            label: 'WhatsApp 24/7 AI Auto-Reply',
            autoEnabled: Boolean(whatsapp.automation_rules?.whatsappAutoReply)
        });
    }

    const telegram = byProvider.get('telegram');
    if (telegram?.is_connected) {
        tools.push({
            id: 'telegram_notify',
            provider: 'telegram',
            label: 'Telegram Bot Alert & Forward',
            autoEnabled: true
        });
    }

    const webhook = byProvider.get('webhook');
    if (webhook?.is_connected) {
        tools.push({
            id: 'custom_webhook',
            provider: 'webhook',
            label: 'Custom Automation Webhook',
            autoEnabled: true
        });
    }

    return tools;
}

const keyCooldownUntil = new Map();
const modelCooldownUntil = new Map();
let openRouterRotationOffset = 0;
let groqRotationOffset = 0;

function parseApiKeyList(...rawValues) {
    const unique = new Set();
    for (const raw of rawValues) {
        if (!raw) continue;
        const parts = String(raw)
            .split(/[\s,;]+/)
            .map((s) => s.trim())
            .filter(Boolean);
        for (const p of parts) {
            unique.add(p);
        }
    }
    return Array.from(unique);
}

function getAvailableKeys(keys, offset = 0) {
    if (!keys.length) return [];
    const now = Date.now();
    const rotated = keys.map((_, i) => keys[(i + offset) % keys.length]);
    const ready = rotated.filter((k) => (keyCooldownUntil.get(k) || 0) <= now);
    return ready.length ? ready : rotated;
}

function markKeyCooldown(key, statusCode) {
    const durationMs =
        statusCode === 401 || statusCode === 402 || statusCode === 403
            ? 30 * 60 * 1000
            : 5 * 60 * 1000;
    keyCooldownUntil.set(key, Date.now() + durationMs);
}

function cleanModelReply(raw) {
    if (!raw) return null;
    let text = String(raw)
        .replace(/<think>[\s\S]*?<\/think>/gi, '')
        .replace(/^\s*["“]+|["”]+\s*$/g, '')
        .trim();
    if (!text) return null;
    return text;
}

async function callOpenRouterWithKey(apiKey, model, systemInstruction, userPrompt, history = []) {
    const safeHistory = Array.isArray(history)
        ? history
              .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && m.content)
              .slice(-12)
              .map((m) => ({ role: m.role, content: String(m.content).slice(0, 800) }))
        : [];

    const resp = await fetch('https://openrouter.ai/api/v1/chat/completions', {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${apiKey}`,
            'Content-Type': 'application/json',
            'X-Title': 'TapPilot Agent'
        },
        body: JSON.stringify({
            model,
            messages: [
                { role: 'system', content: systemInstruction },
                ...safeHistory,
                { role: 'user', content: userPrompt }
            ],
            max_tokens: 600,
            temperature: 0.65
        })
    });
    if (!resp.ok) {
        return { ok: false, status: resp.status, content: null };
    }
    const data = await resp.json();
    const rawContent = data?.choices?.[0]?.message?.content;
    const content = cleanModelReply(rawContent);
    return { ok: Boolean(content), status: resp.status, content };
}

async function callGroqWithKey(apiKey, model, systemInstruction, userPrompt, history = []) {
    const safeHistory = Array.isArray(history)
        ? history
              .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && m.content)
              .slice(-12)
              .map((m) => ({ role: m.role, content: String(m.content).slice(0, 800) }))
        : [];

    const resp = await fetch('https://api.groq.com/openai/v1/chat/completions', {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${apiKey}`,
            'Content-Type': 'application/json',
            'X-Title': 'TapPilot Agent'
        },
        body: JSON.stringify({
            model,
            messages: [
                { role: 'system', content: systemInstruction },
                ...safeHistory,
                { role: 'user', content: userPrompt }
            ],
            max_tokens: 600,
            temperature: 0.65,
            reasoning_effort: 'low'
        })
    });
    if (!resp.ok) {
        return { ok: false, status: resp.status, content: null };
    }
    const data = await resp.json();
    const rawContent = data?.choices?.[0]?.message?.content;
    const content = cleanModelReply(rawContent);
    return { ok: Boolean(content), status: resp.status, content };
}

async function callLLM(systemInstruction, userPrompt, history = []) {
    // Groq Cloud first (fast, free tier) — same keys as the Munsi Store bot.
    const groqKeys = parseApiKeyList(
        process.env.GROQ_API_KEYS,
        process.env.GROQ_API_KEY
    );
    if (groqKeys.length > 0) {
        const groqModel = (process.env.GROQ_MODEL || 'openai/gpt-oss-120b').trim();
        const candidateKeys = getAvailableKeys(groqKeys, groqRotationOffset);
        groqRotationOffset = (groqRotationOffset + 1) % groqKeys.length;

        for (const apiKey of candidateKeys) {
            try {
                const result = await callGroqWithKey(
                    apiKey,
                    groqModel,
                    systemInstruction,
                    userPrompt,
                    history
                );
                if (result.ok && result.content) {
                    keyCooldownUntil.delete(apiKey);
                    return result.content;
                }
                if (result.status === 401 || result.status === 402 || result.status === 403 || result.status === 429) {
                    markKeyCooldown(apiKey, result.status);
                }
            } catch (err) {
                console.error(`[Groq Failover][${groqModel}]`, err.message);
                markKeyCooldown(apiKey, 500);
            }
        }
    }

    const openRouterKeys = parseApiKeyList(
        process.env.OPENROUTER_API_KEYS,
        process.env.OPENROUTER_API_KEY
    );
    if (openRouterKeys.length > 0) {
        const candidateKeys = getAvailableKeys(openRouterKeys, openRouterRotationOffset);
        openRouterRotationOffset = (openRouterRotationOffset + 1) % openRouterKeys.length;

        const configuredModel = (process.env.OPENROUTER_MODEL || '').trim();
        const preferredBanglaFreeModels = [
            'google/gemini-2.0-flash-exp:free',
            'google/gemma-3-27b-it:free',
            'meta-llama/llama-3.3-70b-instruct:free',
            'qwen/qwen-2.5-72b-instruct:free',
            'openrouter/free'
        ];
        const rawModels =
            configuredModel && configuredModel !== 'openrouter/free'
                ? [configuredModel, ...preferredBanglaFreeModels]
                : preferredBanglaFreeModels;
        const now = Date.now();
        const availableModels = Array.from(new Set(rawModels)).filter(
            (m) => (modelCooldownUntil.get(m) || 0) <= now
        );
        const modelsToTry = availableModels.length ? availableModels : ['openrouter/free'];

        for (const apiKey of candidateKeys) {
            let keyFailed = false;
            for (const model of modelsToTry.slice(0, 4)) {
                try {
                    const result = await callOpenRouterWithKey(
                        apiKey,
                        model,
                        systemInstruction,
                        userPrompt,
                        history
                    );
                    if (result.ok && result.content) {
                        keyCooldownUntil.delete(apiKey);
                        return result.content;
                    }
                    if (result.status === 404 || result.status === 400) {
                        // Model slug unavailable/retired; skip model without blaming key
                        modelCooldownUntil.set(model, Date.now() + 60 * 60 * 1000);
                        continue;
                    }
                    if (result.status === 401 || result.status === 402 || result.status === 403 || result.status === 429) {
                        markKeyCooldown(apiKey, result.status);
                        keyFailed = true;
                        break;
                    }
                } catch (err) {
                    console.error(`[OpenRouter Failover][${model}]`, err.message);
                }
            }
            if (!keyFailed) {
                markKeyCooldown(apiKey, 500);
            }
        }
    }

    const geminiKeys = parseApiKeyList(
        process.env.GEMINI_API_KEYS,
        process.env.GEMINI_API_KEY
    );
    if (geminiKeys.length > 0) {
        const model = process.env.GEMINI_MODEL || 'gemini-2.5-flash';
        const geminiHistory = Array.isArray(history)
            ? history
                  .filter((m) => m && (m.role === 'user' || m.role === 'assistant') && m.content)
                  .slice(-12)
                  .map((m) => ({
                      role: m.role === 'assistant' ? 'model' : 'user',
                      parts: [{ text: String(m.content).slice(0, 800) }]
                  }))
            : [];

        for (const geminiKey of getAvailableKeys(geminiKeys)) {
            try {
                const resp = await fetch(
                    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(geminiKey)}`,
                    {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            systemInstruction: { parts: [{ text: systemInstruction }] },
                            contents: [
                                ...geminiHistory,
                                { role: 'user', parts: [{ text: userPrompt }] }
                            ],
                            generationConfig: { maxOutputTokens: 600, temperature: 0.65 }
                        })
                    }
                );
                if (resp.ok) {
                    const data = await resp.json();
                    const content = cleanModelReply(
                        data?.candidates?.[0]?.content?.parts?.[0]?.text
                    );
                    if (content) {
                        keyCooldownUntil.delete(geminiKey);
                        return content;
                    }
                }
                markKeyCooldown(geminiKey, resp.status);
            } catch (err) {
                console.error('[Gemini Failover]', err.message);
                markKeyCooldown(geminiKey, 500);
            }
        }
    }

    return null;
}

/**
 * Runs Smart Assistant or WhatsApp-to-Google combo automation using the user's connected tools.
 */
async function runUnifiedAssistant({
    userId,
    message,
    channel = 'assistant',
    extraContext = '',
    history = [],
    ownerName = ''
}) {
    const tools = getUserTools(userId);
    const hasCal = tools.some((t) => t.id === 'calendar_schedule');
    const hasGmail = tools.some((t) => t.id === 'gmail_read_summary');
    const hasDrive = tools.some((t) => t.id === 'drive_search');

    const gatheredContext = [];
    const toolsUsed = [];
    const textLower = String(message || '').toLowerCase();

    const calPattern =
        channel === 'whatsapp'
            ? /(meeting|schedule|calendar|appointment|মিটিং|শিডিউল|ক্যালেন্ডার|বৈঠক)/i
            : /(meeting|schedule|calendar|free|time|busy|মিটিং|সময়|ক্যালেন্ডার|কখন|ফ্রি)/i;

    if (hasCal && calPattern.test(textLower)) {
        try {
            const events = await googleService.listUpcomingEvents(userId, 5);
            toolsUsed.push('calendar_schedule');
            gatheredContext.push(
                events.length
                    ? `Upcoming Google Calendar Events:\n${events.map((e) => `- ${e.summary} (${e.start})`).join('\n')}`
                    : 'Upcoming Google Calendar Events: No upcoming meetings scheduled.'
            );
        } catch (err) {
            gatheredContext.push(`Calendar check note: ${err.message}`);
        }
    }

    if (
        hasGmail &&
        /(email|gmail|mail|inbox|ইমেইল|মেইল|ইনবক্স)/i.test(textLower)
    ) {
        try {
            const emails = await googleService.listRecentEmails(userId, 4);
            toolsUsed.push('gmail_read_summary');
            gatheredContext.push(
                emails.length
                    ? `Recent Gmail Inbox:\n${emails.map((m) => `- From: ${m.from} | Subject: ${m.subject} | Snippet: ${m.snippet}`).join('\n')}`
                    : 'Recent Gmail Inbox: No unread/recent inbox messages.'
            );
        } catch (err) {
            gatheredContext.push(`Gmail check note: ${err.message}`);
        }
    }

    if (
        hasDrive &&
        /(google drive|drive|ড্রাইভ)/i.test(textLower)
    ) {
        try {
            const files = await googleService.searchDriveFiles(userId, '');
            toolsUsed.push('drive_search');
            gatheredContext.push(
                files.length
                    ? `Recent Google Drive Files:\n${files.map((f) => `- ${f.name} (${f.mimeType})`).join('\n')}`
                    : 'Google Drive: No matching files found.'
            );
        } catch (err) {
            gatheredContext.push(`Drive check note: ${err.message}`);
        }
    }

    const resolvedOwner = (ownerName || process.env.OWNER_NAME || '').trim();
    const whatsappHumanSystemPrompt = [
        `You are texting on WhatsApp on behalf of ${resolvedOwner || 'the phone owner'} as a real Bangladeshi human friend/person.`,
        'CRITICAL RULES (follow strictly):',
        '1. NEVER say you are an AI, assistant, or bot, and NEVER offer generic help menus like "আমি কীভাবে সাহায্য করতে পারি?", "ইমেইল লেখা", "ক্যালেন্ডার", or "ড্রাইভ" unless the sender explicitly asked about an email/meeting/file.',
        '2. Reply in natural, everyday conversational Bangladeshi Bengali (or Banglish/English ONLY if the sender wrote in Banglish/English).',
        '3. Keep replies short and natural like real WhatsApp texting (1–2 brief sentences, no bullet points).',
        '4. Match the sender’s pronoun & warmth: if they say "তুমি/কি করো/তোমার নাম কি", answer casually with "আমি/তুমি" (e.g., "এইতো তেমন কিছু না, বসে আছি। তুমি কী করো?"). Only use "আপনি" if they address you with "আপনি" or are a formal client.',
        '5. Use the conversation history so your answer directly responds to what they just asked. Complete every sentence cleanly — never cut off mid-sentence and never translate idioms literally.',
        extraContext,
        gatheredContext.length ? `Relevant info you checked:\n${gatheredContext.join('\n\n')}` : ''
    ]
        .filter(Boolean)
        .join('\n\n');

    const assistantSystemPrompt = [
        'You are TapPilot Unified AI Assistant.',
        `Enabled user tools: ${tools.map((t) => t.id).join(', ') || 'none'}.`,
        'Reply naturally in the user language (Bengali/Banglish/English). Be concise, accurate, and mention real tool findings when available.',
        extraContext,
        gatheredContext.join('\n\n')
    ]
        .filter(Boolean)
        .join('\n\n');

    const systemPrompt =
        channel === 'whatsapp' ? whatsappHumanSystemPrompt : assistantSystemPrompt;

    const aiReply = await callLLM(systemPrompt, message, history);
    if (aiReply) {
        return { reply: aiReply, toolsUsed, contextSummary: gatheredContext };
    }

    if (gatheredContext.length > 0) {
        return {
            reply: `আপনার কানেক্ট থাকা টুল থেকে পাওয়া তথ্য:\n\n${gatheredContext.join('\n\n')}`,
            toolsUsed,
            contextSummary: gatheredContext
        };
    }

    return {
        reply:
            channel === 'whatsapp'
                ? 'এইতো ব্যস্ত ছিলাম একটু, একটু পর মেসেজ দিচ্ছি।'
                : `সক্রিয় টুলগুলো (${tools.map((t) => t.label).join(', ') || 'কোনোটি কানেক্ট নেই'}): লাইভ AI রিপ্লাইয়ের জন্য সার্ভারে GROQ_API_KEY, GEMINI_API_KEY বা OPENROUTER_API_KEY সেট করুন।`,
        toolsUsed,
        contextSummary: gatheredContext
    };
}

module.exports = {
    getUserTools,
    callLLM,
    runUnifiedAssistant
};
