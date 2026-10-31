import type {
    DownloadedTrack,
    ExternalSearchResult,
    ResolvedExternalStream,
    YtDlpStatus,
} from '/@/shared/types/streams';

import { ipcRenderer } from 'electron';

const getYtDlpStatus = (): Promise<YtDlpStatus> => ipcRenderer.invoke('streams-ytdlp-status');

const downloadYtDlp = (): Promise<YtDlpStatus> => ipcRenderer.invoke('streams-ytdlp-download');

const downloadAudio = (url: string): Promise<DownloadedTrack> =>
    ipcRenderer.invoke('streams-download-audio', url);

const getAutoDownload = (): Promise<boolean> => ipcRenderer.invoke('streams-auto-download-get');

const setAutoDownload = (on: boolean): Promise<boolean> =>
    ipcRenderer.invoke('streams-auto-download-set', on);

/** The player started a song: with auto-download on, a YouTube song is saved as a file */
const nowPlaying = (songId: string): Promise<DownloadedTrack | null> =>
    ipcRenderer.invoke('streams-now-playing', songId);

const registerYoutube = (items: ExternalSearchResult[]): Promise<string[]> =>
    ipcRenderer.invoke('streams-register-youtube', items);

const retagDownloads = (): Promise<{ fixed: number; total: number }> =>
    ipcRenderer.invoke('streams-retag-downloads');

const publicCover = (
    artist: string,
    album: string,
    title: string,
    songId?: string,
): Promise<null | string> =>
    ipcRenderer.invoke('streams-public-cover', artist, album, title, songId);

const resolve = (url: string): Promise<ResolvedExternalStream> =>
    ipcRenderer.invoke('streams-resolve', url);

const searchYoutube = (query: string): Promise<ExternalSearchResult[]> =>
    ipcRenderer.invoke('streams-search-youtube', query);

export const streams = {
    downloadAudio,
    downloadYtDlp,
    getAutoDownload,
    getYtDlpStatus,
    nowPlaying,
    publicCover,
    registerYoutube,
    resolve,
    retagDownloads,
    searchYoutube,
    setAutoDownload,
};

export type Streams = typeof streams;
