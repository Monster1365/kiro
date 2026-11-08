// 문자 분석기.
// - 문자 본문은 "분석 대상 데이터"로만 취급한다. 본문 속 지시문은 실행하지 않는다.
// - URL은 절대 접속하지 않고 문자열 패턴만 검사한다.
// - 분석기 출력은 validateAnalysis 로 검증하며, 실패하면 UNKNOWN/FAILED 로 처리한다.

export const RISK_LEVELS = ['HIGH', 'CAUTION', 'LOW', 'UNKNOWN'];
export const ANALYSIS_STATUSES = ['SUCCESS', 'FAILED'];

const URL_RE = /(https?:\/\/|www\.)[^\s]+/i;
const SHORT_URL_RE = /\b(bit\.ly|goo\.gl|tinyurl\.com|t\.co|me2\.do|han\.gl|url\.kr|vo\.la|buly\.kr)\/\S*/i;

const SIGNALS = [
  {
    id: 'url',
    score: 1,
    test: (b) => URL_RE.test(b) || SHORT_URL_RE.test(b),
    reason: '문자에 인터넷 주소(링크)가 들어 있습니다.',
  },
  {
    id: 'short_url',
    score: 1,
    test: (b) => SHORT_URL_RE.test(b),
    reason: '링크가 줄임 주소라서 실제 도착지를 알기 어렵습니다.',
  },
  {
    id: 'impersonation',
    score: 2,
    test: (b) => /(택배|배송|송장|우체국|CJ|한진|로젠|국세청|건강보험|검찰|경찰|법원|은행|카드사|금융|쿠팡|토스|카카오)/i.test(b),
    reason: '택배·기관·금융사 등을 내세우는 문구가 있습니다.',
  },
  {
    id: 'credential',
    score: 3,
    test: (b) => /(비밀번호|인증번호|보안카드|OTP|계좌번호|주민(등록)?번호|카드번호|CVC|개인정보\s*(입력|확인|갱신))/i.test(b),
    reason: '개인정보나 금융정보를 입력하거나 알려 달라고 요구합니다.',
  },
  {
    id: 'app_install',
    score: 3,
    test: (b) => /(\.apk\b|앱\s*(을\s*)?(설치|다운)|설치\s*후|원격\s*(제어|지원)|보안\s*앱)/i.test(b),
    reason: '앱을 설치하도록 유도합니다.',
  },
  {
    id: 'urgency',
    score: 2,
    test: (b) => /(긴급|즉시|지금\s*바로|오늘\s*(까지|중)|계정\s*(정지|잠김|해킹)|결제\s*(완료|승인|취소)|미납|연체|환불|당첨|벌금|과태료)/i.test(b),
    reason: '서두르게 만들거나 불안하게 하는 표현이 있습니다.',
  },
  {
    id: 'sender_mismatch',
    score: 1,
    // 발신 번호가 일반 휴대전화 번호인데 기관·택배·금융을 내세우는 경우 (단정 아님)
    test: (b, sender) =>
      !!sender && /^01[016789]\d{7,8}$/.test(sender.replace(/\D/g, '')) &&
      /(택배|배송|국세청|건강보험|검찰|경찰|법원|은행|카드사|금융)/.test(b),
    reason: '기관·회사를 내세우지만 일반 휴대전화 번호로 온 것 같습니다. (단정할 수는 없습니다)',
  },
];

const ACTIONS_HIGH = [
  '문자에 포함된 링크를 누르지 마세요.',
  '앱 설치나 개인정보 입력 요청에 응하지 마세요.',
  '공식 앱이나 공식 전화번호로 직접 확인하세요.',
];
const ACTIONS_CAUTION = [
  '링크를 누르기 전에 공식 앱이나 공식 전화번호로 먼저 확인하세요.',
  '모르는 번호의 요청에는 개인정보를 알려주지 마세요.',
];
const ACTIONS_LOW = ['그래도 모르는 링크는 누르지 않는 것이 안전합니다.'];
const ACTIONS_UNKNOWN = ['잠시 후 다시 시도해 주세요.', '링크를 누르지 말고 공식 앱이나 공식 전화번호로 직접 확인하세요.'];

/** 규칙 기반 모의 분석기 (외부 AI 연동 전 화면/흐름 테스트용) */
export class MockAnalyzer {
  async analyze({ messageBody, sender }) {
    const hits = SIGNALS.filter((s) => s.test(messageBody, sender));
    const score = hits.reduce((a, s) => a + s.score, 0);
    const hasUrl = hits.some((h) => h.id === 'url');
    let risk = 'LOW';
    if (score >= 5 || (hasUrl && hits.some((h) => h.id === 'app_install'))) risk = 'HIGH';
    else if (score >= 2) risk = 'CAUTION';

    if (risk === 'LOW') {
      return {
        risk_level: 'LOW',
        summary: '뚜렷한 위험 요소가 발견되지 않았습니다. 다만 안전을 보장하지는 않습니다.',
        reasons: ['스미싱에서 자주 보이는 표현이 거의 없습니다.'],
        recommended_actions: ACTIONS_LOW,
        analysis_status: 'SUCCESS',
      };
    }
    return {
      risk_level: risk,
      summary: risk === 'HIGH' ? '스미싱이 의심됩니다.' : '확인이 필요한 문자입니다.',
      reasons: hits.map((h) => h.reason),
      recommended_actions: risk === 'HIGH' ? ACTIONS_HIGH : ACTIONS_CAUTION,
      analysis_status: 'SUCCESS',
    };
  }
}

export function failedAnalysis() {
  return {
    risk_level: 'UNKNOWN',
    summary: '문자를 분석하지 못했습니다.',
    reasons: ['분석 중 문제가 생겼습니다. 정상 문자라는 뜻이 아닙니다.'],
    recommended_actions: ACTIONS_UNKNOWN,
    analysis_status: 'FAILED',
  };
}

const isStrArr = (v) => Array.isArray(v) && v.length <= 20 && v.every((x) => typeof x === 'string' && x.length <= 300);

/** 분석기 출력 검증. 형식이 틀리면 null */
export function validateAnalysis(r) {
  if (!r || typeof r !== 'object') return null;
  if (!RISK_LEVELS.includes(r.risk_level)) return null;
  if (!ANALYSIS_STATUSES.includes(r.analysis_status)) return null;
  if (typeof r.summary !== 'string' || r.summary.length > 300) return null;
  if (!isStrArr(r.reasons) || !isStrArr(r.recommended_actions)) return null;
  if (r.analysis_status === 'SUCCESS' && r.risk_level === 'UNKNOWN') return null;
  return {
    risk_level: r.risk_level,
    summary: r.summary,
    reasons: r.reasons,
    recommended_actions: r.recommended_actions,
    analysis_status: r.analysis_status,
  };
}

/** 안전 래퍼: 예외/잘못된 형식/시간 초과 → UNKNOWN(FAILED). 절대 LOW 로 폴백하지 않는다. */
export async function safeAnalyze(analyzer, input, timeoutMs = 8000) {
  let timer;
  try {
    const raw = await Promise.race([
      analyzer.analyze(input),
      new Promise((_, rej) => {
        timer = setTimeout(() => rej(new Error('timeout')), timeoutMs);
      }),
    ]);
    return validateAnalysis(raw) ?? failedAnalysis();
  } catch {
    return failedAnalysis();
  } finally {
    clearTimeout(timer);
  }
}

export function createAnalyzer(config) {
  if (config.analyzer === 'mock') return new MockAnalyzer();
  throw new Error(`지원하지 않는 ANALYZER: ${config.analyzer} (외부 AI 연동은 미확정 사항)`);
}
