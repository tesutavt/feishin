import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { queryKeys } from '/@/renderer/api/query-keys';
import {
    getRemoteLibrary,
    setRemoteLibrary,
    testRemoteLibrary,
    useLocalLibraryInfo,
} from '/@/renderer/features/local-library/hooks/use-local-library';
import { YtDlpStatusLine } from '/@/renderer/features/streams/components/streams-panel';
import { useCurrentServerId } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { FileInput } from '/@/shared/components/file-input/file-input';
import { Group } from '/@/shared/components/group/group';
import { NumberInput } from '/@/shared/components/number-input/number-input';
import { Paper } from '/@/shared/components/paper/paper';
import { PasswordInput } from '/@/shared/components/password-input/password-input';
import { Progress } from '/@/shared/components/progress/progress';
import { Stack } from '/@/shared/components/stack/stack';
import { Switch } from '/@/shared/components/switch/switch';
import { TextInput } from '/@/shared/components/text-input/text-input';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';
import { LocalLibraryInfo } from '/@/shared/types/local-library';

const Section = ({ children, title }: { children: React.ReactNode; title: string }) => (
    <Stack gap="sm">
        <TextTitle fw={600} order={4}>
            {title}
        </TextTitle>
        {children}
    </Stack>
);

const RemoteAccess = ({
    info,
    setInfo,
}: {
    info: LocalLibraryInfo;
    setInfo: (i: LocalLibraryInfo) => void;
}) => {
    const { t } = useTranslation();
    const [form, setForm] = useState(info.remote);
    const [saving, setSaving] = useState(false);

    const save = async (next = form) => {
        setSaving(true);
        try {
            setInfo(await window.api.localLibrary.setRemote(next));
            toast.success({ message: t('library.remoteSaved') });
        } catch (error) {
            toast.error({ message: (error as Error).message });
        } finally {
            setSaving(false);
        }
    };

    return (
        <Section title={t('library.remoteTitle')}>
            <Text isMuted size="sm">
                {t('library.remoteHint')}
            </Text>
            <Switch
                checked={info.remote.enabled}
                label={t('library.remoteEnable')}
                onChange={(e) => {
                    const next = { ...form, enabled: e.currentTarget.checked };
                    setForm(next);
                    save(next);
                }}
            />
            <Group align="flex-end">
                <TextInput
                    label={t('library.username')}
                    onChange={(e) => setForm({ ...form, username: e.currentTarget.value })}
                    value={form.username}
                />
                <PasswordInput
                    label={t('library.password')}
                    onChange={(e) => setForm({ ...form, password: e.currentTarget.value })}
                    value={form.password}
                />
                <NumberInput
                    label={t('library.port')}
                    max={65535}
                    min={1024}
                    onChange={(value) => setForm({ ...form, port: Number(value) || 45173 })}
                    value={form.port}
                    w={110}
                />
                <Button loading={saving} onClick={() => save()} variant="default">
                    {t('common.save')}
                </Button>
            </Group>
            {info.remote.enabled && (
                <Paper p="sm">
                    <Stack gap={4}>
                        <Text size="sm">{t('library.addresses')}</Text>
                        {info.addresses.map((address) => (
                            <Text ff="monospace" key={address} style={{ userSelect: 'text' }}>
                                {address}
                            </Text>
                        ))}
                        <Text isMuted size="xs">
                            {t('library.internetHint', { port: info.remote.port })}
                        </Text>
                    </Stack>
                </Paper>
            )}
        </Section>
    );
};

const OtherComputer = () => {
    const { t } = useTranslation();
    const current = getRemoteLibrary();
    const [form, setForm] = useState(current ?? { password: '', url: '', username: 'tesu' });
    const [busy, setBusy] = useState(false);

    const connect = async () => {
        setBusy(true);
        try {
            await setRemoteLibrary(await testRemoteLibrary(form));
            toast.success({ message: t('library.connected') });
        } catch (error) {
            toast.error({ message: (error as Error).message, title: t('library.connectFailed') });
        } finally {
            setBusy(false);
        }
    };

    return (
        <Section title={t('library.sourceTitle')}>
            <Text isMuted size="sm">
                {current
                    ? t('library.sourceRemote', { url: current.url })
                    : t('library.sourceLocal')}
            </Text>
            <Group align="flex-end">
                <TextInput
                    label={t('library.address')}
                    onChange={(e) => setForm({ ...form, url: e.currentTarget.value })}
                    placeholder="http://192.168.1.10:45173"
                    style={{ flex: 1 }}
                    value={form.url}
                />
                <TextInput
                    label={t('library.username')}
                    onChange={(e) => setForm({ ...form, username: e.currentTarget.value })}
                    value={form.username}
                />
                <PasswordInput
                    label={t('library.password')}
                    onChange={(e) => setForm({ ...form, password: e.currentTarget.value })}
                    value={form.password}
                />
            </Group>
            <Group>
                <Button
                    disabled={!form.url.trim()}
                    loading={busy}
                    onClick={connect}
                    variant="filled"
                >
                    {t('library.connect')}
                </Button>
                {current && (
                    <Button onClick={() => setRemoteLibrary(null)} variant="default">
                        {t('library.useThisComputer')}
                    </Button>
                )}
            </Group>
        </Section>
    );
};

