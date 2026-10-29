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

const resolve = (url: string): Promise<ResolvedExternalStream> =>
    ipcRenderer.invoke('streams-resolve', url);

const searchYoutube = (query: string): Promise<ExternalSearchResult[]> =>
    ipcRenderer.invoke('streams-search-youtube', query);

export const streams = {
    downloadAudio,
    downloadYtDlp,
    getYtDlpStatus,
    resolve,
    searchYoutube,
};

export type Streams = typeof streams;
