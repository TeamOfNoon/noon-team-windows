(function(){
 if(navigator.userAgent.indexOf('NoonNW/')<0)return;
 var menu=null;
 function close(){if(menu && menu.parentNode)menu.parentNode.removeChild(menu);menu=null;}
 function send(action,value){window.top.postMessage({noonContext:true,action:action,value:value||''},location.protocol+'//'+location.host);}
 // The shell has file: origin, so postMessage needs '*' while the shell verifies the sender's HTTP origin.
 function command(action,value){window.top.postMessage({noonContext:true,action:action,value:value||''},'*');}
 document.addEventListener('contextmenu',function(e){
  e.preventDefault();close();var link=e.target;while(link && !(link.tagName && link.tagName.toLowerCase()==='a'))link=link.parentNode;
  menu=document.createElement('div');menu.style.cssText='position:fixed;z-index:2147483647;background:#fff;color:#222;border:1px solid #aaa;box-shadow:0 3px 12px #888;padding:4px;font:14px Arial;min-width:240px;direction:ltr';
  function item(label,fn){var b=document.createElement('button');b.type='button';b.textContent=label;b.style.cssText='display:block;width:100%;padding:9px;border:0;text-align:left;background:#fff;color:#222;cursor:pointer';b.onclick=function(){close();fn();};menu.appendChild(b);}
  if(link && link.href){var href=link.href;if(/^(https?:|mailto:)/i.test(href))item('Open link in default browser / فتح الرابط في المتصفح الافتراضي',function(){command('external',href);});item('Copy link / نسخ الرابط',function(){command('copy',href);});}
  var selected=String(window.getSelection());if(selected)item('Copy selection / نسخ النص',function(){command('copy',selected);});
  item('Back / رجوع',function(){history.back();});item('Forward / التالي',function(){history.forward();});item('Reload / تحديث',function(){location.reload();});item('Clear cache and browsing history / مسح التخزين المؤقت وسجل التصفح',function(){command('clear-cache');});item('Clear all data / مسح جميع البيانات',function(){command('clear-all');});item('Inspect / فحص',function(){command('inspect');});item('About App / حول التطبيق',function(){command('about');});
  document.body.appendChild(menu);menu.style.left=Math.max(0,Math.min(e.clientX,innerWidth-menu.offsetWidth))+'px';menu.style.top=Math.max(0,Math.min(e.clientY,innerHeight-menu.offsetHeight))+'px';
 },false);
 document.addEventListener('click',function(e){if(menu && !menu.contains(e.target))close();},true);
 document.addEventListener('keydown',function(e){if(e.keyCode===27)close();},false);
})();