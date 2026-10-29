import type { LocalLibraryInfo, RemoteAccessSettings } from '/@/shared/types/local-library';

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
    onChange,
    openFolder,
    removeFolder,
    scan,
    setRemote,
};
