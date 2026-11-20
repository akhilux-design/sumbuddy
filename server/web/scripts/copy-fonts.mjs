// Copies the self-hosted fonts into public/fonts from the Fontsource packages (devDependencies, SIL OFL 1.1).
// Run once after changing the font list or the package versions: `npm run fonts`. The copied files are committed,
// so building and serving the page never needs these packages or any font service.
//
// Weights follow design/spec/typography.json: Bricolage Grotesque Bold and ExtraBold; DM Sans Regular, Medium,
// SemiBold and Bold. Subsets: latin and latin-ext (the rupee sign U+20B9 is in latin-ext).

import { copyFile, mkdir, readdir, rm } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, '..', 'public', 'fonts');
const modules = join(here, '..', 'node_modules', '@fontsource');

export const FONTS = [
  { pkg: 'bricolage-grotesque', weights: [700, 800], license: 'OFL-BricolageGrotesque.txt' },
  { pkg: 'dm-sans', weights: [400, 500, 600, 700], license: 'OFL-DMSans.txt' },
];
export const SUBSETS = ['latin', 'latin-ext'];

await mkdir(out, { recursive: true });
for (const name of await readdir(out)) {
  if (name.endsWith('.woff2') || name.startsWith('OFL-')) await rm(join(out, name));
}
for (const font of FONTS) {
  for (const subset of SUBSETS) {
    for (const weight of font.weights) {
      const file = `${font.pkg}-${subset}-${weight}-normal.woff2`;
      await copyFile(join(modules, font.pkg, 'files', file), join(out, file));
    }
  }
  await copyFile(join(modules, font.pkg, 'LICENSE'), join(out, font.license));
}
console.log(`Fonts copied to ${out}`);
