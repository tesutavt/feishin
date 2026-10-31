import { createHash } from 'crypto';
import { createReadStream, promises as fs } from 'fs';
import http, { IncomingMessage, ServerResponse } from 'http';
import { nanoid } from 'nanoid/non-secure';
import path from 'path';

import { library, LocalAlbum, LocalArtist, LocalSong } from './library';
import { receivePhoneCover, receivePhoneUpload } from './phone-uploads';
import { importSpotify } from './spotify';
import {
    downloadedCopyOf,
    getYoutubeMix,
    getYoutubeSong,
    getYoutubeStreamUrl,
    getYoutubeThumbnail,
    isYoutubeId,
    knownYoutubeSong,
    YoutubeSong,
} from './youtube';

import log from '/@/main/logger';

// Subsonic-compatible API on 127.0.0.1 so the existing UI (built for Subsonic
// servers) works on local files with no external server.

const MIME: Record<string, string> = {
    '.aac': 'audio/aac',
    '.aiff': 'audio/aiff',
    '.alac': 'audio/mp4',
    '.ape': 'audio/x-ape',
    '.flac': 'audio/flac',
    '.m4a': 'audio/mp4',
    '.mp3': 'audio/mpeg',
    '.ogg': 'audio/ogg',
    '.opus': 'audio/ogg',
    '.wav': 'audio/wav',
    '.wma': 'audio/x-ms-wma',
    '.wv': 'audio/x-wavpack',
};

type Params = URLSearchParams;

class ApiError extends Error {
    constructor(
        public code: number,
        message: string,
    ) {
        super(message);
    }
}

const num = (p: Params, key: string, fallback: number) => {
    const value = parseInt(p.get(key) ?? '', 10);
    return Number.isFinite(value) ? value : fallback;
};

const page = <T>(list: T[], p: Params, sizeKey = 'size', offsetKey = 'offset', size = 10) =>
    list.slice(num(p, offsetKey, 0), num(p, offsetKey, 0) + num(p, sizeKey, size));

const shuffle = <T>(list: T[]) => {
    const copy = [...list];
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
};

const byName = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: 'base' });

const matches = (query: string, ...fields: string[]) =>
    fields.some((field) => field.toLowerCase().includes(query));

// ---------------------------------------------------------------- serializers

const u = () => library.user;

const songJson = (s: LocalSong) => {
    const album = library.albums.get(s.albumId);
    const ext = path.extname(s.path).toLowerCase();
    const albumArtist = { id: album?.artistId ?? s.artistId, name: album?.artist ?? s.artist };
    return {
        album: s.album,
        albumArtists: [albumArtist],
        albumId: s.albumId,
        artist: s.artist,
        artistId: s.artistId,
        artists: [{ id: s.artistId, name: s.artist }],
        bitRate: s.bitRate,
        channelCount: s.channels,
        contentType: MIME[ext] ?? 'audio/mpeg',
        coverArt: s.hasPicture ? s.id : s.albumId,
        created: s.created,
        discNumber: s.disc,
        duration: s.duration,
        genre: s.genre || undefined,
        genres: s.genre ? [{ name: s.genre }] : [],
        id: s.id,
        isDir: false,
        isVideo: false,
        parent: s.albumId,
        path: s.path,
        playCount: u().playCounts[s.id] ?? 0,
        played: u().lastPlayed[s.id],
        samplingRate: s.sampleRate,
        size: s.size,
        starred: u().starred[s.id],
        suffix: ext.slice(1),
        title: s.title,
        track: s.track || undefined,
        type: 'music',
        userRating: u().ratings[s.id],
        year: s.year || undefined,
    };
};

const youtubeSongJson = (s: YoutubeSong) => ({
    album: 'YouTube',
    albumArtists: [],
    artist: s.artist,
    artists: [{ id: `yt-artist-${s.artist}`, name: s.artist }],
    contentType: 'audio/mp4',
    coverArt: s.id,
    created: new Date().toISOString(),
    discNumber: 1,
    duration: s.duration,
    id: s.id,
    isDir: false,
    isVideo: false,
    parent: '',
    path: `https://www.youtube.com/watch?v=${s.videoId}`,
    playCount: u().playCounts[s.id] ?? 0,
    size: 0,
    starred: u().starred[s.id],
    suffix: 'm4a',
    title: s.title,
    type: 'music',
    userRating: u().ratings[s.id],
});

