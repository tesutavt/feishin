import { Outlet } from 'react-router';

import { useLocalLibraryConnection } from '/@/renderer/features/local-library/hooks/use-local-library';
import { useAuthStore } from '/@/renderer/store';
import { Center } from '/@/shared/components/center/center';
import { Spinner } from '/@/shared/components/spinner/spinner';
import { Text } from '/@/shared/components/text/text';

export const AppOutlet = () => {
    const { error, ready } = useLocalLibraryConnection();
    const hasServer = useAuthStore((state) => Boolean(state.currentServer));

    if (error) {
        return (
            <Center h="100%">
                <Text>{error}</Text>
            </Center>
        );
    }

    if (!ready || !hasServer) {
        return (
            <Center h="100%">
                <Spinner />
            </Center>
        );
    }

    return <Outlet />;
};
