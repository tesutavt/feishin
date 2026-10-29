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

export const registerYoutube = (items: Omit<YoutubeSong, 'id'>[]) =>
    items.map((item) => {
        const song = { ...item, id: youtubeId(item.videoId) };
        songs.set(song.id, song);
        return song.id;
    });

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
        songs.set(id, { artist, duration, id, thumbnail, title, videoId });
    }
    return resolved;
};

/** Metadata; after a restart (restored queue) it is fetched again */
export const getYoutubeSong = async (id: string) => {
    const known = songs.get(id);
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
    songs.get(id)?.thumbnail || `https://i.ytimg.com/vi/${id.slice(PREFIX.length)}/hqdefault.jpg`;