const albumJson = (a: LocalAlbum, withSongs = false) => ({
    album: a.name,
    artist: a.artist,
    artistId: a.artistId,
    artists: [{ id: a.artistId, name: a.artist }],
    coverArt: a.id,
    created: a.created,
    duration: a.duration,
    genre: a.genre || undefined,
    genres: a.genre ? [{ name: a.genre }] : [],
    id: a.id,
    isDir: true,
    isVideo: false,
    name: a.name,
    parent: a.artistId,
    playCount: a.songs.reduce((sum, s) => sum + (u().playCounts[s.id] ?? 0), 0),
    played: a.songs
        .map((s) => u().lastPlayed[s.id])
        .filter(Boolean)
        .sort()
        .pop(),
    songCount: a.songs.length,
    starred: u().starred[a.id],
    title: a.name,
    userRating: u().ratings[a.id],
    year: a.year || undefined,
    ...(withSongs ? { song: a.songs.map(songJson) } : {}),
});

const artistJson = (a: LocalArtist, withAlbums = false) => ({
    albumCount: a.albums.length,
    coverArt: a.id,
    id: a.id,
    name: a.name,
    starred: u().starred[a.id],
    userRating: u().ratings[a.id],
    ...(withAlbums ? { album: a.albums.map((al) => albumJson(al)) } : {}),
});

/** A library file or a YouTube song, as Subsonic JSON */
const anySong = (
    id: string,
): null | {
    cover?: string;
    duration: number;
    json: ReturnType<typeof songJson> | ReturnType<typeof youtubeSongJson>;
} => {
    const local = library.songs.get(id);
    if (local)
        return {
            cover: local.hasPicture ? local.id : local.albumId,
            duration: local.duration,
            json: songJson(local),
        };
    const yt = isYoutubeId(id) ? knownYoutubeSong(id) : undefined;
    return yt ? { cover: yt.id, duration: yt.duration, json: youtubeSongJson(yt) } : null;
};

const playlistJson = (id: string, withSongs = false) => {
    const pl = u().playlists.find((p) => p.id === id);
    if (!pl) throw new ApiError(70, 'Playlist not found');
    const songs = pl.songIds.flatMap((sid) => anySong(sid) ?? []);
    return {
        changed: pl.changed,
        comment: '',
        coverArt: songs[0]?.cover,
        created: pl.created,
        duration: songs.reduce((sum, s) => sum + s.duration, 0),
        id: pl.id,
        name: pl.name,
        owner: 'local',
        public: false,
        songCount: songs.length,
        ...(withSongs ? { entry: songs.map((s) => s.json) } : {}),
    };
};

const artistIndex = () => {
    const groups = new Map<string, LocalArtist[]>();
    for (const artist of [...library.artists.values()].sort((a, b) => byName(a.name, b.name))) {
        const letter = artist.name.charAt(0).toUpperCase() || '#';
        const key = /\p{L}/u.test(letter) ? letter : '#';
        groups.set(key, [...(groups.get(key) ?? []), artist]);
    }
    return [...groups.entries()].map(([name, artists]) => ({
        artist: artists.map((a) => artistJson(a)),
        name,
    }));
};

const allSongs = () => [...library.songs.values()];
const allAlbums = () => [...library.albums.values()];

const filterYearGenre = (songs: LocalSong[], p: Params) => {
    const genre = p.get('genre');
    const from = num(p, 'fromYear', 0);
    const to = num(p, 'toYear', 9999);
    return songs.filter(
        (s) =>
            (!genre || s.genre === genre) &&
            (!p.has('fromYear') || (s.year >= from && s.year <= to)),
    );
};

