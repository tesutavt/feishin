import { execFile } from 'child_process';
import { app, ipcMain, net } from 'electron';
import { promises as fs } from 'fs';
import path from 'path';
import { TagLib } from 'taglib-wasm';

import { downloadsDir, library } from '/@/main/features/core/local-library/library';
import log from '/@/main/logger';
import {
    type DownloadedTrack,
    type ExternalSearchResult,
    getExternalStreamSource,
    type ResolvedExternalStream,
    type YtDlpStatus,
} from '/@/shared/types/streams';

// YouTube and Twitch are played through yt-dlp: it turns a page URL into a direct
// audio stream (http for YouTube videos, HLS for Twitch and YouTube live).

const BINARY_NAME = process.platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp';

const RELEASE_ASSET: Record<string, string> = {
    darwin: 'yt-dlp_macos',
    linux: 'yt-dlp_linux',
    win32: 'yt-dlp.exe',
};

const AUDIO_FORMAT = 'bestaudio[protocol^=http][acodec!=none]/audio_only/bestaudio/best';

/** Writable copy that yt-dlp can self-update */
const userBinaryPath = () => path.join(app.getPath('userData'), 'bin', BINARY_NAME);

/** Shipped inside the installer (scripts/fetch-ytdlp.mjs -> extraResources) */
const shippedBinaryPath = () =>
    app.isPackaged
        ? path.join(process.resourcesPath, 'bin', BINARY_NAME)
        : path.join(app.getAppPath(), 'build', 'yt-dlp', BINARY_NAME);

const run = (binary: string, args: string[], timeout = 60_000) =>
    new Promise<string>((resolve, reject) => {
        execFile(
            binary,
            args,
            { maxBuffer: 64 * 1024 * 1024, timeout, windowsHide: true },
            (error, stdout, stderr) => {
                if (error) {
                    reject(new Error(stderr?.toString().trim() || error.message));
                    return;
                }
                resolve(stdout.toString());
            },
        );
    });

const exists = (file: string) =>
    fs.access(file).then(
        () => true,
        () => false,
    );

const findYtDlp = async (): Promise<YtDlpStatus> => {
    for (const candidate of [userBinaryPath(), 'yt-dlp']) {
        try {
            const version = (await run(candidate, ['--version'], 15_000)).trim();
            return { path: candidate, version };
        } catch {
            // try next candidate
        }
    }

    return { path: null, version: null };
};

const downloadYtDlp = async (): Promise<YtDlpStatus> => {
    const asset = RELEASE_ASSET[process.platform];
    if (!asset) {
        throw new Error(`yt-dlp has no build for ${process.platform}`);
    }

    const target = userBinaryPath();
    const url = `https://github.com/yt-dlp/yt-dlp/releases/latest/download/${asset}`;
    log.info('Downloading yt-dlp', { target, url });

    const response = await net.fetch(url);
    if (!response.ok) {
        throw new Error(`yt-dlp download failed: HTTP ${response.status}`);
    }

    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, Buffer.from(await response.arrayBuffer()));
    if (process.platform !== 'win32') {
        await fs.chmod(target, 0o755);
    }

    return findYtDlp();
};

const UPDATE_INTERVAL = 24 * 60 * 60 * 1000;
const updateStampPath = () => path.join(app.getPath('userData'), 'bin', 'yt-dlp-updated');

/** YouTube changes often: let yt-dlp update itself once a day, in the background */
const autoUpdate = async (binary: string) => {
    try {
        const stamp = Number(await fs.readFile(updateStampPath(), 'utf8').catch(() => '0'));
        if (Date.now() - stamp < UPDATE_INTERVAL) return;
        await fs.writeFile(updateStampPath(), String(Date.now()));
        const output = await run(binary, ['-U'], 120_000);
        log.info('yt-dlp update check', { output: output.trim().split('\n').pop() });
    } catch (error) {
        log.warn('yt-dlp update failed', { error: String(error) });
    }
};

let preparing: null | Promise<string> = null;

