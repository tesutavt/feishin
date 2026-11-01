import type { SpotifyImportResult } from '/@/shared/types/local-library';

import { session } from 'electron';
import { nanoid } from 'nanoid/non-secure';

import { library } from './library';

import { store } from '/@/main/features/core/settings';
import log from '/@/main/logger';

// Spotify playlists / albums without a Spotify account or API key: the public embed
// page lists the tracks (up to ~100), an Exportify-style CSV covers longer playlists.
// Every track becomes a song of this library (own file first, YouTube otherwise).

export interface ImportTrack {
    artist: string;
    duration?: number;
    title: string;
}

/** Set by the streams feature: the first YouTube result for a query, as a library song id */
let youtubeFinder: ((query: string) => Promise<null | string>) | null = null;

export const setSpotifyYoutubeFinder = (fn: (query: string) => Promise<null | string>) => {
    youtubeFinder = fn;
};

const LINK =
    /(?:open\.spotify\.com\/(?:intl-\w+\/)?|spotify:)(playlist|album|track)[/:]([A-Za-z0-9]+)/;

export const isSpotifyLink = (text: string) => LINK.test(text);

const PROXY_KEY = 'spotify_proxy';

export const getSpotifyProxy = () => String(store.get(PROXY_KEY, '') ?? '');

/**
 * Spotify answers 451 "not available in your country" from Russia, so the list can come
 * through a proxy (a VPN app's local port): only these requests use it, YouTube does not
 */
export const setSpotifyProxy = async (value: string) => {
    const proxy = value.trim();
    store.set(PROXY_KEY, proxy);
    await spotifySession().setProxy(proxy ? { proxyRules: proxy } : { mode: 'direct' });
    return proxy;
};

let spotifySessionReady = false;
const spotifySession = () => {
    const s = session.fromPartition('spotify-import');
    if (!spotifySessionReady) {
        spotifySessionReady = true;
        const proxy = getSpotifyProxy();
        if (proxy) s.setProxy({ proxyRules: proxy });
    }
    return s;
};

const GEO_BLOCKED =
    'Spotify не работает в России: на запросы отсюда он отвечает «недоступно в вашей стране». ' +
    'Включите VPN для TesuPlayer на время импорта, укажите прокси ниже (например, 127.0.0.1:10808 из ' +
    'VPN-приложения) или загрузите CSV: он импортируется без Spotify.';

export const fetchSpotify = async (
    link: string,
): Promise<{ name: string; tracks: ImportTrack[] }> => {
    const match = LINK.exec(link);
    if (!match) throw new Error('Это не ссылка на плейлист или альбом Spotify');
    const [, type, id] = match;
    let response: Response;
    try {
        response = await spotifySession().fetch(`https://open.spotify.com/embed/${type}/${id}`, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' },
        });
    } catch (error) {
        const proxy = getSpotifyProxy();
        throw new Error(
            proxy
                ? `Spotify: прокси ${proxy} не отвечает (${(error as Error).message})`
                : `Spotify: ${(error as Error).message}`,
        );
    }
    if (response.status === 451) throw new Error(GEO_BLOCKED);
    if (!response.ok) throw new Error(`Spotify: HTTP ${response.status}`);
    const html = await response.text();
    const json = /<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/.exec(
        html,
    );
    const pageProps = json ? JSON.parse(json[1])?.props?.pageProps : null;
    // the page is 200 but carries {status: 451, "not available in your country"}
    if (pageProps?.status === 451 || /not available in your country/i.test(html)) {
        log.warn('Spotify import: geo-blocked', { link });
        throw new Error(GEO_BLOCKED);
    }
    const entity = pageProps?.state?.data?.entity;
    if (!entity) throw new Error('Spotify не отдал список треков (плейлист приватный?)');
    const list: any[] = entity.trackList ?? [];
    return {
        name: entity.name || entity.title || 'Spotify',
        tracks: list
            .filter((t) => t?.title)
            .map((t) => ({
                artist: String(t.subtitle ?? '').replace(/\u00a0/g, ' '),
                duration: t.duration ? Math.round(t.duration / 1000) : undefined,
                title: t.title,
            })),
    };
};