/** A YouTube song that is downloaded already: its favourite / rating belong to the file */
const fileOf = (id: string) =>
    (isYoutubeId(id) && downloadedCopyOf(id.slice('yt-'.length))?.id) || id;

const starredLists = () => ({
    album: allAlbums()
        .filter((a) => u().starred[a.id])
        .map((a) => albumJson(a)),
    artist: [...library.artists.values()]
        .filter((a) => u().starred[a.id])
        .map((a) => artistJson(a)),
    // favourite files and favourite YouTube songs, newest first
    song: Object.entries(u().starred)
        .sort(([, a], [, b]) => b.localeCompare(a))
        .flatMap(([id]) => anySong(id)?.json ?? []),
});

const albumList = (p: Params) => {
    let list = allAlbums();
    const type = p.get('type') ?? 'alphabeticalByName';
    const played = (a: LocalAlbum) =>
        a.songs
            .map((s) => u().lastPlayed[s.id] ?? '')
            .sort()
            .pop() ?? '';
    const plays = (a: LocalAlbum) =>
        a.songs.reduce((sum, s) => sum + (u().playCounts[s.id] ?? 0), 0);
    switch (type) {
        case 'alphabeticalByArtist':
            list.sort((a, b) => byName(a.artist, b.artist) || byName(a.name, b.name));
            break;
        case 'byGenre':
            list = list.filter((a) => a.songs.some((s) => s.genre === p.get('genre')));
            break;
        case 'byYear': {
            const from = num(p, 'fromYear', 0);
            const to = num(p, 'toYear', 9999);
            const lo = Math.min(from, to);
            const hi = Math.max(from, to);
            list = list
                .filter((a) => a.year >= lo && a.year <= hi)
                .sort((a, b) => (from <= to ? a.year - b.year : b.year - a.year));
            break;
        }
        case 'frequent':
            list = list.filter((a) => plays(a) > 0).sort((a, b) => plays(b) - plays(a));
            break;
        case 'highest':
            list = list
                .filter((a) => u().ratings[a.id])
                .sort((a, b) => (u().ratings[b.id] ?? 0) - (u().ratings[a.id] ?? 0));
            break;
        case 'newest':
            list.sort((a, b) => b.created.localeCompare(a.created));
            break;
        case 'random':
            list = shuffle(list);
            break;
        case 'recent':
            list = list.filter((a) => played(a)).sort((a, b) => played(b).localeCompare(played(a)));
            break;
        case 'starred':
            list = list.filter((a) => u().starred[a.id]);
            break;
        default:
            list.sort((a, b) => byName(a.name, b.name));
    }
    return page(list, p).map((a) => albumJson(a));
};

const ids = (p: Params, ...keys: string[]) => keys.flatMap((k) => p.getAll(k)).filter(Boolean);

// ---------------------------------------------------------------- devices ("where is it playing")

interface Device {
    /** radio / Twitch: no library song, just what is on air */
    artist?: string;
    client: string;
    commands: string[];
    id: string;
    name: string;
    position: number;
    songId?: string;
    state: 'paused' | 'playing' | 'stopped';
    title?: string;
    updated: number;
}

const DEVICE_TTL = 30_000;
const devices = new Map<string, Device>();

const liveDevices = () => {
    const now = Date.now();
    for (const [id, device] of devices) if (now - device.updated > DEVICE_TTL) devices.delete(id);
    return [...devices.values()];
};

const deviceJson = (d: Device) => {
    const song =
        (d.songId ? anySong(d.songId)?.json : undefined) ??
        (d.title
            ? { artist: d.artist ?? '', duration: 0, id: '', isLive: true, title: d.title }
            : undefined);
    return {
        client: d.client,
        id: d.id,
        name: d.name,
        position: d.position + (d.state === 'playing' ? (Date.now() - d.updated) / 1000 : 0),
        song,
        state: d.state,
        updated: new Date(d.updated).toISOString(),
    };
};

// ---------------------------------------------------------------- methods

