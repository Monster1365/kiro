import { loadConfig } from './config.js';
import { createAnalyzer } from './analyzer.js';
import { createSmsProvider } from './smsProvider.js';
import { createApp } from './app.js';

const config = loadConfig();
const server = createApp({
  config,
  analyzer: createAnalyzer(config),
  sms: createSmsProvider(config),
  log: (level, msg) => console.log(`[${level}] ${msg}`),
});

server.listen(config.port, () => {
  console.log(`[info] 서버 시작: http://localhost:${config.port} (analyzer=${config.analyzer}, sms=${config.smsProvider})`);
});
