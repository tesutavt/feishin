import { useEffect } from 'react';
import { createWithEqualityFn } from 'zustand/traditional';

import { useAuthStore, usePlayerStoreBase } from '/@/renderer/store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { logger } from '/@/renderer/utils/logger';
import { PlayerStatus } from '/@/shared/types/types';

// "Where is it playing": every TesuPlayer reports its player to the library
// host (tesuHeartbeat) and picks up commands sent from other devices.

export type DeviceCommand = 'next' | 'pause' | 'play' | 'previous' | 'stop';

export interface RemoteDevice {
    client: string;
    id: string;
    name: string;
    position: number;
    song?: {
        album?: string;
        artist?: string;
        coverArt?: string;
        duration?: number;
        id: string;
        title: string;
    };
    state: 'paused' | 'playing' | 'stopped';
}

const HEARTBEAT_MS = 5000;

const deviceId = () => {
    let id = localStorage.getItem('tesuplayer-device-id');
    if (!id) {
        id = crypto.randomUUID();
        localStorage.setItem('tesuplayer-device-id', id);
    }
    return id;
};

export const useDevicesStore = createWithEqualityFn<{
    devices: RemoteDevice[];
    thisDevice: string;
}>(() => ({
    devices: [],
    thisDevice: '',
}));

/** Calls a method on the library host with the current credentials */
export const tesuCall = async <T>(method: string, params: Record<string, string | undefined>) => {
    const server = useAuthStore.getState().currentServer;
    if (!server) throw new Error('No library');
    const query = new URLSearchParams({ c: 'TesuPlayer', f: 'json', v: '1.16.1' });
    for (const [key, value] of Object.entries(params))
        if (value !== undefined) query.set(key, value);
    const response = await fetch(`${server.url}/rest/${method}.view?${query}&${server.credential}`);
    const body = (await response.json())['subsonic-response'];
    if (body?.status !== 'ok') throw new Error(body?.error?.message ?? 'Request failed');
    return body as T;
};

const runCommand = (command: DeviceCommand) => {
    const player = usePlayerStoreBase.getState();
    switch (command) {
        case 'next':
            player.mediaNext(false);
            break;
        case 'pause':
            player.mediaPause();
            break;
        case 'play':
            player.mediaPlay();
            break;
        case 'previous':
            player.mediaPrevious(false);
            break;
        case 'stop':
            player.mediaStop();
            break;
    }
};

export const sendDeviceCommand = (targetId: string, command: DeviceCommand) =>
    tesuCall('tesuCommand', { command, deviceId: targetId });

export const useDeviceHeartbeat = (deviceName: string, enabled: boolean) => {
    useEffect(() => {
        if (!enabled) return;
        const id = deviceId();
        useDevicesStore.setState({ thisDevice: id });

        const beat = async () => {
            const player = usePlayerStoreBase.getState();
            const song = player.getCurrentSong();
            const status = player.player.status;
            try {
                const result = await tesuCall<{
                    commands: DeviceCommand[];
                    devices: RemoteDevice[];
                }>('tesuHeartbeat', {
                    deviceId: id,
                    deviceName,
                    id: song?.id,
                    position: String(Math.round(useTimestampStoreBase.getState().timestamp)),
                    state: !song
                        ? 'stopped'
                        : status === PlayerStatus.PLAYING
                          ? 'playing'
                          : 'paused',
                });
                result.commands.forEach(runCommand);
                useDevicesStore.setState({ devices: result.devices });
            } catch (error) {
                logger.debug('Device heartbeat failed', { error: String(error) });
            }
        };

        beat();
        const timer = setInterval(beat, HEARTBEAT_MS);
        return () => clearInterval(timer);
    }, [deviceName, enabled]);
};