const methods: Record<string, (p: Params) => object | Promise<object>> = {
    createInternetRadioStation: (p) => {
        u().radio.push({
            homePageUrl: p.get('homepageUrl') ?? undefined,
            id: nanoid(),
            name: p.get('name') ?? '',
            streamUrl: p.get('streamUrl') ?? '',
        });
        library.save();
        return {};
    },
    createPlaylist: (p) => {
        const existing = p.get('playlistId');
        const now = new Date().toISOString();
        let pl = existing ? u().playlists.find((x) => x.id === existing) : undefined;
        if (!pl) {
            pl = {
                changed: now,
                created: now,
                id: nanoid(),
                name: p.get('name') ?? 'Плейлист',
                songIds: [],
            };
            u().playlists.push(pl);
        }
        if (p.has('name')) pl.name = p.get('name')!;
        const songIds = ids(p, 'songId');
        if (songIds.length || existing) pl.songIds = songIds;
        pl.changed = now;
        library.save();
        return { playlist: playlistJson(pl.id, true) };
    },
    deleteInternetRadioStation: (p) => {
        u().radio = u().radio.filter((r) => r.id !== p.get('id'));
        library.save();
        return {};
    },
    deletePlaylist: (p) => {
        u().playlists = u().playlists.filter((pl) => pl.id !== p.get('id'));
        library.save();
        return {};
    },
    getAlbum: (p) => {
        const album = library.albums.get(p.get('id') ?? '');
        if (!album) throw new ApiError(70, 'Album not found');
        return { album: albumJson(album, true) };
    },
    getAlbumInfo2: () => ({ albumInfo: {} }),
    getAlbumList: (p) => ({ albumList: { album: albumList(p) } }),
    getAlbumList2: (p) => ({ albumList2: { album: albumList(p) } }),
    getArtist: (p) => {
        const artist = library.artists.get(p.get('id') ?? '');
        if (!artist) throw new ApiError(70, 'Artist not found');
        return { artist: artistJson(artist, true) };
    },
    getArtistInfo: () => ({ artistInfo: {} }),
    getArtistInfo2: () => ({ artistInfo2: {} }),
    getArtists: () => ({ artists: { ignoredArticles: '', index: artistIndex() } }),
    getGenres: () => {
        const genres = new Map<string, { albums: Set<string>; songs: number }>();
        for (const s of allSongs()) {
            if (!s.genre) continue;
            const g = genres.get(s.genre) ?? { albums: new Set(), songs: 0 };
            g.songs += 1;
            g.albums.add(s.albumId);
            genres.set(s.genre, g);
        }
        return {
            genres: {
                genre: [...genres.entries()].map(([value, g]) => ({
                    albumCount: g.albums.size,
                    songCount: g.songs,
                    value,
                })),
            },
        };
    },
    getIndexes: () => ({
        indexes: { ignoredArticles: '', index: artistIndex(), lastModified: Date.now() },
    }),
    getInternetRadioStations: () => ({
        internetRadioStations: {
            internetRadioStation: u().radio.map((r) => ({ ...r, homepageUrl: r.homePageUrl })),
        },
    }),
    getLicense: () => ({ license: { valid: true } }),
    getLyrics: (p) => {
        const artist = (p.get('artist') ?? '').toLowerCase();
        const title = (p.get('title') ?? '').toLowerCase();
        const song = allSongs().find(
            (s) =>
                s.lyrics &&
                s.title.toLowerCase() === title &&
                (!artist || s.artist.toLowerCase() === artist),
        );
        return {
            lyrics: song ? { artist: song.artist, title: song.title, value: song.lyrics } : {},
        };
    },
    getMusicDirectory: (p) => {
        const id = p.get('id') ?? '';
        const artist = library.artists.get(id);
        if (artist) {
            return {
                directory: {
                    child: artist.albums.map((a) => ({
                        ...albumJson(a),
                        isDir: true,
                        parent: artist.id,
                        title: a.name,
                    })),
                    id,
                    name: artist.name,
                },
            };
        }
        const album = library.albums.get(id);
        if (album)
            return {
                directory: {
                    child: album.songs.map(songJson),
                    id,
                    name: album.name,
                    parent: album.artistId,
                },
            };
        throw new ApiError(70, 'Directory not found');
    },
    getMusicFolders: () => ({ musicFolders: { musicFolder: [{ id: '0', name: 'Моя музыка' }] } }),
    getNowPlaying: () => ({
        nowPlaying: {
            entry: liveDevices().flatMap((d) => {
                const song = d.songId ? anySong(d.songId)?.json : undefined;
                if (!song || d.state !== 'playing') return [];
                return [
                    {
                        ...song,
                        minutesAgo: 0,
                        playerId: d.id,
                        playerName: d.name,
                        username: u().remote.username,
                    },
                ];
            }),
        },
    }),
    getPlaylist: (p) => ({ playlist: playlistJson(p.get('id') ?? '', true) }),
    getPlaylists: () => ({
        playlists: { playlist: u().playlists.map((pl) => playlistJson(pl.id)) },
    }),
    getPlayQueue: () => {
        const queue = u().playQueue;
        if (!queue) return {};
        const entry = queue.songIds.flatMap((id) => anySong(id)?.json ?? []);
        return {
            playQueue: {
                changed: queue.changed,
                changedBy: queue.changedBy,
                current: queue.current,
                entry,
                position: queue.position,
                username: u().remote.username,
            },
        };
    },
    getRandomSongs: (p) => ({
        randomSongs: {
            song: shuffle(filterYearGenre(allSongs(), p))
                .slice(0, num(p, 'size', 10))
                .map(songJson),
        },
    }),
    getScanStatus: () => ({
        scanStatus: { count: library.songs.size, scanning: library.scanning },
    }),
    getSimilarSongs: (p) => methods.getSimilarSongs2(p),
    getSimilarSongs2: async (p) => {
        const requested = p.get('id') ?? '';
        if (isYoutubeId(requested)) {
            const song = (await getYoutubeMix(requested, num(p, 'count', 25))).map(youtubeSongJson);
            return { similarSongs: { song }, similarSongs2: { song } };
        }
        const base =
            library.songs.get(p.get('id') ?? '') ??
            library.artists.get(p.get('id') ?? '')?.albums[0]?.songs[0];
        const pool = allSongs().filter(
            (s) =>
                s.id !== base?.id &&
                (!base?.genre || s.genre === base.genre || s.artistId === base.artistId),
        );
        const song = shuffle(pool.length ? pool : allSongs())
            .slice(0, num(p, 'count', 50))
            .map(songJson);
        return { similarSongs: { song }, similarSongs2: { song } };
    },
    getSong: async (p) => {
        const id = p.get('id') ?? '';
        if (isYoutubeId(id)) return { song: youtubeSongJson(await getYoutubeSong(id)) };
        const song = library.songs.get(id);
        if (!song) throw new ApiError(70, 'Song not found');
        return { song: songJson(song) };
    },
    getSongsByGenre: (p) => ({
        songsByGenre: {
            song: page(
                allSongs().filter((s) => s.genre === p.get('genre')),
                p,
                'count',
                'offset',
                10,
            ).map(songJson),
        },
    }),
    getStarred: () => ({ starred: starredLists() }),
    getStarred2: () => ({ starred2: starredLists() }),
    getTopSongs: (p) => {
        const name = (p.get('artist') ?? '').toLowerCase();
        const song = allSongs()
            .filter((s) => s.artist.toLowerCase() === name)
            .sort((a, b) => (u().playCounts[b.id] ?? 0) - (u().playCounts[a.id] ?? 0))
            .slice(0, num(p, 'count', 50))
            .map(songJson);
        return { topSongs: { song } };
    },
    getUser: () => ({
        user: {
            adminRole: true,
            commentRole: true,
            coverArtRole: true,
            downloadRole: true,
            email: '',
            folder: [0],
            jukeboxRole: false,
            playlistRole: true,
            podcastRole: false,
            scrobblingEnabled: true,
            settingsRole: true,
            shareRole: false,
            streamRole: true,
            uploadRole: false,
            username: 'local',
        },
    }),
    ping: () => ({}),
    savePlayQueue: (p) => {
        u().playQueue = {
            changed: new Date().toISOString(),
            changedBy: p.get('c') ?? 'TesuPlayer',
            current: p.get('current') ?? undefined,
            position: num(p, 'position', 0),
            songIds: ids(p, 'id'),
        };
        library.save();
        return {};
    },
    scrobble: (p) => {
        if (p.get('submission') !== 'false') {
            const now = new Date().toISOString();
            for (const id of ids(p, 'id')) {
                u().playCounts[id] = (u().playCounts[id] ?? 0) + 1;
                u().lastPlayed[id] = now;
            }
            library.save();
        }
        return {};
    },
    search2: (p) => ({ searchResult2: search(p) }),
    search3: (p) => ({ searchResult3: search(p) }),
    setRating: (p) => {
        const id = fileOf(p.get('id') ?? '');
        const rating = num(p, 'rating', 0);
        if (rating > 0) u().ratings[id] = rating;
        else delete u().ratings[id];
        library.save();
        return {};
    },
    star: (p) => {
        const now = new Date().toISOString();
        for (const id of ids(p, 'id', 'albumId', 'artistId')) u().starred[fileOf(id)] = now;
        library.save();
        return {};
    },
    startScan: () => {
        library.scan();
        return { scanStatus: { count: library.songs.size, scanning: true } };
    },
    /** TesuPlayer devices: report what plays here, get the other devices and pending commands */
    tesuCommand: (p) => {
        const device = devices.get(p.get('deviceId') ?? '');
        const command = p.get('command') ?? '';
        if (!device) throw new ApiError(70, 'Device is offline');
        if (!['next', 'pause', 'play', 'previous', 'stop'].includes(command)) {
            throw new ApiError(10, 'Unknown command');
        }
        device.commands.push(command);
        return {};
    },
    tesuDevices: () => ({ devices: liveDevices().map(deviceJson) }),
    tesuHeartbeat: (p) => {
        const id = p.get('deviceId');
        if (!id) throw new ApiError(10, 'deviceId is required');
        const state = p.get('state');
        const device: Device = {
            artist: p.get('artist') || undefined,
            client: p.get('c') ?? 'TesuPlayer',
            commands: devices.get(id)?.commands ?? [],
            id,
            name: p.get('deviceName') || p.get('c') || 'Device',
            position: Number(p.get('position')) || 0,
            songId: p.get('id') || undefined,
            state: state === 'playing' || state === 'paused' ? state : 'stopped',
            title: p.get('title') || undefined,
            updated: Date.now(),
        };
        devices.set(id, device);
        return {
            commands: device.commands.splice(0),
            devices: liveDevices()
                .filter((d) => d.id !== id)
                .map(deviceJson),
        };
    },
    /** Spotify playlist / album link or CSV text (POST): becomes a playlist of this library */
    tesuImportSpotify: async (p) => ({
        import: await importSpotify({
            csv: p.get('csv') ?? undefined,
            name: p.get('name') ?? undefined,
            url: p.get('url') ?? undefined,
        }),
    }),
    unstar: (p) => {
        for (const id of ids(p, 'id', 'albumId', 'artistId')) {
            delete u().starred[id];
            delete u().starred[fileOf(id)];
        }
        library.save();
        return {};
    },
    updateInternetRadioStation: (p) => {
        const station = u().radio.find((r) => r.id === p.get('id'));
        if (station) {
            station.name = p.get('name') ?? station.name;
            station.streamUrl = p.get('streamUrl') ?? station.streamUrl;
            station.homePageUrl = p.get('homepageUrl') ?? station.homePageUrl;
            library.save();
        }
        return {};
    },
    updatePlaylist: (p) => {
        const pl = u().playlists.find((x) => x.id === p.get('playlistId'));
        if (!pl) throw new ApiError(70, 'Playlist not found');
        if (p.has('name')) pl.name = p.get('name')!;
        const remove = new Set(p.getAll('songIndexToRemove').map((i) => parseInt(i, 10)));
        pl.songIds = pl.songIds.filter((_, i) => !remove.has(i)).concat(ids(p, 'songIdToAdd'));
        pl.changed = new Date().toISOString();
        library.save();
        return {};
    },
};

