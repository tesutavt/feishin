import { useTranslation } from 'react-i18next';

import { toTag } from '/@/renderer/hooks';
import { useLatestVersion } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { GITHUB_URL } from '/@/shared/constants/repository';

export const UpdateAvailableButton = () => {
    const { t } = useTranslation();
    const { currentVersion, isUpdateAvailable, latestVersion } = useLatestVersion();

    if (!isUpdateAvailable || !latestVersion) {
        return null;
    }

    return (
        <Button
            component="a"
            href={`${GITHUB_URL}/releases/tag/${toTag(latestVersion || currentVersion)}`}
            size="compact-sm"
            target="_blank"
            variant="filled"
        >
            {t('common.newVersionAvailable')}: v{latestVersion}
        </Button>
    );
};
