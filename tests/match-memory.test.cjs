const fs=require('node:fs');
const vm=require('node:vm');
const assert=require('node:assert/strict');
const html=fs.readFileSync('app/src/main/assets/lyrics_overlay.html','utf8');
for(const script of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g))new vm.Script(script[1]);
let stored='',requests=0;
const original={source:'QQ音乐',recordId:'a',title:'Song',lyrics:'original lyrics',wordLyrics:'words'};
const next={source:'QQ音乐',recordId:'b',title:'Song',lyrics:'another version'};
const context={console,TextEncoder,setTimeout:()=>1,clearTimeout:()=>{},requestAnimationFrame:()=>{},
  playback:{key:'song\u0000artist',track:'Song',artist:'Artist',durationMs:216000,requestId:7,candidates:[original],candidateIndex:0},
  automaticCandidates:[original],manualSourceKey:'',applyCandidate:()=>{},isUsableLyricPayload:x=>!!x,
  localStorage:{getItem:()=>null,setItem:()=>{}},window:{LobstaNativeLyrics:{
    readMatchMemory:()=>stored,writeMatchMemory:x=>{stored=x},rematchLyrics:()=>requests++
  }}};
vm.createContext(context);
vm.runInContext(html.slice(html.indexOf('    let sourceClickTimer='),html.indexOf('    function showMatchMenu()')),context);
vm.runInContext('matchNotice=()=>{};',context);
context.rematchCurrentSource();
assert.equal(JSON.parse(stored)[0].original.recordId,'a');
context.receiveRematch(7,1,next);
assert.equal(context.playback.candidates[0].recordId,'b');
assert.equal(JSON.parse(stored)[0].history.length,2);
context.rematchCurrentSource(true);
assert.equal(context.playback.candidates[0].recordId,'a');
assert.equal(requests,1,'restore must work offline without a new request');
assert.equal(JSON.parse(stored).length,0);
context.rematchCurrentSource();
context.refreshMatchManagement();
context.receiveRematch(7,2,next);
assert.equal(context.playback.candidates[0].recordId,'a','late response must not override management');
stored=JSON.stringify([{key:'song\u0000artist|216|QQ音乐',at:Date.now(),candidate:next}]);
assert.equal(context.readMatchMemories()[0].needsReview,true,'legacy choices require review');
context.refreshMatchManagement();
assert.equal(context.playback.candidates[0].recordId,'a','unreviewed old selection must not override automatic');
console.log('PASS: scripts parse, rematch history, offline rollback, stale-response guard, legacy-choice review');