function search(p: Params) {
    const query = (p.get('query') ?? '')
        .replace(/^"+|"+$/g, '')
        .trim()
        .toLowerCase();
    const artists = [...library.artists.values()]
        .filter((a) => !query || matches(query, a.name))
        .sort((a, b) => byName(a.name, b.name));
    const albums = allAlbums()
        .filter((a) => !query || matches(query, a.name, a.artist))
        .sort((a, b) => byName(a.name, b.name));
    const songs = allSongs()
        .filter((s) => !query || matches(query, s.title, s.artist, s.album))
        .sort(
            (a, b) =>
                byName(a.artist, b.artist) ||
                byName(a.album, b.album) ||
                a.disc - b.disc ||
                a.track - b.track,
        );
    return {
        album: page(albums, p, 'albumCount', 'albumOffset', 20).map((a) => albumJson(a)),
        artist: page(artists, p, 'artistCount', 'artistOffset', 20).map((a) => artistJson(a)),
        song: page(songs, p, 'songCount', 'songOffset', 20).map(songJson),
    };
}

// ---------------------------------------------------------------- http

const CORS = {
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Origin': '*',
};

const sendJson = (res: ServerResponse, body: object) => {
    res.writeHead(200, { ...CORS, 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(body));
};

const wrap = (body: object, ok = true) => ({
    'subsonic-response': {
        openSubsonic: false,
        status: ok ? 'ok' : 'failed',
        type: 'tesuplayer',
        version: '1.16.1',
        ...body,
    },
});

const streamFile = async (req: IncomingMessage, res: ServerResponse, filePath: string) => {
    const { size } = await fs.stat(filePath);
    const type = MIME[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream';
    const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range ?? '');
    if (range) {
        const start = range[1] ? parseInt(range[1], 10) : 0;
        const end = range[2] ? Math.min(parseInt(range[2], 10), size - 1) : size - 1;
        res.writeHead(206, {
            ...CORS,
            'Accept-Ranges': 'bytes',
            'Content-Length': end - start + 1,
            'Content-Range': `bytes ${start}-${end}/${size}`,
            'Content-Type': type,
        });
        createReadStream(filePath, { end, start }).pipe(res);
    } else {
        res.writeHead(200, {
            ...CORS,
            'Accept-Ranges': 'bytes',
            'Content-Length': size,
            'Content-Type': type,
        });
        createReadStream(filePath).pipe(res);
    }
};

const readBody = (req: IncomingMessage) =>
    new Promise<string>((resolve) => {
        let data = '';
        req.on('data', (chunk) => (data += chunk));
        req.on('end', () => resolve(data));
    });

/** A music file sent from the phone, with the tags the phone knows (see ./phone-uploads) */
const receiveUpload = (req: IncomingMessage, p: Params) =>
    receivePhoneUpload(req, path.basename(p.get('name') ?? ''), {
        album: p.get('album') || undefined,
        artist: p.get('artist') ?? '',
        genre: p.get('genre') || undefined,
        pcId: p.get('pcId') || undefined,
        source: p.get('source') || undefined,
        title: p.get('title') ?? '',
        track: Number(p.get('track')) || undefined,
        year: Number(p.get('year')) || undefined,
    });

const md5 = (value: string) => createHash('md5').update(value).digest('hex');

const isLoopback = (req: IncomingMessage) =>
    ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress ?? '');

