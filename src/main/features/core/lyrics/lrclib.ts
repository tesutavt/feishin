// Credits to https://github.com/tranxuanthang/lrcget for API implementation
import axios, { AxiosResponse } from 'axios';

import {
    InternetProviderLyricResponse,
    InternetProviderLyricSearchResponse,
    LyricSearchQuery,
    LyricSource,
} from '.';
import { orderSearchResults } from './shared';

import log from '/@/main/logger';

const FETCH_URL = 'https://lrclib.net/api/get';
const SEEARCH_URL = 'https://lrclib.net/api/search';

const TIMEOUT_MS = 5000;

export interface LrcLibSearchResponse {
    albumName: string;
    artistName: string;
    duration?: number;
    id: number;
    instrumental?: boolean;
    name: string;
    plainLyrics: null | string;
    syncedLyrics: null | string;
}

export interface LrcLibTrackResponse {
    albumName: string;
    artistName: string;
    duration: number;
    id: number;
    instrumental: boolean;
    isrc: string;
    lang: string;
    name: string;
    plainLyrics: null | string;
    releaseDate: string;
    spotifyId: string;
    syncedLyrics: null | string;
}

export async function getLyricsBySongId(songId: string): Promise<null | string> {
    let result: AxiosResponse<LrcLibTrackResponse, any>;

    try {
        result = await axios.get<LrcLibTrackResponse>(`${FETCH_URL}/${songId}`);
    } catch (e) {
        log.error('LrcLib lyrics request got an error!', (e as Error)?.message);
        return null;
    }

    return result.data.syncedLyrics || result.data.plainLyrics || null;
}

export async function getSearchResults(
    params: LyricSearchQuery,
): Promise<InternetProviderLyricSearchResponse[] | null> {
    let result: AxiosResponse<LrcLibSearchResponse[]>;

    if (!params.name && !params.artist) {
        return null;
    }

    const searchQuery = [params.name, params.artist].join(' ');

    try {
        result = await axios.get<LrcLibSearchResponse[]>(SEEARCH_URL, {
            params: {
                q: searchQuery,
            },
        });
    } catch (e) {
        log.error('LrcLib search request got an error!', (e as Error)?.message);
        return null;
    }

    if (!result.data) return null;

    const songResults: InternetProviderLyricSearchResponse[] = result.data.map((song) => {
        return {
            artist: song.artistName,
            id: String(song.id),
            isSync: song.syncedLyrics ? true : false,
            name: song.name,
            source: LyricSource.LRCLIB,
        };
    });

    return orderSearchResults({ params, results: songResults });
}

export async function query(
    params: LyricSearchQuery,
): Promise<InternetProviderLyricResponse | null> {
    let result: AxiosResponse<LrcLibTrackResponse, any>;

    const headers = { 'User-Agent': 'LRCGET v0.2.0 (https://github.com/tesutavt/TesuPlayer)' };
    // lrclib.net answers 503 now and then: one retry
    const request = async <T>(url: string, query: Record<string, unknown>) => {
        for (let attempt = 0; ; attempt++) {
            try {
                return await axios.get<T>(url, { headers, params: query, timeout: TIMEOUT_MS });
            } catch (e) {
                const status = (e as { response?: { status?: number } }).response?.status;
                if (status !== 503 || attempt > 0) throw e;
                await new Promise((resolve) => setTimeout(resolve, 800));
            }
        }
    };

    try {
        result = await request<LrcLibTrackResponse>(FETCH_URL, {
            album_name: params.album,
            artist_name: params.artist,
            duration: params.duration,
            track_name: params.name,
        });
    } catch {
        // exact match failed (other album name, YouTube upload...): search by artist + title
        try {
            const found = await request<(LrcLibTrackResponse & { duration?: number })[]>(
                SEEARCH_URL,
                {
                    artist_name: params.artist,
                    track_name: params.name,
                },
            );
            const seconds = Number(params.duration) || 0;
            const withLyrics = found.data.filter((r) => r.syncedLyrics || r.plainLyrics);
            const best =
                withLyrics.find(
                    (r) =>
                        r.syncedLyrics && (!seconds || Math.abs((r.duration ?? 0) - seconds) <= 6),
                ) ??
                withLyrics.find((r) => !seconds || Math.abs((r.duration ?? 0) - seconds) <= 6) ??
                withLyrics[0];
            if (!best) return null;
            result = { ...found, data: best } as AxiosResponse<LrcLibTrackResponse>;
        } catch (e) {
            log.error('LrcLib search request got an error!', (e as Error).message);
            return null;
        }
    }

    const lyrics = result.data.syncedLyrics || result.data.plainLyrics || null;

    if (!lyrics) {
        return null;
    }

    return {
        artist: result.data.artistName,
        id: String(result.data.id),
        lyrics,
        name: result.data.name,
        source: LyricSource.LRCLIB,
    };
}
