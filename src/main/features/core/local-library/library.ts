import { createHash } from 'crypto';
import { app } from 'electron';
import { promises as fs } from 'fs';
import path from 'path';
import { TagLib } from 'taglib-wasm';

import log from '/@/main/logger';

// Local music library: scans folders, keeps an index cache and the user's
// playlists / favourites / play counts. Served to the UI by ./server.ts.

export const AUDIO_EXTENSIONS = new Set([
    '.aac',
    '.aiff',
    '.alac',
    '.ape',
    '.flac',
    '.m4a',
    '.mp3',
    '.mpc',
    '.ogg',
    '.opus',
    '.wav',
    '.wma',
    '.wv',
]);

const COVER_NAMES = ['cover', 'folder', 'front', 'album', 'albumart'];
const IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.webp'];

export interface LocalAlbum {
    artist: string;
    artistId: string;
    created: string;
    duration: number;
    genre: string;
    id: string;
    name: string;
    songs: LocalSong[];
    year: number;
}

export interface LocalArtist {
    albums: LocalAlbum[];
    id: string;
    name: string;
}

export interface LocalPlaylist {
    changed: string;
    created: string;
    id: string;
    name: string;
    songIds: string[];
}

export interface LocalRadio {
    homePageUrl?: string;
    id: string;
    name: string;
    streamUrl: string;
}

export interface LocalSong {
    album: string;
    albumArtist: string;
    albumId: string;
    artist: string;
    artistId: string;
    bitRate: number;
    channels: number;
    created: string;
    disc: number;
    duration: number;
    genre: string;
    hasPicture: boolean;
    id: string;
    lyrics?: string;
    mtimeMs: number;
    path: string;
    sampleRate: number;
    size: number;
    title: string;
    track: number;
    year: number;
}

export interface RemoteAccess {
    enabled: boolean;
    password: string;
    port: number;
    username: string;
}

export interface SavedQueue {
    changed: string;
    changedBy: string;
    current?: string;
    position: number;
    songIds: string[];
}

export interface StoredYoutubeSong {
    artist: string;
    duration: number;
    id: string;
    thumbnail: null | string;
    title: string;
    videoId: string;
}

interface UserData {
    folders: string[];
    lastPlayed: Record<string, string>;
    playCounts: Record<string, number>;
    playlists: LocalPlaylist[];
    playQueue?: SavedQueue;
    radio: LocalRadio[];
    ratings: Record<string, number>;
    remote: RemoteAccess;
    starred: Record<string, string>;
    /** YouTube songs that were played: favourites and playlists keep working after a restart */
    youtube?: Record<string, StoredYoutubeSong>;
}

export const hashId = (value: string) =>
    createHash('sha1').update(value.toLowerCase()).digest('hex').slice(0, 16);

const dataDir = () =>
    process.env.NODE_ENV === 'development'
        ? path.normalize(`${app.getPath('userData')}-dev`)
        : app.getPath('userData');

const userFile = () => path.join(dataDir(), 'local-library.json');
const indexFile = () => path.join(dataDir(), 'local-library-index.json');

export const downloadsDir = () => path.join(app.getPath('music'), 'TesuPlayer');

const first = (values: string[] | undefined) => values?.find((v) => v.trim())?.trim() ?? '';
const toInt = (value: string) => parseInt(value, 10) || 0;

let taglib: null | TagLib = null;
const getTagLib = async () => (taglib ??= await TagLib.initialize());

class Library {
    albums = new Map<string, LocalAlbum>();
    artists = new Map<string, LocalArtist>();
    scanning = false;
    songs = new Map<string, LocalSong>();
    user: UserData = {
        folders: [],
        lastPlayed: {},
        playCounts: {},
        playlists: [],
        radio: [],
        ratings: {},
        remote: {
            enabled: false,
            password: Math.random().toString(36).slice(2, 10),
            port: 45173,
            username: 'tesu',
        },
        starred: {},
    };

    private listeners = new Set<() => void>();
    private saveTimer: NodeJS.Timeout | null = null;

