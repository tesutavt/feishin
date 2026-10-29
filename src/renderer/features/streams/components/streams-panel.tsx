import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import styles from './streams-panel.module.css';

import { api } from '/@/renderer/api';
import { radioQueries } from '/@/renderer/features/radio/api/radio-api';
import { useRadioControls, useRadioStore } from '/@/renderer/features/radio/hooks/use-radio-player';
import { useCreateRadioStation } from '/@/renderer/features/radio/mutations/create-radio-station-mutation';
import { useDeleteRadioStation } from '/@/renderer/features/radio/mutations/delete-radio-station-mutation';
import { useAuthStore, useCurrentServer, usePlayerStoreBase } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Spinner } from '/@/shared/components/spinner/spinner';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';
import { Song } from '/@/shared/types/domain-types';
import {
    ExternalSearchResult,
    getExternalStreamSource,
    ResolvedExternalStream,
} from '/@/shared/types/streams';
import { Play } from '/@/shared/types/types';

// YouTube / Twitch pieces of the global search (yt-dlp, desktop only)

const YTDLP_STATUS_KEY = ['streams', 'ytdlp-status'];
export const TWITCH_LOGIN = /^@?[a-zA-Z0-9_]{3,25}$/;

const formatDuration = (seconds: null | number) => {
    if (!seconds) return '';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    const mm = h ? String(m).padStart(2, '0') : String(m);
    return `${h ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`;
};

/** A YouTube link as a search result */
export const youtubeLinkItem = (url: string, title = url): ExternalSearchResult => {
    let id = url;
    try {
        const parsed = new URL(url);
        id =
            parsed.searchParams.get('v') ?? parsed.pathname.split('/').filter(Boolean).pop() ?? url;
    } catch {
        // not a URL
    }
    return { duration: null, id, thumbnail: null, title, uploader: null, url };
};

/**
 * Videos go into the regular queue as songs of the built-in library (seek, next,
 * favourites); live streams play as radio.
 */
export const playYoutube = async (
    items: ExternalSearchResult[],
    index = 0,
    playType: Play = Play.NOW,
) => {
    const target = items[index];
    if (!target) return;
    if (target.isLive) {
        useRadioStore.getState().actions.play(target.url, target.title);
        return;
    }
    const server = useAuthStore.getState().currentServer;
    if (!server) return;
    try {
        const videos = items.filter((item) => !item.isLive);
        const ids = await window.api.streams.registerYoutube(videos);
        const songs = (
            await Promise.all(
                ids.map((id) =>
                    api.controller
                        .getSongDetail({ apiClientProps: { serverId: server.id }, query: { id } })
                        .catch(() => null),
                ),
            )
        ).filter(Boolean) as Song[];
        if (songs.length === 0) throw new Error('YouTube: nothing to play');
        const playSongId = ids[videos.indexOf(target)];
        usePlayerStoreBase.getState().addToQueueByType(songs, playType, playSongId);
    } catch (error) {
        toast.error({ message: (error as Error).message });
    }
};

export const YtDlpStatusLine = () => {
    const { t } = useTranslation();
    const queryClient = useQueryClient();
    const [downloading, setDownloading] = useState(false);
    const { data: status, isLoading } = useQuery({
        queryFn: () => window.api.streams.getYtDlpStatus(),
        queryKey: YTDLP_STATUS_KEY,
    });

    const handleDownload = async () => {
        setDownloading(true);
        try {
            queryClient.setQueryData(YTDLP_STATUS_KEY, await window.api.streams.downloadYtDlp());
        } catch (error) {
            toast.error({ message: (error as Error).message });
        } finally {
            setDownloading(false);
        }
    };

    if (isLoading) return null;

    return (
        <Group justify="space-between">
            <Text isMuted size="sm">
                {status?.path
                    ? t('streams.ytdlpFound', { version: status.version })
                    : t('streams.ytdlpMissing')}
            </Text>
            <Button loading={downloading} onClick={handleDownload} size="xs" variant="default">
                {status?.path ? t('streams.ytdlpUpdate') : t('streams.ytdlpDownload')}
            </Button>
        </Group>
    );
};

