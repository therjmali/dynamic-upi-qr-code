const V="dupi-v15",FILES=["./","index.html","qrcode.js","jsqr.js","logos.js","banks.js","manifest.webmanifest","favicon.svg","icon-180.png","icon-192.png","icon-512.png","icon-maskable-512.png","fonts/sora-latin.woff2","fonts/inter-latin.woff2","fonts/inter-rupee.woff2"];
self.addEventListener("install",e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(FILES)).then(()=>self.skipWaiting()))});
self.addEventListener("activate",e=>{e.waitUntil(caches.keys().then(k=>Promise.all(k.filter(x=>x!==V).map(x=>caches.delete(x)))).then(()=>self.clients.claim()))});
self.addEventListener("fetch",e=>{const r=e.request;if(r.method!=="GET")return;
 e.respondWith(fetch(r).then(res=>{if(res.ok){const c=res.clone();caches.open(V).then(x=>x.put(r,c))}return res}).catch(()=>caches.match(r,{ignoreSearch:true}).then(m=>m||(r.mode==="navigate"?caches.match("index.html"):Response.error()))))});
