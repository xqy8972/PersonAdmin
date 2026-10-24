/* 个人管理系统 PWA Service Worker
 * 策略：页面/CDN 静态资源缓存优先（后台刷新），后端 API 一律网络直达不缓存。
 * 注意：SW 仅在 HTTPS（或 localhost）下生效，HTTP 部署时浏览器会自动忽略注册。
 */
const CACHE = 'personadmin-v2';

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(['./'])).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  // 后端接口（行情/数据必须实时）：交给网络，不进缓存
  if (url.pathname.indexOf('/api/') !== -1) return;
  // 静态资源与页面壳：缓存优先，后台用网络最新版悄悄更新缓存
  e.respondWith(
    caches.match(e.request).then(hit => {
      const net = fetch(e.request).then(res => {
        if (res && (res.status === 200 || res.type === 'opaque')) {
          const clone = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return res;
      }).catch(() => hit);
      return hit || net;
    })
  );
});
