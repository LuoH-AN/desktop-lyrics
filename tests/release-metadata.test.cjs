const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = name => fs.readFileSync(name, 'utf8');
const gradle = read('app/build.gradle.kts');
const version = /versionName = "([^"]+)"/.exec(gradle)[1];
const code = Number(/versionCode = (\d+)/.exec(gradle)[1]);
assert.match(version, /^\d+\.\d+\.\d+$/, 'stable version must be x.y.z');
assert.ok(code > 13, 'versionCode must upgrade the published 1.0.2 build');
assert.ok(gradle.includes('applicationId = "com.luoh.music.lrc"'));
for (const file of ['README.md', 'README_EN.md']) {
  const text = read(file);
  assert.ok(text.includes(version) && text.includes('LuoH-AN'));
  assert.ok(text.includes('https://github.com/LuoH-AN/desktop-lyrics/releases/latest'),
    'download link must remain valid before the source version is published');
  assert.ok(!/bilibili|Tcrrrry|com\.tcrrry/.test(text), 'front page must use current project identity');
  assert.ok(text.split('\n').length < 50, 'README must remain concise');
}
const layout = read('app/src/main/res/layout/activity_settings.xml');
const activity = read('app/src/main/kotlin/com/luoh/music/lrc/SettingsActivity.kt');
assert.ok(layout.includes('cell_maintainer') && layout.includes('维护者 · LuoH-AN'));
assert.ok(activity.includes('openUrl("https://github.com/LuoH-AN")'));
assert.ok(!/bilibili|Tcrrrry/.test(layout + activity));
assert.ok(read('NOTICE.md').includes('tcrrry/desktop-lyrics'), 'retain project provenance outside the front page');
assert.ok(read(`docs/releases/${version}.md`).includes(`desktop-lyrics-${version}-arm64-v8a.apk`));
const workflow = read('.github/workflows/release.yml');
assert.ok(workflow.includes('assembleRelease') && workflow.includes('apksigner'));
assert.ok(workflow.includes('application-debuggable') && workflow.includes('RELEASE_CERT_SHA256'));
assert.ok(workflow.includes('--verify-tag --latest'));
console.log(`PASS: ${version} release metadata, maintainer links, concise documentation and signed-release checks`);
