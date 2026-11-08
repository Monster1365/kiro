// 환경 변수 기반 설정
const num = (v, d) => (v !== undefined && v !== '' && !Number.isNaN(Number(v)) ? Number(v) : d);

export function loadConfig(env = process.env) {
  return {
    port: num(env.PORT, 8080),
    analyzer: env.ANALYZER || 'mock',
    smsProvider: env.SMS_PROVIDER || 'mock',
    mockExposeCode: env.MOCK_EXPOSE_CODE === 'true',
    alertMaxAttempts: num(env.ALERT_MAX_ATTEMPTS, 3),
    verifyCodeTtlSec: num(env.VERIFY_CODE_TTL_SEC, 300),
    verifyMaxTries: num(env.VERIFY_MAX_TRIES, 5),
    verifyResendCooldownSec: num(env.VERIFY_RESEND_COOLDOWN_SEC, 60),
    rateLimitPerMin: num(env.RATE_LIMIT_PER_MIN, 30),
    analysisTtlSec: num(env.ANALYSIS_TTL_SEC, 3600),
    maxBodyChars: 2000,
  };
}
