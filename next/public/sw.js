const CACHE='logit-shell-v2';
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(c=>c.addAll(['/','/logo.svg'])).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('logit-shell-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('message',event=>{if(event.data?.type==='CACHE_ASSETS')event.waitUntil(caches.open(CACHE).then(c=>Promise.all(event.data.resources.filter(u=>{try{const x=new URL(u);return x.origin===self.location.origin&&x.pathname.startsWith('/_next/static/')}catch{return false}}).map(u=>c.add(u).catch(()=>{})))))});
self.addEventListener('fetch',event=>{const r=event.request,u=new URL(r.url);if(r.method!=='GET'||u.origin!==self.location.origin||u.pathname.startsWith('/download'))return;if(r.mode==='navigate')event.respondWith(fetch(r).then(response=>{if(response.ok){const copy=response.clone();void caches.open(CACHE).then(c=>c.put('/',copy))}return response}).catch(()=>caches.match('/')));else if(u.pathname.startsWith('/_next/static/')||u.pathname==='/logo.svg')event.respondWith(caches.match(r).then(cached=>cached||fetch(r).then(response=>{if(response.ok){const copy=response.clone();void caches.open(CACHE).then(c=>c.put(r,copy))}return response})));});

self.addEventListener('push', event => {
  if (!event.data) return;
  try {
    const message = event.data.json();
    if (typeof message.title !== 'string' || typeof message.body !== 'string') return;
    event.waitUntil(self.registration.showNotification(message.title, {
      body: message.body, tag: message.tag, icon: '/logo.svg',
    }));
  } catch {}
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true})
    .then(windows => windows.find(window => new URL(window.url).pathname === '/')?.focus() || self.clients.openWindow('/')));
});
