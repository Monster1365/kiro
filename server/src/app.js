import http from 'node:http';
import crypto from 'node:crypto';
import { safeAnalyze } from './analyzer.js';

// 개인정보 주의: 문자 원문/전화번호/URL 은 로그에 남기지 않는다.
// 서버는 문자 원문을 저장하지 않는다. 분석 결과 메타데이터(위험도/요약)만 TTL 동안 보관한다.

const PHONE_RE = /^01[016789]\d{7,8}$/;
const SHARE_LEVELS = ['MINIMAL', 'WITH_SENDER', 'WITH_EXCERPT'];
const SOURCE_TYPES = ['MANUAL', 'AUTO'];

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const normPhone = (p) => String(p ?? '').replace(/[\s-]/g, '');
const newId = (prefix) => `${prefix}_${crypto.randomBytes(8).toString('hex')}`;

function formatKst(ms) {
  const d = new Date(ms + 9 * 3600 * 1000);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}

/** 공유용 발췌: URL 제거 + 길이 제한 */
export function sanitizeExcerpt(text) {
  return String(text)
    .replace(/(https?:\/\/|www\.)\S+/gi, '[링크 삭제됨]')
    .replace(/\b[\w-]+\.(com|net|kr|co\.kr|me|io|xyz|top|cc)\/\S*/gi, '[링크 삭제됨]')
    .slice(0, 60);
}

export function buildGuardianMessage({ shareLevel, analyzedAt, sender, excerpt }) {
  let msg =
    '[스미싱 예방 알림] 등록된 사용자의 휴대전화에서 스미싱이 의심되는 문자가 확인되었습니다. ' +
    '문자 속 링크를 누르지 않도록 안내해 주세요.' +
    ` (확인 시각 ${formatKst(analyzedAt)})`;
  if ((shareLevel === 'WITH_SENDER' || shareLevel === 'WITH_EXCERPT') && sender) {
    msg += ` 발신번호: ${normPhone(sender)}`;
  }
  if (shareLevel === 'WITH_EXCERPT' && excerpt) {
    msg += ` 내용 일부: "${sanitizeExcerpt(excerpt)}"`;
  }
  return msg;
}

