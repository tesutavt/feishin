import type {
    LocalLibraryInfo,
    RemoteAccessSettings,
    SpotifyImportResult,
} from '/@/shared/types/local-library';

import { ipcRenderer, IpcRendererEvent } from 'electron';

const getInfo = (): Promise<LocalLibraryInfo> => ipcRenderer.invoke('local-library-info');

const addFolder = (): Promise<LocalLibraryInfo> => ipcRenderer.invoke('local-library-add-folder');

const removeFolder = (folder: string): Promise<LocalLibraryInfo> =>
    ipcRenderer.invoke('local-library-remove-folder', folder);

const setRemote = (remote: Partial<RemoteAccessSettings>): Promise<LocalLibraryInfo> =>
    ipcRenderer.invoke('local-library-set-remote', remote);

const scan = (): Promise<LocalLibraryInfo> => ipcRenderer.invoke('local-library-scan');

const openFolder = (folder: string): Promise<string> =>
    ipcRenderer.invoke('local-library-open-folder', folder);

/** A Spotify link or the text of an exported CSV; progress while tracks are matched */
const importSpotify = (
    input: { csv?: string; name?: string; url?: string },
    onProgress?: (done: number, total: number) => void,
): Promise<SpotifyImportResult> => {
    const listener = (_event: IpcRendererEvent, p: { done: number; total: number }) =>
        onProgress?.(p.done, p.total);
    ipcRenderer.on('local-library-import-progress', listener);
    return ipcRenderer
        .invoke('local-library-import-spotify', input)
        .finally(() => ipcRenderer.removeListener('local-library-import-progress', listener));
};

const onChange = (callback: (info: LocalLibraryInfo) => void) => {
    const listener = (_event: IpcRendererEvent, info: LocalLibraryInfo) => callback(info);
    ipcRenderer.on('local-library-changed', listener);
    return () => {
        ipcRenderer.removeListener('local-library-changed', listener);
    };
};

export const localLibrary = {
    addFolder,
    getInfo,
    importSpotify,
    onChange,
    openFolder,
    removeFolder,
    scan,
    setRemote,
};
