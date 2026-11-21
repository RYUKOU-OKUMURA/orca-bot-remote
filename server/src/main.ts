import { readConfig } from './config.ts';
import { createApi } from './http.ts';

try {
  const config = readConfig();
  const server = createApi(config);
  server.on('error', error => {
    console.error(error.message);
    process.exitCode = 1;
  });
  server.listen(config.port, config.host);
} catch (error) {
  console.error(error instanceof Error ? error.message : 'サーバーの起動に失敗しました。');
  process.exitCode = 1;
}
