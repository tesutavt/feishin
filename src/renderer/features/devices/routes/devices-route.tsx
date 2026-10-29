import { useTranslation } from 'react-i18next';

import { api } from '/@/renderer/api';
import { PageHeader } from '/@/renderer/components/page-header/page-header';
import {
    DeviceCommand,
    RemoteDevice,
    sendDeviceCommand,
    useDevicesStore,
} from '/@/renderer/features/devices/hooks/use-devices';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { LibraryHeaderBar } from '/@/renderer/features/shared/components/library-header-bar';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { useAuthStore, usePlayerStoreBase } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Paper } from '/@/shared/components/paper/paper';
import { ScrollArea } from '/@/shared/components/scroll-area/scroll-area';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';

const formatTime = (seconds: number) =>
    `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;

const DeviceCard = ({ device }: { device: RemoteDevice }) => {
    const { t } = useTranslation();

    const command = (cmd: DeviceCommand) =>
        sendDeviceCommand(device.id, cmd).catch((error) => toast.error({ message: error.message }));

    // take the track over: same song and position here, pause it there
    const playHere = async () => {
        const server = useAuthStore.getState().currentServer;
        if (!device.song || !server) return;
        try {
            const song = await api.controller.getSongDetail({
                apiClientProps: { serverId: server.id },
                query: { id: device.song.id },
            });
            if (!song) return;
            const player = usePlayerStoreBase.getState();
            player.setQueue([song], 0, device.position);
            player.mediaPlay();
            command('pause');
        } catch (error) {
            toast.error({ message: (error as Error).message });
        }
    };

    return (
        <Paper p="md">
            <Stack gap="xs">
                <Group justify="space-between">
                    <Text fw={700}>{device.name}</Text>
                    <Text isMuted size="sm">
                        {device.state === 'playing'
                            ? t('devices.playing')
                            : device.state === 'paused'
                              ? t('devices.paused')
                              : t('devices.idle')}
                    </Text>
                </Group>
                {device.song ? (
                    <Text>
                        {device.song.artist} - {device.song.title}
                        <Text component="span" isMuted size="sm">
                            {'  '}
                            {formatTime(device.position)}
                            {device.song.duration ? ` / ${formatTime(device.song.duration)}` : ''}
                        </Text>
                    </Text>
                ) : (
                    <Text isMuted>{t('devices.nothing')}</Text>
                )}
                <Group gap="xs">
                    <Button onClick={() => command('previous')} size="xs" variant="default">
                        ⏮
                    </Button>
                    <Button
                        onClick={() => command(device.state === 'playing' ? 'pause' : 'play')}
                        size="xs"
                        variant="default"
                    >
                        {device.state === 'playing' ? '⏸' : '▶'}
                    </Button>
                    <Button onClick={() => command('next')} size="xs" variant="default">
                        ⏭
                    </Button>
                    {device.song && (
                        <Button onClick={playHere} size="xs" variant="filled">
                            {t('devices.playHere')}
                        </Button>
                    )}
                </Group>
            </Stack>
        </Paper>
    );
};

const DevicesRoute = () => {
    const { t } = useTranslation();
    const devices = useDevicesStore((state) => state.devices);

    return (
        <PageErrorBoundary>
            <AnimatedPage>
                <PageHeader>
                    <LibraryHeaderBar ignoreMaxWidth>
                        <LibraryHeaderBar.Title>{t('devices.title')}</LibraryHeaderBar.Title>
                    </LibraryHeaderBar>
                </PageHeader>
                <ScrollArea>
                    <Stack gap="md" maw="800px" p="md">
                        {devices.length === 0 ? (
                            <Text isMuted>{t('devices.empty')}</Text>
                        ) : (
                            devices.map((device) => <DeviceCard device={device} key={device.id} />)
                        )}
                    </Stack>
                </ScrollArea>
            </AnimatedPage>
        </PageErrorBoundary>
    );
};

export default DevicesRoute;
