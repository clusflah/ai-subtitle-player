const status = document.querySelector('#status');
const open = document.querySelector('#open');

async function currentTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

async function check() {
  const tab = await currentTab();
  try {
    const result = await chrome.tabs.sendMessage(tab.id, { type: 'VOX_MANGA_STATUS' });
    const count = result?.count || 0;
    status.textContent = count >= 3 ? `发现 ${count} 张漫画图片` : '未发现连续的漫画图片';
    open.disabled = count < 3;
  } catch (_) {
    status.textContent = '此页面无法使用扩展';
  }
}

open.addEventListener('click', async () => {
  const tab = await currentTab();
  await chrome.tabs.sendMessage(tab.id, { type: 'VOX_MANGA_OPEN' });
  window.close();
});
check();
