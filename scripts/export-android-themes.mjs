/* eslint-disable @typescript-eslint/explicit-function-return-type -- plain JS build script */
// The desktop's built-in themes for the Android app: android/app/src/main/assets/themes.json
// node scripts/export-android-themes.mjs
import { mkdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(join(root, 'node_modules', '.pnpm', 'node_modules', 'x.js'));
const esbuild = require('esbuild');

const out = join(root, 'out', 'android-themes.mjs');
await esbuild.build({
    bundle: true,
    entryPoints: [join(root, 'src', 'shared', 'themes', 'app-theme.ts')],
    format: 'esm',
    logLevel: 'warning',
    outfile: out,
    platform: 'node',
    plugins: [
        {
            name: 'shared-alias',
            setup(build) {
                build.onResolve({ filter: /^\/@\/shared\// }, async (args) =>
                    build.resolve(
                        join(root, 'src', 'shared', args.path.slice('/@/shared/'.length)),
                        {
                            kind: args.kind,
                            resolveDir: root,
                        },
                    ),
                );
            },
        },
    ],
});
const { appTheme, getAppTheme } = await import(pathToFileURL(out).href);

// "rosePineMoon" -> "Rose Pine Moon"
const label = (id) =>
    id
        .replace(/([a-z])([A-Z])/g, '$1 $2')
        .split(/[-_\s]/)
        .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
        .join(' ');

const KEYS = [
    'background',
    'background-alternate',
    'surface',
    'surface-foreground',
    'foreground',
    'foreground-muted',
    'primary',
    'state-error',
    'state-info',
    'state-success',
    'state-warning',
];

const themes = Object.keys(appTheme).map((id) => {
    const theme = getAppTheme(id);
    const colors = Object.fromEntries(
        KEYS.filter((k) => theme.colors?.[k]).map((k) => [k, theme.colors[k]]),
    );
    return { colors, id, label: label(id), mode: theme.mode ?? 'dark' };
});

const target = join(root, 'android', 'app', 'src', 'main', 'assets', 'themes.json');
mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, JSON.stringify(themes, null, 1));
console.log(`themes: ${themes.length} -> ${target}`);
