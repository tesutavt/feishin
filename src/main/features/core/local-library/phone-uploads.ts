import { shell } from 'electron';
import { createWriteStream, promises as fs } from 'fs';
import { IncomingMessage } from 'http';
import path from 'path';
import { TagLib } from 'taglib-wasm';

import { AUDIO_EXTENSIONS, downloadsDir, library, LocalSong } from './library';

import log from '/@/main/logger';

// Songs sent from the phone ("Отправить на компьютер"). Phone downloads keep their tags in
// the app, not in the file, so the phone sends them along and the computer writes them;
// a song the library already has is not stored twice.

const PHONE_DIR = 'С телефона';

const phoneRoot = () => path.join(downloadsDir(), PHONE_DIR);

const safeName = (value: string) =>
    value
        // eslint-disable-next-line no-control-regex
        .replace(/[\\/:*?"<>|\x00-\x1f]/g, '_')
        .replace(/\s+/g, ' ')
        .trim()
        .slice(0, 120) || 'track';

/** "Ironmouse", "IRONMOUSE (feat. x)", "ironmouse!" -> "ironmouse" */
const normalize = (value: string) =>
    value
        .toLowerCase()
        .replace(/\(.*?\)|\[.*?\]/g, ' ')
        .replace(/\b(feat|ft)\.?\s.*$/, ' ')
        .replace(/[^\p{L}\p{N}]+/gu, '')
        .trim();

const videoIdOf = (url?: string) =>
    url ? /(?:[?&]v=|youtu\.be\/)([\w-]{6,})/.exec(url)?.[1] : undefined;

const isPhoneCopy = (song: LocalSong) =>
    song.path.toLowerCase().startsWith(phoneRoot().toLowerCase() + path.sep);

/** The same song: the same YouTube video, or the same artist + title (either way round) */
const findSame = (
    meta: { artist: string; source?: string; title: string },
    except?: (s: LocalSong) => boolean,
) => {
    const video = videoIdOf(meta.source);
    const a = normalize(meta.artist);
    const t = normalize(meta.title);
    for (const song of library.songs.values()) {
        if (except?.(song)) continue;
        if (video && videoIdOf(song.sourceUrl) === video) return song;
        const sa = normalize(song.artist);
        const st = normalize(song.title);
        if (!t || !st) continue;
        if ((sa === a && st === t) || (sa === t && st === a)) return song;
    }
    return undefined;
};

const drain = (req: IncomingMessage) =>
    new Promise<void>((resolve) => {
        req.on('end', resolve).on('error', () => resolve());
        req.resume();
    });

const readBody = (req: IncomingMessage) =>
    new Promise<Buffer>((resolve, reject) => {
        const chunks: Buffer[] = [];
        req.on('data', (c: Buffer) => chunks.push(c))
            .on('end', () => resolve(Buffer.concat(chunks)))
            .on('error', reject);
    });

export interface UploadMeta {
    album?: string;
    artist: string;
    genre?: string;
    pcId?: string;
    source?: string;
    title: string;
    track?: number;
    year?: number;
}

/** Receives the audio; returns the stored file (to attach the cover) or that it is a duplicate */
export const receivePhoneUpload = async (req: IncomingMessage, name: string, meta: UploadMeta) => {
    const ext = path.extname(name).toLowerCase();
    if (!AUDIO_EXTENSIONS.has(ext)) throw new Error('Not a music file');

    // a computer song downloaded to the phone, or a song the library already has
    const existing = (meta.pcId && library.songs.get(meta.pcId)) || findSame(meta);
    if (existing) {
        await drain(req);
        log.info('Phone upload skipped, already in the library', { existing: existing.path });
        return { duplicate: true, file: null };
    }

    const artist = meta.artist || 'Неизвестный исполнитель';
    const album = meta.album || meta.title;
    const dir = path.join(phoneRoot(), safeName(artist), safeName(album));
    await fs.mkdir(dir, { recursive: true });
    const base = safeName(
        `${meta.track ? `${String(meta.track).padStart(2, '0')} - ` : ''}${meta.title || path.basename(name, ext)}`,
    );
    let target = path.join(dir, `${base}${ext}`);
    for (
        let i = 2;
        await fs.access(target).then(
            () => true,
            () => false,
        );
        i++
    ) {
        target = path.join(dir, `${base} (${i})${ext}`);
    }
    await new Promise<void>((resolve, reject) => {
        const out = createWriteStream(target);
        out.on('finish', resolve).on('error', reject);
        req.on('error', reject).pipe(out);
    });

    try {
        const taglib = await TagLib.initialize();
        await taglib.edit(target, (file) => {
            if (meta.title) file.setProperty('title', meta.title);
            file.setProperty('artist', artist);
            file.setProperty('album', album);
            file.setProperty('albumArtist', artist);
            if (meta.year) file.setProperty('date', String(meta.year));
            if (meta.track) file.setProperty('trackNumber', String(meta.track));
            if (meta.genre) file.setProperty('genre', meta.genre);
            if (meta.source) file.setProperty('comment', meta.source);
        });
    } catch (error) {
        log.warn('Could not tag a phone upload', { error: String(error), target });
    }

    log.info('Received a file from a device', { target });
    library.scan();
    return { duplicate: false, file: path.relative(phoneRoot(), target) };
};

/** The cover of a song sent just before (JPEG / PNG bytes) */
export const receivePhoneCover = async (req: IncomingMessage, file: string, mimeType: string) => {
    const target = path.resolve(phoneRoot(), file);
    if (!target.toLowerCase().startsWith(phoneRoot().toLowerCase() + path.sep))
        throw new Error('Bad file');
    const data = await readBody(req);
    if (!data.length) return {};
    const taglib = await TagLib.initialize();
    await taglib.edit(target, (audio) => {
        audio.removePictures();
        audio.addPicture({
            data: new Uint8Array(data),
            mimeType: mimeType || 'image/jpeg',
            type: 'FrontCover',
        });
    });
    library.scan();
    return {};
};

/** Copies sent before the duplicate check: to the Recycle Bin when the library has the song elsewhere */
export const removeDuplicatePhoneCopies = async () => {
    let removed = 0;
    for (const song of [...library.songs.values()].filter(isPhoneCopy)) {
        const same = findSame(
            { artist: song.artist, source: song.sourceUrl, title: song.title },
            (s) => s === song || isPhoneCopy(s),
        );
        if (!same) continue;
        try {
            await shell.trashItem(song.path);
            removed++;
            log.info('Duplicate phone copy moved to the Recycle Bin', {
                copy: song.path,
                kept: same.path,
            });
        } catch (error) {
            log.warn('Could not remove a duplicate phone copy', {
                error: String(error),
                path: song.path,
            });
        }
    }
    return removed;
};