/** YouTube -> tagged m4a in the library, with toasts */
export const downloadYoutubeAudio = async (url: string, t: (key: string) => string) => {
    toast.info({ message: t('streams.downloadStarted') });
    try {
        const track = await window.api.streams.downloadAudio(url);
        toast.success({
            message: `${track.artist} - ${track.title}`,
            title: t('streams.downloadDone'),
        });
    } catch (error) {
        toast.error({ message: (error as Error).message, title: t('streams.downloadFailed') });
    }
};

/** Favourites are radio stations: stored on the library computer, synced to every device */
const useFavorites = () => {
    const { t } = useTranslation();
    const server = useCurrentServer();
    const serverId = server?.id ?? '';
    const { data } = useQuery(radioQueries.list({ query: undefined, serverId }));
    const create = useCreateRadioStation({});
    const remove = useDeleteRadioStation({});

    const favorites = (data ?? []).filter((s) => getExternalStreamSource(s.streamUrl));
    const find = (url: string) => favorites.find((s) => s.streamUrl === url);

    const toggle = (url: string, name: string) => {
        const existing = find(url);
        if (existing) {
            remove.mutate({ apiClientProps: { serverId }, query: { id: existing.id } });
            return;
        }
        create.mutate(
            { apiClientProps: { serverId }, body: { homepageUrl: url, name, streamUrl: url } },
            { onSuccess: () => toast.success({ message: t('streams.saved', { name }) }) },
        );
    };

    return { favorites, isFavorite: (url: string) => Boolean(find(url)), toggle };
};

const FavoriteButton = ({ name, url }: { name: string; url: string }) => {
    const { isFavorite, toggle } = useFavorites();
    const on = isFavorite(url);
    return (
        <Button
            onClick={(e) => {
                e.stopPropagation();
                toggle(url, name);
            }}
            size="xs"
            title={on ? 'Убрать из избранного' : 'В избранное (раздел «Радиостанции»)'}
            variant={on ? 'light' : 'default'}
        >
            {on ? '★' : '☆'}
        </Button>
    );
};

type ChannelState = 'checking' | 'error' | 'live' | 'offline';

const TwitchChannelCard = ({ login }: { login: string }) => {
    const { t } = useTranslation();
    const { play } = useRadioControls();
    const url = `https://www.twitch.tv/${login}`;
    const { data, isFetching } = useQuery<{ info?: ResolvedExternalStream; state: ChannelState }>({
        queryFn: async () => {
            try {
                return { info: await window.api.streams.resolve(url), state: 'live' };
            } catch (error) {
                const message = String((error as Error).message);
                return {
                    state: /not currently live|offline/i.test(message) ? 'offline' : 'error',
                };
            }
        },
        queryKey: ['streams', 'twitch-channel', login.toLowerCase()],
        staleTime: 60_000,
    });
    const state: ChannelState = isFetching ? 'checking' : (data?.state ?? 'checking');
    if (state === 'error') return null;
    const name = `Twitch: ${data?.info?.artist || login}`;
    const status = {
        checking: t('streams.checking'),
        error: '',
        live: `● ${t('streams.live')}`,
        offline: t('streams.offline'),
    }[state];

    return (
        <div className={styles.result}>
            {data?.info?.thumbnail && (
                <img alt="" className={styles.thumb} loading="lazy" src={data.info.thumbnail} />
            )}
            <div className={styles.info}>
                <Text fw={600} lineClamp={1} size="sm">
                    {name}
                </Text>
                <Text
                    c={state === 'live' ? 'red' : undefined}
                    isMuted={state !== 'live'}
                    lineClamp={2}
                    size="xs"
                >
                    {status}
                    {data?.info?.title ? ` - ${data.info.title}` : ''}
                </Text>
            </div>
            <Button
                disabled={state === 'offline'}
                onClick={() => play(url, name)}
                size="xs"
                variant="filled"
            >
                {t('player.play')}
            </Button>
            <FavoriteButton name={name} url={url} />
        </div>
    );
};

const Favorites = () => {
    const { t } = useTranslation();
    const { play } = useRadioControls();
    const { favorites, toggle } = useFavorites();

    return (
        <Stack gap="xs">
            <TextTitle fw={600} order={4}>
                {t('streams.favorites')}
            </TextTitle>
            {favorites.length === 0 ? (
                <Text isMuted size="sm">
                    {t('streams.favoritesEmpty')}
                </Text>
            ) : (
                <Group gap="xs">
                    {favorites.map((station) => (
                        <Group gap={0} key={station.id} wrap="nowrap">
                            <Button
                                onClick={() =>
                                    getExternalStreamSource(station.streamUrl) === 'youtube'
                                        ? playYoutube([
                                              youtubeLinkItem(station.streamUrl, station.name),
                                          ])
                                        : play(station.streamUrl, station.name)
                                }
                                size="xs"
                                variant="default"
                            >
                                {station.name}
                            </Button>
                            <Button
                                onClick={() => toggle(station.streamUrl, station.name)}
                                size="xs"
                                title="Убрать из избранного"
                                variant="subtle"
                            >
                                ✕
                            </Button>
                        </Group>
                    ))}
                </Group>
            )}
        </Stack>
    );
};

