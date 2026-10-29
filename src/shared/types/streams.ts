// YouTube / Twitch playback through yt-dlp (Electron only).

export interface DownloadedTrack {
    artist: string;
    path: string;
    title: string;
}

export interface ExternalSearchResult {
    duration: null | number;
    id: string;
    thumbnail: null | string;
    title: string;
    uploader: null | string;
    url: string;
}

export type ExternalStreamSource = 'twitch' | 'youtube';

export interface ResolvedExternalStream {
    artist: null | string;
    isLive: boolean;
    source: ExternalStreamSource;
    streamUrl: string;
    thumbnail: null | string;
    title: null | string;
}

export interface YtDlpStatus {
    path: null | string;
    version: null | string;
}

const YOUTUBE_HOST = /(^|\.)(youtube\.com|youtu\.be|youtube-nocookie\.com)$/i;
const TWITCH_HOST = /(^|\.)twitch\.tv$/i;

export const getExternalStreamSource = (url: null | string | undefined) => {
    if (!url) return null;

    try {
        const { hostname } = new URL(url.trim());
        if (YOUTUBE_HOST.test(hostname)) return 'youtube' as const;
        if (TWITCH_HOST.test(hostname)) return 'twitch' as const;
    } catch {
        // not a URL
    }

    return null;
};

export const isExternalStreamUrl = (url: null | string | undefined) =>
    getExternalStreamSource(url) !== null;