const splitCsvLine = (line: string) => {
    const cells: string[] = [];
    let cell = '';
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (quoted) {
            if (c === '"' && line[i + 1] === '"') {
                cell += '"';
                i++;
            } else if (c === '"') quoted = false;
            else cell += c;
        } else if (c === '"') quoted = true;
        else if (c === ',' || c === ';') {
            cells.push(cell);
            cell = '';
        } else cell += c;
    }
    cells.push(cell);
    return cells.map((value) => value.trim());
};

/** Exportify, TuneMyMusic, Soundiiz...: columns with the track name and the artist */
export const parseCsv = (text: string): ImportTrack[] => {
    const [header, ...rows] = text.split(/\r?\n/).filter((line) => line.trim());
    if (!header) return [];
    const names = splitCsvLine(header).map((name) => name.toLowerCase());
    // the most specific key wins: "Track Name" over "Track URI"
    const column = (...keys: string[]) => {
        for (const key of keys) {
            const index = names.findIndex((name) => name.includes(key) && !name.includes('uri'));
            if (index >= 0) return index;
        }
        return -1;
    };
    const title = column('track name', 'title', 'название', 'track', 'name');
    const artist = column('artist name', 'artist', 'исполнитель');
    const duration = column('duration (ms)', 'duration');
    if (title < 0) throw new Error('В CSV нет колонки с названием трека');
    return rows
        .map(splitCsvLine)
        .filter((cells) => cells[title])
        .map((cells) => {
            const ms = Number(cells[duration]);
            return {
                artist: artist >= 0 ? cells[artist] : '',
                duration: ms > 0 ? Math.round(ms > 10_000 ? ms / 1000 : ms) : undefined,
                title: cells[title],
            };
        });
};

const normalize = (value: string) =>
    value
        .toLowerCase()
        .replace(/\(.*?\)|\[.*?\]|- .*remaster.*$/g, '')
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .trim();

const findOwn = (track: ImportTrack) => {
    const title = normalize(track.title);
    const artists = track.artist
        .split(/,|&|feat\./i)
        .map(normalize)
        .filter(Boolean);
    for (const song of library.songs.values()) {
        if (normalize(song.title) !== title) continue;
        const artist = normalize(song.artist);
        if (!artists.length || artists.some((a) => artist.includes(a) || a.includes(artist))) {
            return song.id;
        }
    }
    return null;
};

export const importTracks = async (
    name: string,
    tracks: ImportTrack[],
    onProgress?: (done: number, total: number) => void,
): Promise<SpotifyImportResult> => {
    const ids: (null | string)[] = new Array(tracks.length).fill(null);
    let done = 0;
    let next = 0;
    // a few yt-dlp searches at a time
    const worker = async () => {
        while (next < tracks.length) {
            const i = next++;
            const track = tracks[i];
            ids[i] = findOwn(track);
            if (!ids[i] && youtubeFinder) {
                const query = `${track.artist.split(',')[0]} - ${track.title}`;
                ids[i] = await youtubeFinder(query).catch((error) => {
                    log.warn('Spotify import: YouTube search failed', {
                        error: String(error),
                        query,
                    });
                    return null;
                });
            }
            onProgress?.(++done, tracks.length);
        }
    };
    await Promise.all(Array.from({ length: 4 }, worker));

    const now = new Date().toISOString();
    const playlist = {
        changed: now,
        created: now,
        id: nanoid(),
        name,
        songIds: ids.filter((id): id is string => Boolean(id)),
    };
    library.user.playlists.push(playlist);
    library.save();
    log.info('Spotify import', { found: playlist.songIds.length, name, total: tracks.length });
    return {
        found: playlist.songIds.length,
        missing: tracks.filter((_, i) => !ids[i]).map((t) => `${t.artist} - ${t.title}`),
        name,
        playlistId: playlist.id,
        total: tracks.length,
    };
};

/** A link or the text of a CSV file */
export const importSpotify = async (
    input: { csv?: string; name?: string; url?: string },
    onProgress?: (done: number, total: number) => void,
) => {
    const source = input.csv
        ? { name: input.name || 'Импорт из Spotify', tracks: parseCsv(input.csv) }
        : await fetchSpotify(input.url ?? '');
    if (!source.tracks.length) throw new Error('Треков не найдено');
    return importTracks(input.name || source.name, source.tracks, onProgress);
};
