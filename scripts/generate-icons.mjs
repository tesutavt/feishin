/* eslint-disable @typescript-eslint/explicit-function-return-type -- plain JS build script */
// Renders media/tesuplayer.svg into every app icon: node scripts/generate-icons.mjs
import { Resvg } from '@resvg/resvg-js';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const svg = readFileSync(join(root, 'media/tesuplayer.svg'));

const render = (size) => new Resvg(svg, { fitTo: { mode: 'width', value: size } }).render().asPng();

const write = (path, data) => {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), data);
};

// ICO with embedded PNG images (supported since Windows Vista)
const toIco = (sizes) => {
    const images = sizes.map((size) => ({ data: render(size), size }));
    const header = Buffer.alloc(6 + images.length * 16);
    header.writeUInt16LE(0, 0);
    header.writeUInt16LE(1, 2);
    header.writeUInt16LE(images.length, 4);
    let offset = header.length;
    images.forEach(({ data, size }, i) => {
        const entry = 6 + i * 16;
        header.writeUInt8(size >= 256 ? 0 : size, entry);
        header.writeUInt8(size >= 256 ? 0 : size, entry + 1);
        header.writeUInt16LE(1, entry + 4);
        header.writeUInt16LE(32, entry + 6);
        header.writeUInt32LE(data.length, entry + 8);
        header.writeUInt32LE(offset, entry + 12);
        offset += data.length;
    });
    return Buffer.concat([header, ...images.map((image) => image.data)]);
};

for (const size of [16, 24, 32, 48, 64, 72, 96, 128, 256, 512, 1024]) {
    write(`assets/icons/${size}x${size}.png`, render(size));
}
write('assets/icons/icon.png', render(512));
write('resources/icon.png', render(512));
write('media/tesuplayer.png', render(512));
write('assets/icons/icon.ico', toIco([16, 24, 32, 48, 64, 128, 256]));
write('assets/icons/favicon.ico', toIco([16, 32, 48]));

// Android launcher icons (mipmap densities, 48dp base)
const android = { hdpi: 72, mdpi: 48, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [density, size] of Object.entries(android)) {
    write(`android/app/src/main/res/mipmap-${density}/ic_launcher.png`, render(size));
}

console.log('Icons generated');