/** The "YouTube / Twitch" tab of the search page */
export const OnlineSearchResults = ({ query }: { query: string }) => {
    const { t } = useTranslation();
    const { play } = useRadioControls();
    const [pending, setPending] = useState<Set<string>>(new Set());
    const value = query.trim();
    const linkSource = getExternalStreamSource(value);
    const login = !linkSource && TWITCH_LOGIN.test(value) ? value.replace(/^@/, '') : null;

    const youtube = useQuery({
        enabled: Boolean(value) && !linkSource,
        queryFn: () => window.api.streams.searchYoutube(value),
        queryKey: ['streams', 'youtube-search', value],
        retry: false,
        staleTime: 10 * 60_000,
    });

    const download = async (url: string) => {
        setPending((prev) => new Set(prev).add(url));
        await downloadYoutubeAudio(url, t);
        setPending((prev) => {
            const next = new Set(prev);
            next.delete(url);
            return next;
        });
    };

    const results = youtube.data ?? [];

    return (
        <Stack gap="lg" maw="1000px" p="md">
            {linkSource && (
                <Group>
                    <Text style={{ wordBreak: 'break-all' }}>{value}</Text>
                    <Button
                        onClick={() =>
                            linkSource === 'youtube'
                                ? playYoutube([youtubeLinkItem(value)])
                                : play(value)
                        }
                        size="xs"
                        variant="filled"
                    >
                        {t('player.play')}
                    </Button>
                    {linkSource === 'youtube' && (
                        <Button
                            loading={pending.has(value)}
                            onClick={() => download(value)}
                            size="xs"
                            variant="default"
                        >
                            {t('streams.download')}
                        </Button>
                    )}
                    <FavoriteButton name={value} url={value} />
                </Group>
            )}
            {!value && <Favorites />}
            <Stack gap={4}>
                {login && <TwitchChannelCard key={login} login={login} />}
                {youtube.isFetching && results.length === 0 && <Spinner container />}
                {youtube.isError && (
                    <Text isMuted size="sm">
                        YouTube: {String(youtube.error)}
                    </Text>
                )}
                {results.map((item, index) => (
                    <div
                        className={styles.result}
                        key={item.id}
                        onDoubleClick={() => playYoutube(results, index)}
                    >
                        {item.thumbnail && (
                            <img
                                alt=""
                                className={styles.thumb}
                                loading="lazy"
                                src={item.thumbnail}
                            />
                        )}
                        <div className={styles.info}>
                            <Text lineClamp={2} size="sm">
                                {item.title}
                            </Text>
                            <Text
                                c={item.isLive ? 'red' : undefined}
                                isMuted={!item.isLive}
                                size="xs"
                            >
                                {[
                                    item.isLive ? `● ${t('streams.live')}` : 'YouTube',
                                    item.uploader,
                                    formatDuration(item.duration),
                                ]
                                    .filter(Boolean)
                                    .join(' - ')}
                            </Text>
                        </div>
                        <Button
                            onClick={() => playYoutube(results, index)}
                            size="xs"
                            variant="filled"
                        >
                            {t('player.play')}
                        </Button>
                        {!item.isLive && (
                            <>
                                <Button
                                    onClick={() => playYoutube([item], 0, Play.LAST)}
                                    size="xs"
                                    title={t('streams.addToQueue')}
                                    variant="default"
                                >
                                    +
                                </Button>
                                <Button
                                    loading={pending.has(item.url)}
                                    onClick={() => download(item.url)}
                                    size="xs"
                                    variant="default"
                                >
                                    {t('streams.download')}
                                </Button>
                            </>
                        )}
                        <FavoriteButton name={item.title} url={item.url} />
                    </div>
                ))}
            </Stack>
            {value && <Favorites />}
        </Stack>
    );
};
