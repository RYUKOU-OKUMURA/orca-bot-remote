import { readConfig } from './config.ts';
import { createApi } from './http.ts';
import { stopAll } from './orca.ts';

try {
  const config = readConfig();
  const shutdownController = new AbortController();
  const server = createApi({ ...config, shutdownSignal: shutdownController.signal });
  server.on('error', error => {
    console.error(error.message);
    process.exitCode = 1;
  });
  server.listen(config.port, config.host);

  let shuttingDown = false;
  function shutdown() {
    if (shuttingDown) return;
    shuttingDown = true;
    setTimeout(() => process.exit(0), 2000);
    const drained = new Promise<void>(resolve => server.close(() => resolve()));
    shutdownController.abort();
    void Promise.all([drained, stopAll()]).then(() => process.exit(0));
  }
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
} catch (error) {
  console.error(error instanceof Error ? error.message : 'サーバーの起動に失敗しました。');
  process.exitCode = 1;
}
