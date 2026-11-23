// scripts/dev/tar-parser-parity.mjs
// Parity fixture for android-shell TarExtractor.kt (CLI).
// Extracts a tar.gz produced by a REAL tar implementation (bsdtar / GNU tar)
// with the shared replica parser, so its tree can be diffed against system-tar
// extraction. See tar-gnu-branches-test.mjs for crafted GNU/pax branch tests.
//
// Usage: node scripts/dev/tar-parser-parity.mjs <archive.tar.gz> <outDir>
import fs from 'node:fs';
import zlib from 'node:zlib';
import { extractBuffer } from './tar-parity-lib.mjs';

const [,, archive, outDir] = process.argv;
if (!archive || !outDir) { console.error('usage: node tar-parser-parity.mjs <in.tar.gz> <outDir>'); process.exit(2); }
fs.mkdirSync(outDir, { recursive: true });
const entries = extractBuffer(zlib.gunzipSync(fs.readFileSync(archive)), outDir);
console.log(JSON.stringify(entries, null, 1));
console.error(`entries=${entries.length}`);
