// scripts/seed-mobile-comfort.mjs
// Appliance build step: enable @local/dsh-mobile-comfort in the web profile.
// Format learned from a real profile: bundle enablement = dependency entry +
// the package name listed in `dsh.profile.bundles` of the profile package.json.
// Idempotent; run AFTER `dsh plugin --profile web add file:/opt/dsh-plugins/dsh-mobile-comfort`.
//
// Usage: node seed-mobile-comfort.mjs <profileDirPackageJson> [packageName]
import fs from 'node:fs';

const [target = '/root/.dsh/profiles/web/package.json', pkg = '@local/dsh-mobile-comfort'] = process.argv.slice(2);
const json = JSON.parse(fs.readFileSync(target, 'utf8'));
json.dsh ??= {};
const prof = (json.dsh.profile ??= {});
prof.bundles ??= [];
if (!Array.isArray(prof.bundles)) throw new Error(`${target}: dsh.profile.bundles exists but is not an array`);
if (!prof.bundles.includes(pkg)) prof.bundles.push(pkg);
if (!json.dependencies?.[pkg]) throw new Error(`${pkg} missing from dependencies — run "dsh plugin --profile web add file:/opt/dsh-plugins/dsh-mobile-comfort" first`);
fs.writeFileSync(target, JSON.stringify(json, null, 2) + '\n');
console.log(`seeded ${pkg} into ${target} (bundles=${prof.bundles.length})`);