export function createApp({ config, analyzer, sms, now = () => Date.now(), log = () => {} }) {
  const installations = new Map(); // token → installation_id
  const analyses = new Map(); // id → { installationId, risk_level, summary, analyzedAt, expiresAt }
  const guardians = new Map(); // id → guardian
  const alertsByKey = new Map(); // `${inst}:${idempotency}` → alert
  const alertsByEvent = new Map(); // `${analysis}:${guardian}` → alert
  const hits = new Map(); // installation → [timestamps]

  function sweep() {
    const t = now();
    for (const [id, a] of analyses) if (a.expiresAt <= t) analyses.delete(id);
  }

  function rateLimit(installationId) {
    const t = now();
    const arr = (hits.get(installationId) ?? []).filter((x) => t - x < 60_000);
    if (arr.length >= config.rateLimitPerMin) throw new HttpError(429, 'RATE_LIMITED', '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.');
    arr.push(t);
    hits.set(installationId, arr);
  }

  function auth(req) {
    const h = req.headers.authorization ?? '';
    const token = h.startsWith('Bearer ') ? h.slice(7) : '';
    const inst = installations.get(token);
    if (!inst) throw new HttpError(401, 'UNAUTHORIZED', '인증이 필요합니다.');
    rateLimit(inst);
    return inst;
  }

  function ownedGuardian(id, inst) {
    const g = guardians.get(id);
    if (!g || g.installationId !== inst) throw new HttpError(404, 'NOT_FOUND', '보호자를 찾을 수 없습니다.');
    return g;
  }

  const publicGuardian = (g) => ({
    guardian_id: g.id,
    name: g.name,
    relationship: g.relationship ?? null,
    phone_masked: g.phone.slice(0, 3) + '****' + g.phone.slice(-4),
    verification_status: g.status,
    alert_consent: g.alertConsent,
  });

  async function readJson(req) {
    const chunks = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > 16 * 1024) throw new HttpError(413, 'TOO_LARGE', '요청이 너무 큽니다.');
      chunks.push(c);
    }
    if (size === 0) return {};
    try {
      const v = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error();
      return v;
    } catch {
      throw new HttpError(400, 'BAD_JSON', '요청 형식이 올바르지 않습니다.');
    }
  }

  // ---------- 핸들러 ----------
  async function route(req, url) {
    // HttpURLConnection(안드로이드)은 PATCH 를 지원하지 않아 POST + override 헤더를 허용한다.
    const m = req.method === 'POST' && req.headers['x-http-method-override'] === 'PATCH' ? 'PATCH' : req.method;
    const p = url.pathname;

    if (m === 'GET' && p === '/health') return [200, { status: 'ok' }];

    if (m === 'POST' && p === '/api/v1/installations') {
      const id = newId('inst');
      const token = crypto.randomBytes(24).toString('hex');
      installations.set(token, id);
      return [201, { installation_id: id, token }];
    }

    const inst = auth(req);
    sweep();

    // 문자 분석
    if (m === 'POST' && p === '/api/v1/analyses') {
      const b = await readJson(req);
      const body = typeof b.message_body === 'string' ? b.message_body.trim() : '';
      if (!body) throw new HttpError(400, 'EMPTY_MESSAGE', '문자 내용을 입력해 주세요.');
      if (body.length > config.maxBodyChars) throw new HttpError(400, 'MESSAGE_TOO_LONG', '문자가 너무 깁니다.');
      const sender = typeof b.sender === 'string' ? b.sender.trim().slice(0, 40) : undefined;
      const source = SOURCE_TYPES.includes(b.source_type) ? b.source_type : 'MANUAL';

      const result = await safeAnalyze(analyzer, { messageBody: body, sender, sourceType: source });
      const id = newId('analysis');
      const t = now();
      // 원문은 저장하지 않는다.
      analyses.set(id, {
        installationId: inst,
        risk_level: result.risk_level,
        summary: result.summary,
        analyzedAt: t,
        expiresAt: t + config.analysisTtlSec * 1000,
      });
      return [200, { analysis_id: id, ...result }];
    }

    // 보호자 인증 요청 (MVP: 설치당 보호자 1명)
    if (m === 'POST' && p === '/api/v1/guardians/verification-requests') {
      const b = await readJson(req);
      const phone = normPhone(b.phone_number);
      const name = typeof b.name === 'string' ? b.name.trim().slice(0, 30) : '';
      if (!name) throw new HttpError(400, 'INVALID_NAME', '보호자 이름(별칭)을 입력해 주세요.');
      if (!PHONE_RE.test(phone)) throw new HttpError(400, 'INVALID_PHONE', '휴대전화 번호 형식이 올바르지 않습니다.');

      const existing = [...guardians.values()].find((g) => g.installationId === inst);
      const t = now();
      if (existing && existing.phone === phone && t - existing.lastSentAt < config.verifyResendCooldownSec * 1000) {
        throw new HttpError(429, 'RESEND_COOLDOWN', '인증번호를 너무 자주 요청했습니다. 잠시 후 다시 시도해 주세요.');
      }
      if (existing) guardians.delete(existing.id); // 번호 변경/재등록 시 인증 다시 진행

      const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
      const g = {
        id: newId('guardian'),
        installationId: inst,
        name,
        relationship: typeof b.relationship === 'string' ? b.relationship.trim().slice(0, 20) || null : null,
        phone,
        status: 'PENDING',
        // 보호자 수신 동의: 인증번호 SMS 에 동의 안내 포함, 인증 완료 = 수신 동의
        alertConsent: b.alert_consent === true,
        codeHash: sha(code),
        codeExpiresAt: t + config.verifyCodeTtlSec * 1000,
        tries: 0,
        lastSentAt: t,
      };
      guardians.set(g.id, g);
      const r = await sms.send(
        phone,
        `[스미싱 예방] 인증번호 ${code}. 인증하시면 스미싱 의심 문자 발생 시 알림 문자를 받게 됩니다. (${Math.floor(config.verifyCodeTtlSec / 60)}분 내 입력)`,
      );
      if (!r.accepted) {
        guardians.delete(g.id);
        throw new HttpError(502, 'SMS_FAILED', '인증번호 문자를 보내지 못했습니다. 잠시 후 다시 시도해 주세요.');
      }
      const out = { ...publicGuardian(g), expires_in_sec: config.verifyCodeTtlSec };
      if (config.mockExposeCode) out.dev_code = code; // 개발용
      return [200, out];
    }

    if (m === 'POST' && p === '/api/v1/guardians/verifications') {
      const b = await readJson(req);
      const g = ownedGuardian(b.guardian_id, inst);
      if (g.status === 'VERIFIED') return [200, publicGuardian(g)];
      if (g.status === 'FAILED') throw new HttpError(429, 'TOO_MANY_TRIES', '시도 횟수를 초과했습니다. 인증번호를 다시 요청해 주세요.');
      if (now() > g.codeExpiresAt) throw new HttpError(400, 'CODE_EXPIRED', '인증번호가 만료되었습니다. 다시 요청해 주세요.');
      g.tries += 1;
      const ok = typeof b.code === 'string' && crypto.timingSafeEqual(Buffer.from(sha(b.code)), Buffer.from(g.codeHash));
      if (!ok) {
        if (g.tries >= config.verifyMaxTries) g.status = 'FAILED';
        throw new HttpError(400, 'CODE_MISMATCH', '인증번호가 맞지 않습니다.');
      }
      g.status = 'VERIFIED';
      return [200, publicGuardian(g)];
    }

    let mm = p.match(/^\/api\/v1\/guardians\/([\w-]+)$/);
    if (mm) {
      const g = ownedGuardian(mm[1], inst);
      if (m === 'GET') return [200, publicGuardian(g)];
      if (m === 'DELETE') {
        guardians.delete(g.id);
        return [200, { deleted: true }];
      }
      if (m === 'PATCH') {
        const b = await readJson(req);
        if (typeof b.alert_consent === 'boolean') g.alertConsent = b.alert_consent;
        return [200, publicGuardian(g)];
      }
    }

    // 보호자 알림
    if (m === 'POST' && p === '/api/v1/guardian-alerts') {
      const b = await readJson(req);
      const key = typeof b.idempotency_key === 'string' ? b.idempotency_key.slice(0, 100) : '';
      if (!key) throw new HttpError(400, 'MISSING_IDEMPOTENCY_KEY', 'idempotency_key 가 필요합니다.');
      const shareLevel = b.share_level ?? 'MINIMAL';
      if (!SHARE_LEVELS.includes(shareLevel)) throw new HttpError(400, 'INVALID_SHARE_LEVEL', '공유 범위가 올바르지 않습니다.');

      // 같은 키/같은 사건의 재요청 → 기존 결과 반환 (중복 발송 방지)
      const prior = alertsByKey.get(`${inst}:${key}`);
      if (prior) return [200, { ...prior, duplicate: true }];

      const a = analyses.get(b.analysis_id);
      if (!a || a.installationId !== inst) throw new HttpError(404, 'ANALYSIS_NOT_FOUND', '분석 결과를 찾을 수 없습니다.');
      const g = ownedGuardian(b.guardian_id, inst);

      const eventKey = `${b.analysis_id}:${g.id}`;
      const sameEvent = alertsByEvent.get(eventKey);
      if (sameEvent) return [200, { ...sameEvent, duplicate: true }];

      // 서버가 직접 조건을 검증한다. 요청 값만 믿고 임의 번호로 보내지 않는다(번호는 서버 저장값 사용).
      if (a.risk_level !== 'HIGH') throw new HttpError(409, 'NOT_ELIGIBLE', '위험 수준이 높음일 때만 자동 알림을 보냅니다.');
      if (g.status !== 'VERIFIED') throw new HttpError(409, 'GUARDIAN_NOT_VERIFIED', '인증되지 않은 보호자에게는 알림을 보내지 않습니다.');
      if (!g.alertConsent) throw new HttpError(409, 'ALERT_DISABLED', '자동 보호자 알림이 꺼져 있습니다.');

      const text = buildGuardianMessage({
        shareLevel,
        analyzedAt: a.analyzedAt,
        sender: typeof b.sender === 'string' ? b.sender : undefined,
        excerpt: typeof b.excerpt === 'string' ? b.excerpt : undefined,
      });

      const alert = {
        alert_id: newId('alert'),
        analysis_id: b.analysis_id,
        guardian_id: g.id,
        status: 'REQUESTED',
        attempts: 0,
        failure_code: null,
        requested_at: now(),
        completed_at: null,
      };
      // 먼저 등록해 동시 재요청에 의한 중복 발송을 막는다.
      alertsByKey.set(`${inst}:${key}`, alert);
      alertsByEvent.set(eventKey, alert);

      while (alert.attempts < config.alertMaxAttempts) {
        alert.attempts += 1;
        const r = await sms.send(g.phone, text);
        if (r.accepted) {
          alert.status = 'SENT'; // 사업자 접수/전송 상태. 보호자의 열람을 의미하지 않음.
          alert.failure_code = null;
          break;
        }
        alert.status = 'FAILED';
        alert.failure_code = r.code ?? 'PROVIDER_ERROR';
      }
      alert.completed_at = now();
      return [200, { ...alert }];
    }

    throw new HttpError(404, 'NOT_FOUND', '존재하지 않는 경로입니다.');
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    let status;
    let payload;
    try {
      [status, payload] = await route(req, url);
    } catch (e) {
      if (e instanceof HttpError) {
        status = e.status;
        payload = { error: { code: e.code, message: e.message } };
      } else {
        status = 500;
        payload = { error: { code: 'INTERNAL', message: '서버 오류가 발생했습니다.' } };
        log('error', 'unhandled'); // 상세 내용(원문 등)은 기록하지 않음
      }
    }
    log('info', `${req.method} ${url.pathname} ${status}`);
    const data = JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(data);
  });

  return server;
}
