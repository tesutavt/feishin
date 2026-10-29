import { useQuery } from '@tanstack/react-query';
import isElectron from 'is-electron';
import { useTranslation } from 'react-i18next';

import styles from './search-online-section.module.css';

import { useRadioControls } from '/@/renderer/features/radio/hooks/use-radio-player';
import { CollapsibleCommandGroup } from '/@/renderer/features/search/components/collapsible-command-group';
import { CommandItemSelectable } from '/@/renderer/features/search/components/command-item-selectable';
import { downloadYoutubeAudio } from '/@/renderer/features/streams/components/streams-panel';
import { Box } from '/@/shared/components/box/box';
import { Button } from '/@/shared/components/button/button';
import { Spinner } from '/@/shared/components/spinner/spinner';
import { Text } from '/@/shared/components/text/text';
import { useDebouncedValue } from '/@/shared/hooks/use-debounced-value';

// YouTube + Twitch next to the library results in the main search (yt-dlp, desktop only)

const TWITCH_LOGIN = /^@?[a-zA-Z0-9_]{3,25}$/;

interface SearchOnlineSectionProps {
    expanded: boolean;
    isHome: boolean;
    onSelectResult: () => void;
    onToggle: () => void;
    query: string;
}

const Thumb = ({ src }: { src?: null | string }) =>
    src ? <img alt="" className={styles.thumb} loading="lazy" src={src} /> : null;

export function SearchOnlineSection({
    expanded,
    isHome,
    onSelectResult,
    onToggle,
    query,
}: SearchOnlineSectionProps) {
    const { t } = useTranslation();
    const { play } = useRadioControls();
    // yt-dlp takes a few seconds per search: wait until typing stops
    const [debouncedValue] = useDebouncedValue(query.trim(), 900);
    const debounced = debouncedValue ?? '';
    const enabled = isElectron() && isHome && debounced.length >= 2 && query.trim() !== '';
    const login = TWITCH_LOGIN.test(debounced) ? debounced.replace(/^@/, '') : null;

    const youtube = useQuery({
        enabled,
        queryFn: () => window.api.streams.searchYoutube(debounced),
        queryKey: ['streams', 'youtube-search', debounced],
        retry: false,
        staleTime: 10 * 60_000,
    });

    const twitch = useQuery({
        enabled: enabled && Boolean(login),
        queryFn: () =>
            window.api.streams.resolve(`https://www.twitch.tv/${login}`).catch(() => null),
        queryKey: ['streams', 'twitch-channel-live', login?.toLowerCase()],
        staleTime: 60_000,
    });

    if (!isHome || !isElectron()) return null;

    const loading = youtube.isFetching || twitch.isFetching;
    const results = youtube.data ?? [];

    return (
        <CollapsibleCommandGroup
            expanded={expanded}
            heading="YouTube / Twitch"
            onToggle={onToggle}
            subtitle={
                youtube.isError ? (
                    <Text isMuted size="xs">
                        {String(youtube.error).slice(0, 80)}
                    </Text>
                ) : undefined
            }
        >
            {loading && results.length === 0 && (
                <Box p="md">
                    <Spinner container />
                </Box>
            )}
            {twitch.data && login && (
                <CommandItemSelectable
                    onSelect={() => {
                        play(
                            `https://www.twitch.tv/${login}`,
                            `Twitch: ${twitch.data?.artist || login}`,
                        );
                        onSelectResult();
                    }}
                    value={`search-twitch-${login}`}
                >
                    {() => (
                        <div className={styles.item}>
                            <Thumb src={twitch.data?.thumbnail} />
                            <div className={styles.info}>
                                <Text fw={600} overflow="hidden" size="sm">
                                    Twitch: {twitch.data?.artist || login}
                                </Text>
                                <Text c="red" overflow="hidden" size="xs">
                                    ● {t('streams.live')} - {twitch.data?.title}
                                </Text>
                            </div>
                        </div>
                    )}
                </CommandItemSelectable>
            )}
            {results.map((item) => (
                <CommandItemSelectable
                    key={`search-youtube-${item.id}`}
                    onSelect={() => {
                        play(item.url, item.title);
                        onSelectResult();
                    }}
                    value={`search-youtube-${item.id}`}
                >
                    {() => (
                        <div className={styles.item}>
                            <Thumb src={item.thumbnail} />
                            <div className={styles.info}>
                                <Text overflow="hidden" size="sm">
                                    {item.title}
                                </Text>
                                <Text isMuted overflow="hidden" size="xs">
                                    YouTube{item.uploader ? ` - ${item.uploader}` : ''}
                                </Text>
                            </div>
                            <Button
                                onClick={(e) => {
                                    e.stopPropagation();
                                    downloadYoutubeAudio(item.url, t);
                                }}
                                size="compact-xs"
                                variant="default"
                            >
                                {t('streams.download')}
                            </Button>
                        </div>
                    )}
                </CommandItemSelectable>
            ))}
        </CollapsibleCommandGroup>
    );
}
