import { useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import styles from './streams-panel.module.css';

import { radioQueries } from '/@/renderer/features/radio/api/radio-api';
import { useRadioControls } from '/@/renderer/features/radio/hooks/use-radio-player';
import { useCreateRadioStation } from '/@/renderer/features/radio/mutations/create-radio-station-mutation';
import { useDeleteRadioStation } from '/@/renderer/features/radio/mutations/delete-radio-station-mutation';
import { useCurrentServer } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';
import {
    ExternalSearchResult,
    getExternalStreamSource,
    ResolvedExternalStream,
} from '/@/shared/types/streams';

const YTDLP_STATUS_KEY = ['streams', 'ytdlp-status'];
const TWITCH_LOGIN = /^[a-zA-Z0-9_]{3,25}$/;

const formatDuration = (seconds: null | number) => {
    if (!seconds) return '';
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    const mm = h ? String(m).padStart(2, '0') : String(m);
    return `${h ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`;
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

/** Downloads YouTube audio into the library; tracks the links in progress */
const useAudioDownload = () => {
    const { t } = useTranslation();
    const [pending, setPending] = useState<Set<string>>(new Set());

    const download = async (url: string) => {
        setPending((prev) => new Set(prev).add(url));
        toast.info({ message: t('streams.downloadStarted') });
        try {
            const track = await window.api.streams.downloadAudio(url);
            toast.success({
                message: `${track.artist} - ${track.title}`,
                title: t('streams.downloadDone'),
            });
        } catch (error) {
            toast.error({ message: (error as Error).message, title: t('streams.downloadFailed') });
        } finally {
            setPending((prev) => {
                const next = new Set(prev);
                next.delete(url);
                return next;
            });
        }
    };

    return { download, pending };
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
            onClick={() => toggle(url, name)}
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
                    state: /not currently live|offline|does not exist/i.test(message)
                        ? 'offline'
                        : 'error',
                };
            }
        },
        queryKey: ['streams', 'twitch-channel', login.toLowerCase()],
        staleTime: 60_000,
    });
    const state: ChannelState = isFetching ? 'checking' : (data?.state ?? 'checking');
    const status = {
        checking: t('streams.checking'),
        error: t('streams.channelUnknown'),
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
                    Twitch: {data?.info?.artist || login}
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
                onClick={() => play(url, `Twitch: ${data?.info?.artist || login}`)}
                size="xs"
                variant="filled"
            >
                {t('player.play')}
            </Button>
            <FavoriteButton name={`Twitch: ${data?.info?.artist || login}`} url={url} />
        </div>
    );
};

const VideoRow = ({
    download,
    item,
    pending,
}: {
    download: (url: string) => void;
    item: ExternalSearchResult;
    pending: boolean;
}) => {
    const { t } = useTranslation();
    const { play } = useRadioControls();
    return (
        <div className={styles.result}>
            {item.thumbnail && (
                <img alt="" className={styles.thumb} loading="lazy" src={item.thumbnail} />
            )}
            <div className={styles.info}>
                <Text lineClamp={2} size="sm">
                    {item.title}
                </Text>
                <Text isMuted size="xs">
                    {['YouTube', item.uploader, formatDuration(item.duration)]
                        .filter(Boolean)
                        .join(' - ')}
                </Text>
            </div>
            <Button onClick={() => play(item.url, item.title)} size="xs" variant="filled">
                {t('player.play')}
            </Button>
            <Button
                loading={pending}
                onClick={() => download(item.url)}
                size="xs"
                variant="default"
            >
                {t('streams.download')}
            </Button>
            <FavoriteButton name={item.title} url={item.url} />
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
                                onClick={() => play(station.streamUrl, station.name)}
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

const Search = () => {
    const { t } = useTranslation();
    const { play } = useRadioControls();
    const { download, pending } = useAudioDownload();
    const [query, setQuery] = useState('');
    const [submitted, setSubmitted] = useState('');
    const [searching, setSearching] = useState(false);
    const [results, setResults] = useState<ExternalSearchResult[]>([]);

    const value = query.trim();
    const linkSource = getExternalStreamSource(value);
    const channel = submitted.replace(/^@/, '');

    const handleSearch = async (e: FormEvent) => {
        e.preventDefault();
        if (!value) return;
        if (linkSource) {
            play(value);
            return;
        }
        setSubmitted(value);
        setSearching(true);
        try {
            setResults(await window.api.streams.searchYoutube(value));
        } catch (error) {
            setResults([]);
            toast.error({ message: (error as Error).message, title: 'YouTube' });
        } finally {
            setSearching(false);
        }
    };

    return (
        <Stack gap="xs">
            <form onSubmit={handleSearch}>
                <Group align="flex-end" wrap="nowrap">
                    <TextInput
                        onChange={(e) => setQuery(e.currentTarget.value)}
                        placeholder={t('streams.searchPlaceholder')}
                        style={{ flex: 1 }}
                        value={query}
                    />
                    <Button loading={searching} type="submit" variant="filled">
                        {linkSource ? t('player.play') : t('common.search')}
                    </Button>
                    {linkSource === 'youtube' && (
                        <Button
                            loading={pending.has(value)}
                            onClick={() => download(value)}
                            variant="default"
                        >
                            {t('streams.download')}
                        </Button>
                    )}
                    {linkSource && <FavoriteButton name={value} url={value} />}
                </Group>
            </form>
            <Stack gap={4}>
                {TWITCH_LOGIN.test(channel) && <TwitchChannelCard key={channel} login={channel} />}
                {results.map((item) => (
                    <VideoRow
                        download={download}
                        item={item}
                        key={item.id}
                        pending={pending.has(item.url)}
                    />
                ))}
            </Stack>
        </Stack>
    );
};

export const StreamsPanel = () => (
    <Stack gap="xl" maw="960px" p="md">
        <YtDlpStatusLine />
        <Search />
        <Favorites />
    </Stack>
);
