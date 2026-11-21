import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import type { ServerResponse } from 'node:http';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';

const maxSize = 200 * 1024 * 1024;

// Non-blocking open lets fstat reject special files such as FIFOs.
async function openReleaseFile(path: string) {
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.uid !== process.getuid!() || stat.size < 1 || stat.size > maxSize) {
      throw new Error('Invalid release file');
    }
    return { file, stat };
  } catch (error) {
    await file.close();
    throw error;
  }
}

export function createAppRelease(dir = process.env.APP_DIR || join(homedir(), 'Library/Application Support/orca-bot-remote/app')) {
  const apkPath = join(dir, 'orca-bot-remote.apk');
  let checksum: { key: string; sha256: string } | undefined;

  return {
    async metadata() {
      try {
        const metadata = await openReleaseFile(join(dir, 'release.json'));
        let versionCode: number, versionName: string;
        try {
          ({ versionCode, versionName } = JSON.parse(await metadata.file.readFile('utf8')));
        } finally {
          await metadata.file.close();
        }
        if (!Number.isSafeInteger(versionCode) || versionCode <= 0 ||
            typeof versionName !== 'string' || (versionName.length < 1 || versionName.length > 64 || /[^ -~]/.test(versionName))) return null;

        const { file, stat } = await openReleaseFile(apkPath);
        try {
          const { ino, size, mtimeMs } = stat;
          const key = JSON.stringify([ino, size, mtimeMs]);
          let sha256 = checksum?.key === key ? checksum.sha256 : undefined;
          if (!sha256) {
            const hash = createHash('sha256');
            let read = 0;
            for await (const chunk of file.createReadStream({ end: size - 1, autoClose: false })) {
              read += chunk.length;
              hash.update(chunk);
            }
            if (read !== size) return null;
            sha256 = hash.digest('hex');
            checksum = { key, sha256 };
          }
          return { versionCode, versionName, sha256, size };
        } finally {
          await file.close();
        }
      } catch {
        return null;
      }
    },
    async download(response: ServerResponse) {
      const opened = await openReleaseFile(apkPath).catch(() => null);
      if (!opened) return false;
      const { file, stat } = opened;
      try {
        if (!response.destroyed) {
          response.writeHead(200, {
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Length': stat.size,
          });
          await pipeline(file.createReadStream({ end: stat.size - 1, autoClose: false }), response);
        }
      } catch {
        response.destroy();
      } finally {
        await file.close();
      }
      return true;
    },
  };
}