/** Spotify playlist / album -> playlist of this library, no Spotify account needed */
const SpotifyImport = () => {
    const { t } = useTranslation();
    const queryClient = useQueryClient();
    const serverId = useCurrentServerId();
    const [url, setUrl] = useState('');
    const [progress, setProgress] = useState<null | { done: number; total: number }>(null);

    const run = async (input: { csv?: string; name?: string; url?: string }) => {
        setProgress({ done: 0, total: 0 });
        try {
            const result = await window.api.localLibrary.importSpotify(input, (done, total) =>
                setProgress({ done, total }),
            );
            await queryClient.invalidateQueries({ queryKey: queryKeys.playlists.root(serverId) });
            toast.success({
                message: t('library.spotifyDone', { found: result.found, total: result.total }),
                title: result.name,
            });
            setUrl('');
        } catch (error) {
            toast.error({ message: (error as Error).message, title: t('library.spotifyFailed') });
        } finally {
            setProgress(null);
        }
    };

    return (
        <Section title={t('library.spotifyTitle')}>
            <Text isMuted size="sm">
                {t('library.spotifyHint')}
            </Text>
            <Group align="flex-end">
                <TextInput
                    label={t('library.spotifyLink')}
                    onChange={(e) => setUrl(e.currentTarget.value)}
                    placeholder="https://open.spotify.com/playlist/..."
                    style={{ flex: 1 }}
                    value={url}
                />
                <Button
                    disabled={!url.trim()}
                    loading={progress !== null}
                    onClick={() => run({ url: url.trim() })}
                    variant="filled"
                >
                    {t('library.spotifyImport')}
                </Button>
            </Group>
            <FileInput
                accept=".csv,text/csv"
                disabled={progress !== null}
                label={t('library.spotifyCsv')}
                onChange={async (file) => {
                    if (!file) return;
                    run({ csv: await file.text(), name: file.name.replace(/\.csv$/i, '') });
                }}
                placeholder={t('library.spotifyCsvPlaceholder')}
                value={null}
            />
            {progress && progress.total > 0 && (
                <Stack gap={4}>
                    <Progress value={(progress.done / progress.total) * 100} />
                    <Text isMuted size="xs">
                        {t('library.spotifyProgress', progress)}
                    </Text>
                </Stack>
            )}
        </Section>
    );
};

/** Earlier YouTube downloads: find their real album / artist / cover */
const RetagButton = () => {
    const { t } = useTranslation();
    const [busy, setBusy] = useState(false);
    return (
        <Group>
            <Button
                loading={busy}
                onClick={async () => {
                    setBusy(true);
                    try {
                        const { fixed, total } = await window.api.streams.retagDownloads();
                        toast.success({ message: t('library.retagDone', { fixed, total }) });
                    } catch (error) {
                        toast.error({ message: (error as Error).message });
                    } finally {
                        setBusy(false);
                    }
                }}
                size="xs"
                variant="default"
            >
                {t('library.retag')}
            </Button>
        </Group>
    );
};

export const LibraryTab = () => {
    const { t } = useTranslation();
    const [info, setInfo] = useLocalLibraryInfo();
    const api = window.api?.localLibrary;

    if (!info || !api) return null;

    return (
        <Stack gap="xl" p="md">
            <Section title={t('library.folders')}>
                <Text isMuted size="sm">
                    {info.scanning
                        ? t('library.scanning')
                        : t('library.songCount', { count: info.count })}
                </Text>
                {info.folders.length === 0 && <Text isMuted>{t('library.noFolders')}</Text>}
                {info.folders.map((folder) => (
                    <Paper key={folder} p="sm">
                        <Group justify="space-between" wrap="nowrap">
                            <Text
                                onClick={() => api.openFolder(folder)}
                                style={{ cursor: 'pointer', wordBreak: 'break-all' }}
                            >
                                {folder}
                            </Text>
                            <Button
                                onClick={async () => setInfo(await api.removeFolder(folder))}
                                size="xs"
                                variant="subtle"
                            >
                                {t('common.delete')}
                            </Button>
                        </Group>
                    </Paper>
                ))}
                <Group>
                    <Button onClick={async () => setInfo(await api.addFolder())} variant="filled">
                        {t('library.addFolder')}
                    </Button>
                    <Button
                        loading={info.scanning}
                        onClick={async () => setInfo(await api.scan())}
                        variant="default"
                    >
                        {t('library.rescan')}
                    </Button>
                    <Button onClick={() => api.openFolder(info.downloadsDir)} variant="subtle">
                        {t('library.openDownloads')}
                    </Button>
                </Group>
            </Section>

            <RemoteAccess info={info} setInfo={setInfo} />

            <OtherComputer />

            <SpotifyImport />

            <Section title="YouTube / Twitch">
                <YtDlpStatusLine />
                <RetagButton />
                <Text isMuted size="sm">
                    {t('library.downloadsHint', { dir: info.downloadsDir })}
                </Text>
            </Section>
        </Stack>
    );
};
