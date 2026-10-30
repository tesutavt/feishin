import type { LocalLibraryInfo, RemoteAccessSettings } from '/@/shared/types/local-library';

import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron';
import os from 'os';

import { downloadsDir, library } from './library';
import { startLocalServer } from './server';
import { importSpotify } from './spotify';

import log from '/@/main/logger';

let url: null | string = null;
let port = 0;

const start = async () => {
    port = await startLocalServer();
    url = `http://127.0.0.1:${port}`;
};

const ready = app.whenReady().then(async () => {
    await library.load();
    await start();
    log.info('Local library ready', {
        remote: library.user.remote.enabled,
        songs: library.songs.size,
        url,
    });
    library.scan();
});

const lanAddresses = () =>
    Object.values(os.networkInterfaces())
        .flat()
        .filter((net) => net && net.family === 'IPv4' && !net.internal)
        .map((net) => `http://${net!.address}:${port}`);

const info = (): LocalLibraryInfo => ({
    addresses: library.user.remote.enabled ? lanAddresses() : [],
    count: library.songs.size,
    deviceName: os.hostname(),
    downloadsDir: downloadsDir(),
    folders: library.user.folders,
    remote: library.user.remote,
    scanning: library.scanning,
    url: url ?? '',
});

library.onChange(() => {
    for (const window of BrowserWindow.getAllWindows()) {
        window.webContents.send('local-library-changed', info());
    }
});

ipcMain.handle('local-library-info', async () => {
    await ready;
    return info();
});

ipcMain.handle('local-library-add-folder', async () => {
    const result = await dialog.showOpenDialog({
        properties: ['openDirectory', 'multiSelections'],
    });
    if (!result.canceled && result.filePaths.length) {
        library.setFolders([...library.user.folders, ...result.filePaths]);
    }
    return info();
});

ipcMain.handle('local-library-remove-folder', (_event, folder: string) => {
    library.setFolders(library.user.folders.filter((f) => f !== folder));
    return info();
});

ipcMain.handle('local-library-scan', () => {
    library.scan();
    return { ...info(), scanning: true };
});

ipcMain.handle(
    'local-library-set-remote',
    async (_event, remote: Partial<RemoteAccessSettings>) => {
        await ready;
        const next = { ...library.user.remote, ...remote };
        next.username = next.username.trim() || 'tesu';
        next.port = Math.min(65535, Math.max(1024, Number(next.port) || 45173));
        library.user.remote = next;
        library.save();
        await start();
        const result = info();
        for (const window of BrowserWindow.getAllWindows()) {
            window.webContents.send('local-library-changed', result);
        }
        return result;
    },
);

ipcMain.handle(
    'local-library-import-spotify',
    async (event, input: { csv?: string; name?: string; url?: string }) => {
        await ready;
        return importSpotify(input, (done, total) =>
            event.sender.send('local-library-import-progress', { done, total }),
        );
    },
);

ipcMain.handle('local-library-open-folder', (_event, folder: string) => shell.openPath(folder));
