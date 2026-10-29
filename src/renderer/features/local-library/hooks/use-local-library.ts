import isElectron from 'is-electron';
import md5 from 'md5';
import { nanoid } from 'nanoid/non-secure';
import { useEffect, useState } from 'react';

import { useDeviceHeartbeat } from '/@/renderer/features/devices/hooks/use-devices';
import { queryClient } from '/@/renderer/lib/react-query';
import { useAuthStore } from '/@/renderer/store';
import { logger } from '/@/renderer/utils/logger';
import { ServerListItemWithCredential, ServerType } from '/@/shared/types/domain-types';
import {
    LOCAL_SERVER_ID,
    LocalLibraryInfo,
    REMOTE_SERVER_ID,
    RemoteLibrary,
} from '/@/shared/types/local-library';

// The built-in library speaks the Subsonic API (src/main/features/core/local-library),
// so it is the one "server" of the app: this computer, or another one running TesuPlayer.

const REMOTE_KEY = 'tesuplayer-remote-library';

export const getRemoteLibrary = (): null | RemoteLibrary => {
    try {
        return JSON.parse(localStorage.getItem(REMOTE_KEY) ?? 'null');
    } catch {
        return null;
    }
};

const credentialFor = (username: string, password: string) => {
    const salt = nanoid(12);
    return `u=${encodeURIComponent(username)}&s=${salt}&t=${md5(password + salt)}`;
};

const toServer = (
    id: string,
    name: string,
    url: string,
    credential: string,
): ServerListItemWithCredential => ({
    credential,
    features: {},
    id,
    isAdmin: true,
    name,
    type: ServerType.SUBSONIC,
    url,
    userId: 'local',
    username: 'local',
});

const applyServer = (server: ServerListItemWithCredential) =>
    useAuthStore.setState((state) => {
        state.serverList = { [server.id]: server };
        state.currentServer = server;
    });

/** Checks login on another TesuPlayer computer; throws with its error message */
export const testRemoteLibrary = async (remote: RemoteLibrary) => {
    const url = remote.url.trim().replace(/\/+$/, '');
    const base = /^https?:\/\//.test(url) ? url : `http://${url}`;
    const response = await fetch(
        `${base}/rest/ping.view?f=json&v=1.16.1&c=TesuPlayer&${credentialFor(remote.username, remote.password)}`,
    );
    const body = (await response.json())['subsonic-response'];
    if (body?.status !== 'ok') throw new Error(body?.error?.message ?? 'Connection failed');
    return { ...remote, url: base };
};

export const connectLibrary = async () => {
    const remote = getRemoteLibrary();
    const info = await window.api.localLibrary.getInfo();
    if (remote) {
        applyServer(
            toServer(
                REMOTE_SERVER_ID,
                remote.url,
                remote.url,
                credentialFor(remote.username, remote.password),
            ),
        );
    } else {
        applyServer(toServer(LOCAL_SERVER_ID, 'Моя музыка', info.url, 'u=local&p=local'));
    }
    return info;
};

export const setRemoteLibrary = async (remote: null | RemoteLibrary) => {
    if (remote) localStorage.setItem(REMOTE_KEY, JSON.stringify(remote));
    else localStorage.removeItem(REMOTE_KEY);
    await connectLibrary();
    queryClient.invalidateQueries();
};

/** Connects the UI to the library on startup and keeps it connected. */
export const useLocalLibraryConnection = () => {
    const [ready, setReady] = useState(false);
    const [error, setError] = useState<null | string>(null);
    const [deviceName, setDeviceName] = useState('');

    useEffect(() => {
        if (!isElectron() || !window.api?.localLibrary) {
            setError('TesuPlayer works as a desktop app');
            return;
        }

        connectLibrary()
            .then((info) => {
                setDeviceName(info.deviceName);
                setReady(true);
            })
            .catch((err) => {
                logger.error('Failed to start local library', { error: String(err) });
                setError(String(err));
            });

        // a rescan (new folder, YouTube download) or a port change refreshes everything
        return window.api.localLibrary.onChange(async () => {
            if (!getRemoteLibrary()) await connectLibrary();
            queryClient.invalidateQueries();
        });
    }, []);

    useDeviceHeartbeat(deviceName, ready);

    return { error, ready };
};

export const useLocalLibraryInfo = () => {
    const [info, setInfo] = useState<LocalLibraryInfo | null>(null);

    useEffect(() => {
        if (!window.api?.localLibrary) return;
        window.api.localLibrary.getInfo().then(setInfo);
        return window.api.localLibrary.onChange(setInfo);
    }, []);

    return [info, setInfo] as const;
};