    /** Embedded picture of a song, album or artist; falls back to cover.jpg next to the files */
    async cover(id: string): Promise<null | { data: Buffer; mimeType: string }> {
        const song =
            this.songs.get(id) ??
            this.albums.get(id)?.songs.find((s) => s.hasPicture) ??
            this.albums.get(id)?.songs[0] ??
            this.artists.get(id)?.albums[0]?.songs.find((s) => s.hasPicture) ??
            this.artists.get(id)?.albums[0]?.songs[0];
        if (!song) return null;

        const album = this.albums.get(song.albumId);
        const withPicture = song.hasPicture ? song : album?.songs.find((s) => s.hasPicture);
        if (withPicture) {
            try {
                const tl = await getTagLib();
                const file = await tl.open(withPicture.path);
                try {
                    const pictures = file.getPictures();
                    const picture = pictures.find((p) => p.type === 'FrontCover') ?? pictures[0];
                    if (picture)
                        return { data: Buffer.from(picture.data), mimeType: picture.mimeType };
                } finally {
                    file.dispose();
                }
            } catch {
                // fall through to folder image
            }
        }

        const dir = path.dirname(song.path);
        try {
            const files = await fs.readdir(dir);
            const image =
                files.find((f) => {
                    const ext = path.extname(f).toLowerCase();
                    return (
                        IMAGE_EXTENSIONS.includes(ext) &&
                        COVER_NAMES.includes(path.basename(f, ext).toLowerCase())
                    );
                }) ?? files.find((f) => IMAGE_EXTENSIONS.includes(path.extname(f).toLowerCase()));
            if (image) {
                const ext = path.extname(image).toLowerCase();
                return {
                    data: await fs.readFile(path.join(dir, image)),
                    mimeType:
                        ext === '.png'
                            ? 'image/png'
                            : ext === '.webp'
                              ? 'image/webp'
                              : 'image/jpeg',
                };
            }
        } catch {
            // no folder image
        }
        return null;
    }

    async load() {
        try {
            const saved = JSON.parse(await fs.readFile(userFile(), 'utf8'));
            this.user = {
                ...this.user,
                ...saved,
                remote: { ...this.user.remote, ...saved.remote },
            };
        } catch {
            this.user.folders = [app.getPath('music')];
            this.save();
        }
        await fs.mkdir(downloadsDir(), { recursive: true }).catch(() => undefined);

        try {
            const cached: LocalSong[] = JSON.parse(await fs.readFile(indexFile(), 'utf8'));
            this.rebuild(cached);
        } catch {
            // first run
        }
    }

    onChange(listener: () => void) {
        this.listeners.add(listener);
    }

    save() {
        if (this.saveTimer) clearTimeout(this.saveTimer);
        this.saveTimer = setTimeout(() => {
            fs.writeFile(userFile(), JSON.stringify(this.user, null, 2)).catch((error) =>
                log.error('Failed to save local library', error),
            );
        }, 300);
    }

    async scan() {
        if (this.scanning) return;
        this.scanning = true;
        const started = Date.now();
        try {
            const files: string[] = [];
            for (const folder of this.roots()) await this.walk(folder, files);

            const previous = new Map([...this.songs.values()].map((s) => [s.path, s]));
            const result: LocalSong[] = [];
            let index = 0;
            const worker = async () => {
                while (index < files.length) {
                    const filePath = files[index++];
                    try {
                        const stat = await fs.stat(filePath);
                        const cached = previous.get(filePath);
                        result.push(
                            cached && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size
                                ? cached
                                : await this.readSong(filePath, stat),
                        );
                    } catch {
                        // file vanished
                    }
                }
            };
            await Promise.all(Array.from({ length: 4 }, worker));

            this.rebuild(result);
            await fs.writeFile(indexFile(), JSON.stringify(result));
            log.info('Local library scanned', { ms: Date.now() - started, songs: result.length });
        } finally {
            this.scanning = false;
            this.listeners.forEach((listener) => listener());
        }
    }

    setFolders(folders: string[]) {
        this.user.folders = [...new Set(folders.map((f) => path.normalize(f)))];
        this.save();
        return this.scan();
    }

