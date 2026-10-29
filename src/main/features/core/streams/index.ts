import { execFile } from 'child_process';
import { app, ipcMain, net } from 'electron';
import { promises as fs } from 'fs';
import path from 'path';
import { TagLib } from 'taglib-wasm';

import { downloadsDir, library } from '/@/main/features/core/local-library/library';
import {
    registerYoutube,
    setYoutubeMixProvider,
    setYoutubeResolver,
} from '/@/main/features/core/local-library/youtube';
import { getDohProxyUrl } from '/@/main/features/core/streams/doh-proxy';
import {
    findMusicTags,
    MusicTags,
    parseVideoTitle,
} from '/@/main/features/core/streams/music-tags';
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

/** Network calls go through the DoH proxy (YouTube is often DNS-blocked) */
const runOnline = async (binary: string, args: string[], timeout?: number) =>
    run(binary, ['--proxy', await getDohProxyUrl(), ...args], timeout);

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
        const output = await runOnline(binary, ['-U'], 120_000);
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
    const output = await runOnline(binary, [
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
    const output = await runOnline(binary, [
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
            isLive: entry.live_status === 'is_live' || entry.is_live === true,
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

const youtubeThumbnail = (info: any) =>
    info.id
        ? `https://i.ytimg.com/vi/${info.id}/maxresdefault.jpg`
        : pickThumbnail(info) || undefined;

const writeTags = async (file: string, tags: MusicTags, sourceUrl: string) => {
    const taglib = await TagLib.initialize();
    await taglib.edit(file, (audio) => {
        audio.setProperty('title', tags.title);
        audio.setProperty('artist', tags.artist);
        audio.setProperty('album', tags.album);
        audio.setProperty('albumArtist', tags.artist);
        if (tags.year) audio.setProperty('date', String(tags.year));
        if (tags.track) audio.setProperty('trackNumber', String(tags.track));
        if (tags.genre) audio.setProperty('genre', tags.genre);
        audio.setProperty('comment', sourceUrl);
        if (tags.cover) {
            audio.removePictures();
            audio.addPicture({ data: tags.cover, mimeType: 'image/jpeg', type: 'FrontCover' });
        }
    });
};

/** YouTube/<Artist>/<Album>/<NN - >Title.m4a, without clobbering another file */
const moveIntoLibrary = async (file: string, tags: MusicTags) => {
    const dir = path.join(
        downloadsDir(),
        'YouTube',
        safeFileName(tags.artist),
        safeFileName(tags.album),
    );
    await fs.mkdir(dir, { recursive: true });
    const ext = path.extname(file);
    const base = safeFileName(
        `${tags.track ? `${String(tags.track).padStart(2, '0')} - ` : ''}${tags.title}`,
    );
    let target = path.join(dir, `${base}${ext}`);
    for (let i = 2; path.resolve(target) !== path.resolve(file) && (await exists(target)); i++) {
        target = path.join(dir, `${base} (${i})${ext}`);
    }
    if (path.resolve(target) !== path.resolve(file)) await fs.rename(file, target);
    return target;
};

const downloadAudio = async (pageUrl: string): Promise<DownloadedTrack> => {
    if (getExternalStreamSource(pageUrl) !== 'youtube') {
        throw new Error('Only YouTube links can be downloaded');
    }
    const binary = await requireYtDlp();
    const tmpDir = path.join(downloadsDir(), 'YouTube');
    await fs.mkdir(tmpDir, { recursive: true });

    const output = await runOnline(
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
            path.join(tmpDir, '%(id)s.%(ext)s'),
            pageUrl.trim(),
        ],
        30 * 60_000,
    );
    const info = JSON.parse(output);
    const downloaded: string =
        info.requested_downloads?.[0]?.filepath ??
        info.filepath ??
        path.join(tmpDir, `${info.id}.${info.ext}`);

    const tags = await findMusicTags(info, youtubeThumbnail(info));
    try {
        await writeTags(downloaded, tags, info.webpage_url || pageUrl);
    } catch (error) {
        log.warn('Could not tag downloaded track', { error: String(error) });
    }
    const target = await moveIntoLibrary(downloaded, tags);

    log.info('Downloaded YouTube audio', { album: tags.album, artist: tags.artist, target });
    await library.scan();
    return { artist: tags.artist, path: target, title: tags.title };
};

/** Earlier downloads were all tagged album "YouTube": look them up again and re-file them */
const retagDownloads = async () => {
    const root = path.join(downloadsDir(), 'YouTube');
    const files: string[] = [];
    const walk = async (dir: string) => {
        for (const entry of await fs.readdir(dir, { withFileTypes: true }).catch(() => [])) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) await walk(full);
            else if (/\.(m4a|mp4|webm|opus|mp3)$/i.test(entry.name)) files.push(full);
        }
    };
    await walk(root);

    const taglib = await TagLib.initialize();
    let fixed = 0;
    for (const file of files) {
        try {
            const audio = await taglib.open(file);
            const props = audio.properties() as Record<string, string[] | undefined>;
            audio.dispose();
            const album = props.album?.[0] ?? '';
            if (album && album !== 'YouTube') continue;
            const source = props.comment?.[0] ?? '';
            const videoId = /[?&]v=([\w-]{6,})/.exec(source)?.[1];
            const info = {
                id: videoId,
                title: `${props.artist?.[0] ?? ''} - ${props.title?.[0] ?? path.basename(file, path.extname(file))}`,
                uploader: props.artist?.[0],
            };
            const tags = await findMusicTags(info, videoId ? youtubeThumbnail(info) : undefined);
            await writeTags(file, tags, source);
            await moveIntoLibrary(file, tags);
            fixed++;
        } catch (error) {
            log.warn('Could not retag', { error: String(error), file });
        }
    }
    await library.scan();
    return { fixed, total: files.length };
};

ipcMain.handle('streams-retag-downloads', () => retagDownloads());

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

/** Search results become library songs (yt-<id>) so they can be queued and seeked */
// "Artist - Song (Official video)" -> artist + song: better library, lyrics and scrobbles
const asSong = (item: ExternalSearchResult) => {
    const { artist, title } = parseVideoTitle({ title: item.title, uploader: item.uploader });
    return {
        artist,
        duration: item.duration ?? 0,
        thumbnail: item.thumbnail,
        title,
        videoId: item.id,
    };
};

ipcMain.handle('streams-register-youtube', (_event, items: ExternalSearchResult[]) =>
    registerYoutube(items.map(asSong)),
);

setYoutubeMixProvider(async (videoId) => {
    const binary = await requireYtDlp();
    const output = await runOnline(binary, [
        '-J',
        '--flat-playlist',
        '--no-warnings',
        '--playlist-end',
        '50',
        `https://www.youtube.com/watch?v=${videoId}&list=RD${videoId}`,
    ]);
    const entries: any[] = JSON.parse(output).entries ?? [];
    return entries
        .filter((entry) => entry?.id)
        .map((entry) =>
            asSong({
                duration: typeof entry.duration === 'number' ? entry.duration : null,
                id: entry.id,
                thumbnail: pickThumbnail(entry),
                title: entry.title || entry.id,
                uploader: entry.channel || entry.uploader || null,
                url: `https://www.youtube.com/watch?v=${entry.id}`,
            }),
        );
});

setYoutubeResolver(async (videoId) => {
    const binary = await requireYtDlp();
    const output = await runOnline(binary, [
        '-J',
        '--no-playlist',
        '--no-warnings',
        '-f',
        DOWNLOAD_FORMAT,
        `https://www.youtube.com/watch?v=${videoId}`,
    ]);
    const info = JSON.parse(output);
    const url: string | undefined = info.url ?? info.requested_formats?.[0]?.url;
    if (!url) throw new Error('yt-dlp returned no stream');
    const { artist, title } = parseVideoTitle(info);
    return {
        artist,
        duration: Math.round(Number(info.duration) || 0),
        thumbnail: pickThumbnail(info),
        title,
        url,
    };
});