/** Works out of the box: shipped binary -> user copy, or a one-time download */
const prepareYtDlp = async () => {
    const target = userBinaryPath();
    if (!(await exists(target)) && (await exists(shippedBinaryPath()))) {
        await fs.mkdir(path.dirname(target), { recursive: true });
        await fs.copyFile(shippedBinaryPath(), target);
        if (process.platform !== 'win32') await fs.chmod(target, 0o755);
    }

    let status = await findYtDlp();
    if (!status.path) status = await downloadYtDlp();
    if (!status.path) throw new Error('yt-dlp could not be started');

    if (status.path === target) autoUpdate(target);
    return status.path;
};

const requireYtDlp = () => {
    preparing ??= prepareYtDlp().catch((error) => {
        preparing = null;
        throw error;
    });
    return preparing;
};

const pickThumbnail = (info: any): null | string => {
    if (typeof info.thumbnail === 'string') return info.thumbnail;
    const thumbnails = Array.isArray(info.thumbnails) ? info.thumbnails : [];
    return thumbnails.length ? thumbnails[thumbnails.length - 1].url || null : null;
};

const resolveStream = async (url: string): Promise<ResolvedExternalStream> => {
    const source = getExternalStreamSource(url);
    if (!source) {
        throw new Error('Not a YouTube or Twitch URL');
    }

    const binary = await requireYtDlp();
    const output = await run(binary, [
        '-J',
        '--no-playlist',
        '--no-warnings',
        '-f',
        AUDIO_FORMAT,
        url.trim(),
    ]);
    const info = JSON.parse(output);
    const streamUrl: string | undefined = info.url ?? info.requested_formats?.[0]?.url;

    if (!streamUrl) {
        throw new Error('yt-dlp returned no playable stream');
    }

    log.info('Resolved external stream', { isLive: Boolean(info.is_live), source });

    return {
        artist: info.uploader || info.channel || null,
        isLive: Boolean(info.is_live),
        source,
        streamUrl,
        thumbnail: pickThumbnail(info),
        title: info.description && source === 'twitch' ? info.description : info.title || null,
    };
};

const searchYoutube = async (query: string, limit = 20): Promise<ExternalSearchResult[]> => {
    const binary = await requireYtDlp();
    const output = await run(binary, [
        '-J',
        '--flat-playlist',
        '--no-warnings',
        `ytsearch${limit}:${query}`,
    ]);
    const entries: any[] = JSON.parse(output).entries ?? [];

    return entries
        .filter((entry) => entry?.id)
        .map((entry) => ({
            duration: typeof entry.duration === 'number' ? entry.duration : null,
            id: entry.id,
            thumbnail: pickThumbnail(entry),
            title: entry.title || entry.id,
            uploader: entry.channel || entry.uploader || null,
            url: entry.url?.startsWith('http')
                ? entry.url
                : `https://www.youtube.com/watch?v=${entry.id}`,
        }));
};

// ---------------------------------------------------------------- YouTube -> music file

// m4a (AAC) is a plain audio track on YouTube: no video, no ffmpeg needed
const DOWNLOAD_FORMAT = 'bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio';

