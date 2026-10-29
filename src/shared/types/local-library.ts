// Built-in local music library (Electron main process)

export interface LocalLibraryInfo {
    /** http://<lan-ip>:<port> addresses other devices can use */
    addresses: string[];
    count: number;
    deviceName: string;
    downloadsDir: string;
    folders: string[];
    remote: RemoteAccessSettings;
    scanning: boolean;
    /** Base URL of the Subsonic-compatible API the UI talks to */
    url: string;
}

export interface RemoteAccessSettings {
    enabled: boolean;
    password: string;
    port: number;
    username: string;
}

/** Another computer running TesuPlayer, used instead of this one's library */
export interface RemoteLibrary {
    password: string;
    url: string;
    username: string;
}

export const LOCAL_SERVER_ID = 'local-library';
export const REMOTE_SERVER_ID = 'remote-library';
