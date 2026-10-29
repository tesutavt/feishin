import { PageHeader } from '/@/renderer/components/page-header/page-header';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { LibraryHeaderBar } from '/@/renderer/features/shared/components/library-header-bar';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { StreamsPanel } from '/@/renderer/features/streams/components/streams-panel';
import { ScrollArea } from '/@/shared/components/scroll-area/scroll-area';

const StreamsRoute = () => (
    <PageErrorBoundary>
        <AnimatedPage>
            <PageHeader>
                <LibraryHeaderBar ignoreMaxWidth>
                    <LibraryHeaderBar.Title>YouTube / Twitch</LibraryHeaderBar.Title>
                </LibraryHeaderBar>
            </PageHeader>
            <ScrollArea>
                <StreamsPanel />
            </ScrollArea>
        </AnimatedPage>
    </PageErrorBoundary>
);

export default StreamsRoute;
