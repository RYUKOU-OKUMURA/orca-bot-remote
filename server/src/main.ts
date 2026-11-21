import { readConfig } from './config.ts';
import { createApi } from './http.ts';
import { stopAll } from './orca.ts';

try {
  const config = readConfig();
  const server = createApi(config);
  server.on('error', error => {
    console.error(error.message);
    process.exitCode = 1;
  });
  server.listen(config.port, config.host);

  let shuttingDown = false;
  function shutdown() {
    if (shuttingDown) return;
    shuttingDown = true;
    server.close();
    void stopAll().then(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000);
  }
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
} catch (error) {
  console.error(error instanceof Error ? error.message : 'サーバーの起動に失敗しました。');
  process.exitCode = 1;
}
