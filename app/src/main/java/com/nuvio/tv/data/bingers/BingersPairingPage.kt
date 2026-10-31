package com.nuvio.tv.data.bingers

internal object BingersPairingPage {
    val HTML = """<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>ربط Bingers • NuvioTV</title>
<style>
:root{color-scheme:dark;font-family:system-ui,sans-serif;background:#0e0e16;color:#f5f3ff}
*{box-sizing:border-box}body{margin:0;min-height:100dvh;padding:32px 20px;background:radial-gradient(ellipse at top,#34204f 0,transparent 65%)}
main{max-width:480px;margin:0 auto}.brand{color:#c4a5ff;font-size:14px;font-weight:700;letter-spacing:2px}
h1{font-size:32px;margin:16px 0 10px;line-height:1.4}p{color:#bcb8cb;line-height:1.9;margin:8px 0 24px}
.card{background:#1b1926;border:1px solid #353044;border-radius:24px;padding:24px;margin-top:24px}
.step{display:inline-grid;place-items:center;width:28px;height:28px;background:#3d2b57;color:#dac4ff;border-radius:50%;margin-inline-end:8px;font-size:14px}
h2{font-size:18px;margin:0 0 16px}a,button{display:block;width:100%;padding:16px;border-radius:14px;text-align:center;font:700 16px system-ui;text-decoration:none;border:0;cursor:pointer}
a{background:#30273f;color:#e6d9ff;border:1px solid #514064}button{background:#b392f0;color:#180d28;margin-top:20px}
textarea{width:100%;min-height:120px;border:1px solid #50455e;border-radius:14px;padding:16px;background:#121019;color:#f5f3ff;font:15px system-ui;direction:ltr;resize:vertical}
textarea:focus{outline:2px solid #b392f0;outline-offset:2px}button:disabled{opacity:.55;cursor:wait}
small{display:block;color:#a6a0b5;line-height:1.9;margin-top:14px}#status{white-space:pre-wrap;line-height:1.8;margin:18px 0 0}#status.ok{color:#9ee0b0}.error{color:#ffb5b5}
</style></head><body><main><div class="brand">NUVIO TV · BINGERS</div>
<h1>اربط حسابك بالتلفزيون</h1><p>اطلب رابط دخول جديداً من Bingers، ثم أرسله إلى التلفزيون من هذه الصفحة.</p>
<section class="card"><h2><span class="step">1</span>طلب رابط الدخول</h2>
<a href="https://bingers.app/import" target="_blank" rel="noopener noreferrer">طلب الرابط من Bingers ↗</a>
<small>أدخل بريدك في Bingers. عند وصول الرسالة، انسخ رابط الدخول دون فتحه، ثم ارجع إلى هذه الصفحة.</small></section>
<section class="card"><h2><span class="step">2</span>إرسال الرابط إلى التلفزيون</h2>
<label for="link">رابط الدخول من رسالة Bingers</label><br><br>
<textarea id="link" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://bingers.app/verify?token=…"></textarea>
<button id="send" type="button">إرسال إلى التلفزيون</button><div id="status" role="status" aria-live="polite"></div>
<small>أبقِ صفحة الربط مفتوحة على التلفزيون، واجعل الموبايل والتلفزيون على الشبكة نفسها.</small></section>
</main><script>
const token=location.hash.slice(1);history.replaceState(null,'',location.pathname);
const input=document.getElementById('link'),button=document.getElementById('send'),status=document.getElementById('status');
if(!token){button.disabled=true;status.textContent='افتح هذه الصفحة من باركود التلفزيون.';status.className='error'}
button.addEventListener('click',async()=>{
let valid=false;try{const u=new URL(input.value.trim());valid=u.protocol==='https:'&&u.hostname==='bingers.app'&&!u.username&&!u.password&&['/m','/verify'].includes(u.pathname.replace(/\/$/,''))&&!!u.searchParams.get('token')}catch(e){}
if(!valid){status.textContent='الصق رابط الدخول الأصلي من رسالة Bingers.';status.className='error';return}
button.disabled=true;status.className='';status.textContent='جاري الربط على التلفزيون…';
try{const r=await fetch('/api/link',{method:'POST',headers:{'Content-Type':'application/json','X-Pairing-Token':token},body:JSON.stringify({link:input.value.trim()}),cache:'no-store'});
const data=await r.json();status.textContent=data.message;status.className=data.ok?'ok':'error';if(data.ok){input.value='';input.disabled=true;button.textContent='تم الربط بنجاح'}else{button.disabled=false}}
catch(e){status.textContent='تعذر الاتصال بالتلفزيون. تأكد من الشبكة وافتح باركوداً جديداً إذا انتهت الجلسة.';status.className='error';button.disabled=false}
});
</script></body></html>"""
}