const safeFileName = (value: string) =>
    value
        // eslint-disable-next-line no-control-regex
        .replace(/[\\/:*?"<>|\x00-\x1f]/g, '_')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 150);

/** Artist / title from YouTube Music fields, "Artist - Title" or the channel name */
const musicTags = (info: any) => {
    const uploader = String(info.artist || info.creator || info.uploader || info.channel || '')
        .replace(/ - Topic$/, '')
        .split(',')[0]
        .trim();
    let title = String(info.track || info.title || info.id);
    let artist = info.track && info.artist ? String(info.artist).split(',')[0].trim() : '';
    if (!artist) {
        const match = title.match(/^(.+?)\s+[-–—]\s+(.+)$/);
        if (match) {
            artist = match[1].trim();
            title = match[2].trim();
        } else {
            artist = uploader;
        }
    }
    title = title
        .replace(
            /\s*[([](official\s*)?(music\s*)?(video|audio|lyrics?|lyric video|visualizer|mv|hd|4k)[)\]]/gi,
            '',
        )
        .trim();
    const year = parseInt(String(info.release_year || info.upload_date || '').slice(0, 4), 10) || 0;
    return { album: String(info.album || 'YouTube'), artist: artist || 'YouTube', title, year };
};

const fetchCover = async (info: any): Promise<null | Uint8Array> => {
    const candidates = [
        info.id ? `https://i.ytimg.com/vi/${info.id}/maxresdefault.jpg` : null,
        info.id ? `https://i.ytimg.com/vi/${info.id}/hqdefault.jpg` : null,
        typeof info.thumbnail === 'string' ? info.thumbnail : null,
    ].filter(Boolean) as string[];
    for (const url of candidates) {
        try {
            const response = await net.fetch(url);
            const type = response.headers.get('content-type') ?? '';
            if (response.ok && type.startsWith('image/jpeg')) {
                return new Uint8Array(await response.arrayBuffer());
            }
        } catch {
            // try next size
        }
    }
    return null;
};

const downloadAudio = async (pageUrl: string): Promise<DownloadedTrack> => {
    if (getExternalStreamSource(pageUrl) !== 'youtube') {
        throw new Error('Only YouTube links can be downloaded');
    }
    const binary = await requireYtDlp();
    const dir = path.join(downloadsDir(), 'YouTube');
    await fs.mkdir(dir, { recursive: true });

    const output = await run(
        binary,
        [
            '-J',
            '--no-simulate',
            '--no-playlist',
            '--no-warnings',
            '--no-part',
            '-f',
            DOWNLOAD_FORMAT,
            '-o',
            path.join(dir, '%(id)s.%(ext)s'),
            pageUrl.trim(),
        ],
        30 * 60_000,
    );
    const info = JSON.parse(output);
    const downloaded: string =
        info.requested_downloads?.[0]?.filepath ??
        info.filepath ??
        path.join(dir, `${info.id}.${info.ext}`);
    const tags = musicTags(info);

    try {
        const cover = await fetchCover(info);
        const taglib = await TagLib.initialize();
        await taglib.edit(downloaded, (file) => {
            file.setProperty('title', tags.title);
            file.setProperty('artist', tags.artist);
            file.setProperty('album', tags.album);
            file.setProperty('albumArtist', tags.artist);
            if (tags.year) file.setProperty('date', String(tags.year));
            file.setProperty('comment', info.webpage_url || pageUrl);
            if (cover) {
                file.removePictures();
                file.addPicture({ data: cover, mimeType: 'image/jpeg', type: 'FrontCover' });
            }
        });
    } catch (error) {
        log.warn('Could not tag downloaded track', { error: String(error) });
    }

    // readable file name: "Artist - Title.m4a"
    const ext = path.extname(downloaded);
    let target = path.join(dir, `${safeFileName(`${tags.artist} - ${tags.title}`)}${ext}`);
    for (let i = 2; target !== downloaded; i++) {
        try {
            await fs.access(target);
            target = path.join(
                dir,
                `${safeFileName(`${tags.artist} - ${tags.title}`)} (${i})${ext}`,
            );
        } catch {
            break;
        }
    }
    if (target !== downloaded) await fs.rename(downloaded, target);

    log.info('Downloaded YouTube audio', { target });
    await library.scan();
    return { artist: tags.artist, path: target, title: tags.title };
};

ipcMain.handle('streams-download-audio', (_event, url: string) => downloadAudio(url));

ipcMain.handle('streams-ytdlp-status', async () => {
    await requireYtDlp().catch(() => undefined);
    return findYtDlp();
});

ipcMain.handle('streams-ytdlp-download', async () => {
    preparing = null;
    return downloadYtDlp();
});

// get it ready in the background so the first play is fast
app.whenReady().then(() =>
    requireYtDlp().catch((error) => log.warn('yt-dlp not ready', { error: String(error) })),
);

ipcMain.handle('streams-resolve', (_event, url: string) => resolveStream(url));

ipcMain.handle('streams-search-youtube', (_event, query: string) => searchYoutube(query));
