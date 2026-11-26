// Renders the launcher icon layers from icon.html (Pingo and Olivia drawn as SVG).
// Usage: node tools/icons/render.js   (needs Playwright with Chromium)
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const root = path.resolve(__dirname, '../..');
  const res = path.join(root, 'app/src/main/res');
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 500, height: 2000 } });
  await p.goto('file://' + path.join(__dirname, 'icon.html'));
  await p.waitForTimeout(300);
  const shot = async (sel, file, transparent) => (await p.$(sel)).screenshot({ path: path.join(res, file), omitBackground: !!transparent });
  await shot('#bg', 'drawable-nodpi/ic_launcher_background.png');
  await shot('#fg', 'drawable-nodpi/ic_launcher_foreground.png', true);
  await shot('#fgm', 'drawable-nodpi/ic_launcher_monochrome.png', true);
  await shot('#legacy', 'mipmap-xxxhdpi/ic_launcher.png', true);
  await b.close();
})();
