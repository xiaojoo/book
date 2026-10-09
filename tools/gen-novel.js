// 生成一本千万字的中文小说，用来在真机上跑导入和翻页。node gen-novel.js 输出.txt
const fs = require('fs');
const WORDS = ['少年', '林动', '山壁', '长剑', '夜色', '石洞', '雷光', '古老', '传承', '天赋',
  '修炼', '血脉', '古族', '令牌', '崖底', '雾气', '掌法', '符纸', '残卷', '宗门',
  '他抬起手', '风停了半刻', '远处传来钟声', '石阶上满是青苔', '那一剑极慢'];
let s = 20261007 >>> 0;
const rnd = () => ((s = (s * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
const sentence = () => {
  const n = 6 + Math.floor(rnd() * 14);
  let out = '';
  for (let i = 0; i < n; i++) out += WORDS[Math.floor(rnd() * WORDS.length)];
  return out + '。';
};
const paragraph = () => {
  const n = 2 + Math.floor(rnd() * 5);
  let out = '';
  for (let i = 0; i < n; i++) out += sentence();
  return out;
};

const chapters = Number(process.argv[3] || 1000);
const perChapter = Number(process.argv[4] || 10000);
const out = fs.createWriteStream(process.argv[2] || 'novel-10m.txt', { encoding: 'utf8' });
let chars = 0;
for (let i = 0; i < chapters; i++) {
  const title = `第${i + 1}章 ${WORDS[Math.floor(rnd() * WORDS.length)]}之上`;
  out.write(title + '\r\n');
  chars += title.length + 1;
  let count = 0;
  while (count < perChapter) {
    const p = paragraph();
    out.write(p + '\n');
    count += p.length + 1;
    chars += p.length + 1;
  }
  out.write('\n');
  chars += 1;
}
out.end(() => console.log('chars=' + chars));
