import java.nio.charset.StandardCharsets;

/** Generated desktop settings; no settings HTML file is added to app/www. */
final class SettingsPage {
 static byte[] html(){
  String html="""
<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Settings / الإعدادات</title>
<style>body{margin:0;padding:24px;background:#f7f3e9;color:#302a21;font:16px 'Segoe UI',Tahoma,Arial,sans-serif;line-height:1.7}.settings-page{max-width:680px;margin:24px auto;padding:24px;border:1px solid #d9c794;border-radius:18px;background:#fffdf8;box-shadow:0 6px 24px #0001}header{text-align:center}header img{width:80px;height:80px;object-fit:contain}h1{font-size:24px}a{color:#695018}</style>
</head><body><main class="settings-page"><header><img src="/__startup/logo.png" alt="Noon"><h1>Settings / الإعدادات</h1></header><div id="noonSettingsHost"></div><p><a href="/">Back to Noon / العودة إلى نون</a></p></main><script>
"""+script()+"</script><script src=\"/__noon/context.js\"></script></body></html>";
  return html.getBytes(StandardCharsets.UTF_8);
 }
 static String script(){return """
(function(){
 if(document.getElementById('noonBrowserSettings'))return;
 var host=document.getElementById('noonSettingsHost')||document.querySelector('#indexBox .index-card');if(!host)return;
 var notices=host.querySelectorAll?host.querySelectorAll('.index-note'):[];for(var ni=notices.length-1;ni>=0;ni--)if(notices[ni].parentNode)notices[ni].parentNode.removeChild(notices[ni]);
 var lang=(window.INDEX_LANG||{}).lang;
 if(lang!=='ar'&&lang!=='en'){try{lang=localStorage.getItem('welcome_language');}catch(ignore){}}
 var english=lang==='en';function text(ar,en){return english?en:ar;}
 if(document.getElementById('noonSettingsHost')){document.documentElement.lang=english?'en':'ar';document.documentElement.dir=english?'ltr':'rtl';}
 var bridge=null;
 try{if(window.top.chrome&&window.top.chrome.webview&&typeof window.top.chrome.webview.postMessage==='function')bridge=window.top.chrome.webview;}catch(ignore){}
 var nw=navigator.userAgent.indexOf('NoonNW/')>=0;
 var style=document.createElement('style');style.textContent='.noon-browser-settings{margin-top:24px;padding:18px;border:1px solid #b69a59;border-radius:12px;text-align:start}.noon-browser-settings h2{margin-top:0;font-size:1.25em}.noon-browser-settings p{line-height:1.7}.noon-browser-settings button{display:block;box-sizing:border-box;width:100%;padding:12px 16px;margin:12px 0 6px;border:1px solid #a58b51;border-radius:8px;background:transparent;color:inherit;font:inherit;white-space:normal;cursor:pointer}.noon-browser-settings button:disabled{opacity:.5;cursor:default}.noon-browser-settings .noon-clear-all{border-color:#b66b57}.noon-browser-settings small{display:block;line-height:1.6}';document.getElementsByTagName('head')[0].appendChild(style);
 var section=document.createElement('section');section.id='noonBrowserSettings';section.className='settings-section noon-browser-settings';
 function node(tag,value){var el=document.createElement(tag);el.textContent=value;section.appendChild(el);return el;}
 node('h2',text('بيانات المستعرض','Browser data'));
 node('p',text('استخدم نفس أوامر المسح المتاحة في قائمة المستعرض.','Use the same cleanup commands available in the browser menu.'));
 function button(id,action,ar,en,descriptionAr,descriptionEn){
  var b=node('button',text(ar,en));b.id=id;b.type='button';b.disabled=!bridge&&!nw;
  if(action==='clear-all')b.className='noon-clear-all';
  b.onclick=function(){
   if(bridge)bridge.postMessage('noon:'+action);
   else if(nw)window.top.postMessage({noonContext:true,action:action,value:''},'*');
   return false;
  };
  node('small',text(descriptionAr,descriptionEn));
 }
 button('noonClearCache','clear-cache','مسح التخزين المؤقت وسجل التصفح','Clear cache and browsing history','يحتفظ بالإعدادات والعلامات المحفوظة.','Keeps saved settings and bookmarks.');
 button('noonClearAllData','clear-all','مسح جميع بيانات المستعرض','Clear all browser data','يزيل الإعدادات والعلامات وبيانات المستعرض المحفوظة، ثم يفتح صفحة بدء التطبيق.','Removes saved settings, bookmarks and browser data, then opens the app startup page.');
 if(!bridge&&!nw)node('p',text('افتح هذه الصفحة داخل تطبيق نون لتنفيذ المسح.','Open this page inside Noon to clear browser data.'));
 host.appendChild(section);
 var ports=document.createElement('section');ports.id='noonServerPortSettings';ports.className='settings-section';
 ports.style.cssText='margin:20px 0;padding:20px;border:1px solid #b18b3c;border-radius:12px';
 function pn(tag,value){var e=document.createElement(tag);e.textContent=value;ports.appendChild(e);return e;}
 pn('h2',text('منفذ الخادم المحلي','Local server port'));
 var current=pn('p','');var label=pn('label',text('رقم المنفذ','Port number'));label.htmlFor='noonServerPort';
 var input=document.createElement('input');input.id='noonServerPort';input.type='number';input.min='1';input.max='65535';input.step='1';input.style.cssText='display:block;box-sizing:border-box;width:100%;padding:12px;margin:10px 0;font:inherit;direction:ltr';ports.appendChild(input);
 var suggest=pn('button',text('اقتراح منفذ متاح','Suggest free port')),save=pn('button',text('حفظ وإعادة تشغيل الخادم','Save and restart server'));
 suggest.type=save.type='button';suggest.style.cssText=save.style.cssText='padding:12px;margin:6px;font:inherit;cursor:pointer';
 var status=pn('p','');status.setAttribute('role','status');status.setAttribute('aria-live','polite');
 function busy(value){suggest.disabled=save.disabled=input.disabled=value;}
 function request(method,path,body,done){busy(true);var x=new XMLHttpRequest();x.open(method,path,true);x.timeout=15000;if(body)x.setRequestHeader('Content-Type','application/json');
  var finished=false;function finish(){if(finished)return;finished=true;busy(false);var result;try{result=JSON.parse(x.responseText);}catch(e){result={error:text('تعذر الاتصال بالخادم','Cannot contact server')};}if(x.status<200||x.status>=300||!result.ok){status.textContent=result.error||text('فشلت العملية','Operation failed');return;}done(result);}
  x.onload=finish;x.onerror=finish;x.ontimeout=finish;x.send(body?JSON.stringify(body):null);
 }
 request('GET','/__settings/port',null,function(result){input.value=result.server_port;current.textContent=text('المنفذ الحالي: ','Current port: ')+result.active_port;});
 suggest.onclick=function(){request('GET','/__settings/port/suggest',null,function(result){input.value=result.server_port;status.textContent='';});};
 save.onclick=function(){var value=Number(input.value);if(!value||value%1||value<1||value>65535){status.textContent=text('أدخل منفذاً من 1 إلى 65535','Enter a port from 1 to 65535');return;}
  status.textContent=text('جارٍ حفظ المنفذ وإعادة تشغيل الخادم…','Saving port and restarting server…');
  var returnPath=window.location.pathname+window.location.search+window.location.hash;
  request('POST','/__settings/port',{server_port:value},function(result){busy(true);
   var saved=text('تم حفظ المنفذ في bin/config.js. تمت إعادة تشغيل الخادم.','Port saved in bin/config.js. The server has restarted.');status.textContent=saved;
   window.alert(saved);
   if(nw)window.top.postMessage({noonContext:true,action:'server-port',value:String(result.server_port),path:returnPath},'*');
   else window.location.replace('http://127.0.0.1:'+result.server_port+returnPath);
  });
 };
 host.appendChild(ports);
 if(window.refreshIndexCustomThumb)window.refreshIndexCustomThumb();
})();
""";}
}
