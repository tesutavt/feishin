import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
    getRemoteLibrary,
    setRemoteLibrary,
    testRemoteLibrary,
    useLocalLibraryInfo,
} from '/@/renderer/features/local-library/hooks/use-local-library';
import { YtDlpStatusLine } from '/@/renderer/features/streams/components/streams-panel';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { NumberInput } from '/@/shared/components/number-input/number-input';
import { Paper } from '/@/shared/components/paper/paper';
import { PasswordInput } from '/@/shared/components/password-input/password-input';
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

            <Section title="YouTube / Twitch">
                <YtDlpStatusLine />
                <Text isMuted size="sm">
                    {t('library.downloadsHint', { dir: info.downloadsDir })}
                </Text>
            </Section>
        </Stack>
    );
};
