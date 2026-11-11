// Nen anh PNG do Codex sinh ra thanh JPEG <= 280 KB. Dung:  node compress-images.cjs <thu-muc-nguon> <thu-muc-dich>
// Can sharp: `npm i sharp` (hoac chay trong thu muc da cai sharp, NODE_PATH tro toi node_modules).
const fs = require('fs'), path = require('path'), sharp = require('sharp');
const [src, dst] = process.argv.slice(2);
fs.mkdirSync(dst, { recursive: true });
(async () => {
  for (const f of fs.readdirSync(src).filter(x => /\.(png|jpe?g)$/i.test(x))) {
    const name = path.basename(f, path.extname(f)) + '.jpg';
    const square = /^(product|avatar)-/.test(name);
    let width = square ? 800 : 1280, q = 82, buf;
    for (;;) {
      buf = await sharp(path.join(src, f)).resize({ width, height: square ? width : undefined, fit: 'cover', withoutEnlargement: true })
        .jpeg({ quality: q, mozjpeg: true }).toBuffer();
      if (buf.length <= 280 * 1024) break;
      q > 55 ? q -= 6 : width = Math.round(width * 0.88);
    }
    fs.writeFileSync(path.join(dst, name), buf);
    console.log(name, Math.round(buf.length / 1024) + ' KB');
  }
})();