/** This computer needs no password; other devices use Subsonic auth (u + t/s or p) */
const authorized = (req: IncomingMessage, p: Params) => {
    if (isLoopback(req)) return true;
    const { password, username } = u().remote;
    if ((p.get('u') ?? '').toLowerCase() !== username.toLowerCase()) return false;
    const plain = p.get('p');
    if (plain) {
        const value = plain.startsWith('enc:')
            ? Buffer.from(plain.slice(4), 'hex').toString('utf8')
            : plain;
        return value === password;
    }
    const salt = p.get('s');
    return Boolean(salt) && p.get('t')?.toLowerCase() === md5(password + salt);
};

const handle = async (req: IncomingMessage, res: ServerResponse) => {
    if (req.method === 'OPTIONS') {
        res.writeHead(204, CORS);
        res.end();
        return;
    }

    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    const params = url.searchParams;
    if (req.method === 'POST' && (req.headers['content-type'] ?? '').includes('urlencoded')) {
        new URLSearchParams(await readBody(req)).forEach((value, key) => params.append(key, value));
    }

    const method = url.pathname.replace(/^\/rest\//, '').replace(/\.view$/, '');

    try {
        if (!authorized(req, params)) {
            if (method === 'stream' || method === 'download' || method === 'getCoverArt') {
                res.writeHead(401, CORS);
                res.end();
                return;
            }
            throw new ApiError(40, 'Wrong username or password');
        }
        const requestedId = params.get('id') ?? '';
        if (isYoutubeId(requestedId) && ['download', 'getCoverArt', 'stream'].includes(method)) {
            // the player follows the redirect: seeking works, Chromium resolves it over DoH
            const location =
                method === 'getCoverArt'
                    ? getYoutubeThumbnail(requestedId)
                    : await getYoutubeStreamUrl(requestedId);
            res.writeHead(302, { ...CORS, 'Cache-Control': 'no-store', Location: location });
            res.end();
            return;
        }
        if (method === 'stream' || method === 'download') {
            const song = library.songs.get(params.get('id') ?? '');
            if (!song) throw new ApiError(70, 'Song not found');
            await streamFile(req, res, song.path);
            return;
        }
        if (method === 'getCoverArt' || method === 'getAvatar') {
            const cover = await library.cover(params.get('id') ?? '');
            if (!cover) {
                res.writeHead(404, CORS);
                res.end();
                return;
            }
            res.writeHead(200, {
                ...CORS,
                'Cache-Control': 'max-age=86400',
                'Content-Type': cover.mimeType,
            });
            res.end(cover.data);
            return;
        }
        if (method === 'tesuUpload' && req.method === 'POST') {
            sendJson(res, wrap({ upload: await receiveUpload(req, params) }));
            return;
        }
        if (method === 'tesuUploadCover' && req.method === 'POST') {
            sendJson(
                res,
                wrap(
                    await receivePhoneCover(
                        req,
                        params.get('file') ?? '',
                        params.get('mime') ?? 'image/jpeg',
                    ),
                ),
            );
            return;
        }
        const fn = methods[method];
        if (!fn) throw new ApiError(0, `Not supported: ${method}`);
        sendJson(res, wrap(await fn(params)));
    } catch (error) {
        const code = error instanceof ApiError ? error.code : 0;
        if (!(error instanceof ApiError))
            log.error('Local library request failed', { error: String(error), method });
        sendJson(res, wrap({ error: { code, message: (error as Error).message } }, false));
    }
};

const listen = (server: http.Server, port: number, host: string) =>
    new Promise<number>((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, host, () => {
            server.removeListener('error', reject);
            resolve((server.address() as { port: number }).port);
        });
    });

let current: http.Server | null = null;

/** (Re)starts the API: loopback only, or all interfaces when remote access is on */
export const startLocalServer = async () => {
    if (current) {
        const closing = current;
        current = null;
        closing.closeAllConnections();
        await new Promise((resolve) => closing.close(resolve));
    }
    const { enabled, port } = u().remote;
    const host = enabled ? '0.0.0.0' : '127.0.0.1';
    const server = http.createServer((req, res) => {
        handle(req, res).catch((error) => {
            log.error('Local library server error', error);
            if (!res.headersSent) res.writeHead(500, CORS);
            res.end();
        });
    });
    current = server;
    try {
        return await listen(server, port, host);
    } catch (error) {
        log.warn('Library port is busy, using a random one', { error: String(error), port });
        return listen(server, 0, host);
    }
};
