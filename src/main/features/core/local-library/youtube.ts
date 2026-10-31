import { library } from './library';

// YouTube videos as regular songs of the library: the UI queues them like local
// tracks (seek, next, favourites), the stream endpoint redirects to the audio.

export interface YoutubeResolved extends Omit<YoutubeSong, 'id' | 'videoId'> {
    url: string;
}

export interface YoutubeSong {
    artist: string;
    duration: number;
    id: string;
    thumbnail: null | string;
    title: string;
    videoId: string;
}

const PREFIX = 'yt-';
const songs = new Map<string, YoutubeSong>();
const streams = new Map<string, { expires: number; url: string }>();
let resolver: ((videoId: string) => Promise<YoutubeResolved>) | null = null;
let mixProvider: ((videoId: string) => Promise<Omit<YoutubeSong, 'id'>[]>) | null = null;

export const setYoutubeMixProvider = (
    fn: (videoId: string) => Promise<Omit<YoutubeSong, 'id'>[]>,
) => {
    mixProvider = fn;
};

/** YouTube Mix (radio) of a video: what Auto-DJ plays after YouTube songs */
export const getYoutubeMix = async (id: string, count: number) => {
    if (!mixProvider) return [];
    const items = await mixProvider(id.slice(PREFIX.length));
    return registerYoutube(items.filter((item) => youtubeId(item.videoId) !== id).slice(0, count))
        .map((songId) => songs.get(songId)!)
        .filter(Boolean);
};

export const isYoutubeId = (id: string) => id.startsWith(PREFIX);

export const youtubeId = (videoId: string) => `${PREFIX}${videoId}`;

export const setYoutubeResolver = (fn: (videoId: string) => Promise<YoutubeResolved>) => {
    resolver = fn;
};

const remember = (song: YoutubeSong) => {
    songs.set(song.id, song);
    const stored = (library.user.youtube ??= {});
    const prev = stored[song.id];
    if (
        !prev ||
        prev.title !== song.title ||
        prev.artist !== song.artist ||
        prev.duration !== song.duration
    ) {
        stored[song.id] = song;
        library.save();
    }
};

export const registerYoutube = (items: Omit<YoutubeSong, 'id'>[]) =>
    items.map((item) => {
        const song = { ...item, id: youtubeId(item.videoId) };
        remember(song);
        return song.id;
    });

/** Known without asking YouTube (this session or saved earlier) */
export const knownYoutubeSong = (id: string): undefined | YoutubeSong =>
    songs.get(id) ?? library.user.youtube?.[id];

const resolve = async (id: string) => {
    if (!resolver) throw new Error('yt-dlp is not ready');
    const videoId = id.slice(PREFIX.length);
    const resolved = await resolver(videoId);
    // googlevideo links carry their expiry time
    const expire = Number(new URL(resolved.url).searchParams.get('expire')) * 1000;
    streams.set(id, {
        expires: (expire || Date.now() + 3 * 3600_000) - 5 * 60_000,
        url: resolved.url,
    });
    if (!songs.has(id)) {
        const { artist, duration, thumbnail, title } = resolved;
        remember({ artist, duration, id, thumbnail, title, videoId });
    }
    return resolved;
};

/** Metadata; after a restart (restored queue) it is fetched again */
export const getYoutubeSong = async (id: string) => {
    const known = knownYoutubeSong(id);
    if (known) return known;
    await resolve(id);
    return songs.get(id)!;
};

export const getYoutubeStreamUrl = async (id: string) => {
    const cached = streams.get(id);
    if (cached && cached.expires > Date.now()) return cached.url;
    return (await resolve(id)).url;
};

export const getYoutubeThumbnail = (id: string) =>
    knownYoutubeSong(id)?.thumbnail ||
    `https://i.ytimg.com/vi/${id.slice(PREFIX.length)}/hqdefault.jpg`;

const videoIdOfUrl = (url?: string) =>
    url ? /(?:[?&]v=|youtu\.be\/|shorts\/)([\w-]{6,})/.exec(url)?.[1] : undefined;

/** The downloaded file of a YouTube video, if the library has one */
export const downloadedCopyOf = (videoId: string) => {
    for (const song of library.songs.values()) {
        if (videoIdOfUrl(song.sourceUrl) === videoId) return song;
    }
    return undefined;
};

/**
 * The YouTube song was downloaded: its favourite, rating, plays and playlist places move
 * to the file, so it is one song, not a stream and a copy side by side
 */
export const replaceYoutubeWithFile = (ytId: string, fileId: string) => {
    const user = library.user;
    let changed = false;
    if (user.starred[ytId]) {
        user.starred[fileId] ??= user.starred[ytId];
        delete user.starred[ytId];
        changed = true;
    }
    if (user.ratings[ytId]) {
        user.ratings[fileId] ||= user.ratings[ytId];
        delete user.ratings[ytId];
        changed = true;
    }
    if (user.playCounts[ytId]) {
        user.playCounts[fileId] = (user.playCounts[fileId] ?? 0) + user.playCounts[ytId];
        delete user.playCounts[ytId];
        changed = true;
    }
    if (user.lastPlayed[ytId]) {
        if (!user.lastPlayed[fileId] || user.lastPlayed[fileId] < user.lastPlayed[ytId]) {
            user.lastPlayed[fileId] = user.lastPlayed[ytId];
        }
        delete user.lastPlayed[ytId];
        changed = true;
    }
    for (const playlist of user.playlists) {
        if (!playlist.songIds.includes(ytId)) continue;
        // the file may be in the playlist already: keep one entry
        const has = playlist.songIds.includes(fileId);
        playlist.songIds = playlist.songIds.flatMap((id) =>
            id === ytId ? (has ? [] : [fileId]) : [id],
        );
        playlist.changed = new Date().toISOString();
        changed = true;
    }
    const queue = user.playQueue;
    if (queue?.songIds.includes(ytId)) {
        queue.songIds = queue.songIds.map((id) => (id === ytId ? fileId : id));
        if (queue.current === ytId) queue.current = fileId;
        changed = true;
    }
    if (changed) library.save();
    return changed;
};

/** Earlier downloads: YouTube songs in favourites / playlists that already have their file */
export const replaceDownloadedYoutubeSongs = () => {
    const user = library.user;
    const ids = new Set([
        ...Object.keys(user.ratings),
        ...Object.keys(user.starred),
        ...user.playlists.flatMap((p) => p.songIds),
    ]);
    let replaced = 0;
    for (const id of ids) {
        if (!isYoutubeId(id)) continue;
        const file = downloadedCopyOf(id.slice(PREFIX.length));
        if (file && replaceYoutubeWithFile(id, file.id)) replaced++;
    }
    return replaced;
};
