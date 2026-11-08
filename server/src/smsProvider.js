// SMS 발송 사업자 인터페이스. 실제 사업자는 미확정(가정)이므로 모의 구현만 제공한다.
// 구현체는 { send(to, text) → Promise<{ accepted: boolean, code?: string }> } 를 따른다.
// "accepted" 는 사업자 접수/전송 상태이며 수신자가 읽었다는 뜻이 아니다.

export class MockSmsProvider {
  constructor() {
    this.sent = []; // 테스트용 (실제 사업자에서는 보관하지 않음)
    this.failNext = 0; // 테스트용: 다음 N회 실패
  }
  async send(to, text) {
    if (this.failNext > 0) {
      this.failNext -= 1;
      return { accepted: false, code: 'PROVIDER_ERROR' };
    }
    this.sent.push({ to, text });
    return { accepted: true };
  }
}

export function createSmsProvider(config) {
  if (config.smsProvider === 'mock') return new MockSmsProvider();
  throw new Error(`지원하지 않는 SMS_PROVIDER: ${config.smsProvider} (사업자 선정은 미확정 사항)`);
}
