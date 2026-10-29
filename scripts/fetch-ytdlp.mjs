/* eslint-disable @typescript-eslint/explicit-function-return-type -- plain JS build script */
// Puts yt-dlp next to the app so YouTube / Twitch work right after install.
// Runs as part of `pnpm build`; the app also downloads it itself if this step was skipped.
import { mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ASSETS = { darwin: 'yt-dlp_macos', linux: 'yt-dlp_linux', win32: 'yt-dlp.exe' };
const platform = process.env.YTDLP_PLATFORM || process.platform;
const asset = ASSETS[platform];
const name = platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp';
const target = join(dirname(fileURLToPath(import.meta.url)), '..', 'build', 'yt-dlp', name);

const fresh = () => {
    try {
        return Date.now() - statSync(target).mtimeMs < 7 * 24 * 60 * 60 * 1000;
    } catch {
        return false;
    }
};

if (!asset) {
    console.warn(`yt-dlp: no build for ${platform}, skipping`);
} else if (fresh()) {
    console.log('yt-dlp: up to date');
} else {
    try {
        const response = await fetch(
            `https://github.com/yt-dlp/yt-dlp/releases/latest/download/${asset}`,
        );
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, Buffer.from(await response.arrayBuffer()), { mode: 0o755 });
        console.log(`yt-dlp: saved ${target}`);
    } catch (error) {
        // not fatal: the app fetches yt-dlp on first launch
        console.warn(
            `yt-dlp: download failed (${error.message}), the app will fetch it on first use`,
        );
    }
}
