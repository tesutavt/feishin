import { net } from 'electron';

import log from '/@/main/logger';

// Real album / artist / cover for audio taken from YouTube, so the library
// sorts it like any other music instead of one big "YouTube" album.

export interface MusicTags {
    album: string;
    artist: string;
    cover?: Uint8Array;
    coverUrl?: string;
    genre?: string;
    title: string;
    track?: number;
    year?: number;
}

const normalize = (value: string) =>
    value
        .toLowerCase()
        .replace(/\(.*?\)|\[.*?\]/g, ' ')
        .replace(/\b(feat|ft)\.?\s.*$/, ' ')
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .trim();

const similar = (a: string, b: string) => {
    const x = normalize(a);
    const y = normalize(b);
    return Boolean(x && y) && (x === y || x.includes(y) || y.includes(x));
};

/** "Artist - Title (Official Video)" and friends -> artist + clean title */
export const parseVideoTitle = (info: any): { artist: string; title: string } => {
    const channel = String(info.uploader || info.channel || '')
        .replace(/ - Topic$/, '')
        .replace(/VEVO$/i, '')
        .trim();
    let title = String(info.track || info.title || info.id || '');
    let artist = info.track && info.artist ? String(info.artist).split(',')[0].trim() : '';
    if (!artist) {
        const match = title.match(/^(.+?)\s+[-–—|]\s+(.+)$/);
        if (match) {
            artist = match[1].trim();
            title = match[2].trim();
        } else {
            artist = channel;
        }
    }
    title = title
        // "(Official HD Video)", "[HD Upgrade]", "(Lyric Video)", "(Премьера клипа, 2024)"...
        .replace(
            /\s*[([][^)\]]*(official|video|audio|lyric|visuali[sz]er|\bmv\b|\bhd\b|\bhq\b|4k|upgrade|remaster|клип|премьера|premiere)[^)\]]*[)\]]/gi,
            '',
        )
        .replace(/\s*\|.*$/, '')
        .trim();
    return { artist: artist || 'YouTube', title: title || String(info.id) };
};

const fetchImage = async (url: string): Promise<Uint8Array | undefined> => {
    try {
        const response = await net.fetch(url);
        const type = response.headers.get('content-type') ?? '';
        if (response.ok && type.startsWith('image/'))
            return new Uint8Array(await response.arrayBuffer());
    } catch {
        // no cover
    }
    return undefined;
};

/** iTunes Search: free, no key; returns album, track number, year and a square cover */
const lookupItunes = async (
    artist: string,
    title: string,
): Promise<null | Omit<MusicTags, 'cover'>> => {
    try {
        const term = encodeURIComponent(`${artist} ${title}`);
        const response = await net.fetch(
            `https://itunes.apple.com/search?term=${term}&entity=song&media=music&limit=15`,
        );
        if (!response.ok) return null;
        const { results = [] } = (await response.json()) as { results?: any[] };
        const hit =
            results.find(
                (r) => similar(r.artistName, artist) && normalize(r.trackName) === normalize(title),
            ) ?? results.find((r) => similar(r.artistName, artist) && similar(r.trackName, title));
        if (!hit) return null;
        return {
            album: String(hit.collectionName || title).replace(/\s+-\s+(Single|EP)$/i, ''),
            artist: hit.artistName,
            coverUrl: hit.artworkUrl100?.replace(/\/\d+x\d+bb\./, '/600x600bb.'),
            genre: hit.primaryGenreName,
            title: hit.trackName,
            track: hit.trackNumber || undefined,
            year: parseInt(String(hit.releaseDate).slice(0, 4), 10) || undefined,
        };
    } catch (error) {
        log.warn('iTunes lookup failed', { error: String(error) });
        return null;
    }
};

const publicCovers = new Map<string, null | string>();

/** A public https album cover (iTunes catalogue) for Discord, cached */
export const findPublicCover = async (
    artist: string,
    album: string,
    title: string,
    /** YouTube page of a downloaded track: its thumbnail is the fallback */
    sourceUrl?: string,
): Promise<null | string> => {
    const key = `${artist}|${album}|${title}|${sourceUrl ?? ''}`.toLowerCase();
    if (publicCovers.has(key)) return publicCovers.get(key)!;
    let url: null | string = null;
    const search = async (term: string, entity: 'album' | 'song') => {
        const response = await net.fetch(
            `https://itunes.apple.com/search?term=${encodeURIComponent(term)}&entity=${entity}&media=music&limit=10`,
        );
        if (!response.ok) return [];
        return ((await response.json()) as { results?: any[] }).results ?? [];
    };
    try {
        if (album && album !== 'YouTube') {
            const albums = await search(`${artist} ${album}`, 'album');
            const hit = albums.find(
                (r) => similar(r.artistName, artist) && similar(r.collectionName, album),
            );
            url = hit?.artworkUrl100 ?? null;
        }
        if (!url && title) {
            const songs = await search(`${artist} ${title}`, 'song');
            const hit = songs.find(
                (r) => similar(r.artistName, artist) && similar(r.trackName, title),
            );
            url = hit?.artworkUrl100 ?? null;
        }
    } catch (error) {
        log.warn('Public cover lookup failed', { error: String(error) });
    }
    url = url?.replace(/\/\d+x\d+bb\./, '/512x512bb.') ?? null;
    // not in the iTunes catalogue (covers, VTubers, J-pop...): the video's thumbnail is public too
    const videoId = sourceUrl && /(?:[?&]v=|youtu\.be\/)([\w-]{6,})/.exec(sourceUrl)?.[1];
    if (!url && videoId) url = `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
    publicCovers.set(key, url);
    return url;
};

/**
 * YouTube Music data from yt-dlp first, then the iTunes catalogue,
 * otherwise a single (album = title).
 */
export const findMusicTags = async (info: any, fallbackCover?: string): Promise<MusicTags> => {
    const parsed = parseVideoTitle(info);
    const year =
        parseInt(String(info.release_year || info.upload_date || '').slice(0, 4), 10) || undefined;

    let tags: MusicTags = { album: parsed.title, artist: parsed.artist, title: parsed.title, year };
    if (info.album && info.track) {
        tags = { ...tags, album: String(info.album) };
    }
    const found = await lookupItunes(parsed.artist, parsed.title);
    if (found) {
        tags = { ...tags, ...found, album: info.album ? String(info.album) : found.album };
    }
    tags.cover =
        (tags.coverUrl ? await fetchImage(tags.coverUrl) : undefined) ??
        (fallbackCover ? await fetchImage(fallbackCover) : undefined);
    return tags;
};