    private async readSong(
        filePath: string,
        stat: { birthtime: Date; mtimeMs: number; size: number },
    ) {
        const fileName = path.basename(filePath, path.extname(filePath));
        const base: LocalSong = {
            album: '',
            albumArtist: '',
            albumId: '',
            artist: '',
            artistId: '',
            bitRate: 0,
            channels: 0,
            created: stat.birthtime.toISOString(),
            disc: 1,
            duration: 0,
            genre: '',
            hasPicture: false,
            id: hashId(filePath),
            mtimeMs: stat.mtimeMs,
            path: filePath,
            sampleRate: 0,
            size: stat.size,
            title: fileName,
            track: 0,
            year: 0,
        };

        try {
            const tl = await getTagLib();
            const file = await tl.open(filePath);
            try {
                const p = file.properties() as Record<string, string[] | undefined>;
                const audio = file.audioProperties();
                const lyrics = file.getLyrics();
                Object.assign(base, {
                    album: first(p.album),
                    albumArtist: first(p.albumArtist),
                    artist: first(p.artist),
                    bitRate: audio?.bitrate ?? 0,
                    channels: audio?.channels ?? 0,
                    disc: toInt(first(p.discNumber)) || 1,
                    duration: Math.round(audio?.duration ?? 0),
                    genre: first(p.genre),
                    hasPicture: file.getPictures().length > 0,
                    lyrics: lyrics.length ? lyrics.map((l) => l.text).join('\n\n') : undefined,
                    sampleRate: audio?.sampleRate ?? 0,
                    title: first(p.title) || fileName,
                    track: toInt(first(p.trackNumber)),
                    year: toInt(first(p.date).slice(0, 4)),
                });
            } finally {
                file.dispose();
            }
        } catch (error) {
            log.warn('Could not read tags', { error: String(error), filePath });
        }

        // "Artist - Title" file names without tags
        if (!base.artist) {
            const match = fileName.match(/^(.+?)\s+-\s+(.+)$/);
            if (match && base.title === fileName) {
                base.artist = match[1];
                base.title = match[2];
            }
        }
        if (!base.album) base.album = path.basename(path.dirname(filePath));
        return base;
    }

    private rebuild(list: LocalSong[]) {
        const songs = new Map<string, LocalSong>();
        const albums = new Map<string, LocalAlbum>();
        const artists = new Map<string, LocalArtist>();
        const unknownArtist = 'Неизвестный исполнитель';

        for (const song of list) {
            const albumArtist = song.albumArtist || song.artist || unknownArtist;
            song.artist ||= unknownArtist;
            song.artistId = hashId(`artist:${song.artist}`);
            song.albumId = hashId(`album:${albumArtist}:${song.album}`);
            songs.set(song.id, song);

            let album = albums.get(song.albumId);
            if (!album) {
                album = {
                    artist: albumArtist,
                    artistId: hashId(`artist:${albumArtist}`),
                    created: song.created,
                    duration: 0,
                    genre: song.genre,
                    id: song.albumId,
                    name: song.album,
                    songs: [],
                    year: song.year,
                };
                albums.set(album.id, album);
            }
            album.songs.push(song);
            album.duration += song.duration;
            if (song.created > album.created) album.created = song.created;
            album.year ||= song.year;
            album.genre ||= song.genre;
        }

        for (const album of albums.values()) {
            album.songs.sort(
                (a, b) => a.disc - b.disc || a.track - b.track || a.title.localeCompare(b.title),
            );
            let artist = artists.get(album.artistId);
            if (!artist) {
                artist = { albums: [], id: album.artistId, name: album.artist };
                artists.set(artist.id, artist);
            }
            artist.albums.push(album);
        }

        this.songs = songs;
        this.albums = albums;
        this.artists = artists;
    }

    /** Configured folders + downloads, without folders nested in another one */
    private roots() {
        const all = [
            ...new Set([...this.user.folders, downloadsDir()].map((f) => path.resolve(f))),
        ];
        const inside = (child: string, parent: string) =>
            child !== parent && child.toLowerCase().startsWith(parent.toLowerCase() + path.sep);
        return all.filter((folder) => !all.some((other) => inside(folder, other)));
    }

    private async walk(dir: string, out: string[], depth = 0) {
        if (depth > 12) return;
        let entries: import('fs').Dirent[];
        try {
            entries = await fs.readdir(dir, { withFileTypes: true });
        } catch {
            return;
        }
        for (const entry of entries) {
            if (entry.name.startsWith('.')) continue;
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) await this.walk(full, out, depth + 1);
            else if (AUDIO_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) out.push(full);
        }
    }
}

export const library = new Library();
