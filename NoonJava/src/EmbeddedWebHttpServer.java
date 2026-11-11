import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

/** Embedded web bytes with donor SQLite/Lucene services on loopback only. */
public final class EmbeddedWebHttpServer implements AutoCloseable {
 private volatile HttpServer server;
 private final java.util.List<HttpServer> retired=new java.util.ArrayList<>();
 private final ExecutorService pool;
 private final String home;
 private final DesktopSearchRuntime data;
 private final String boot=UUID.randomUUID().toString();
 private EmbeddedWebHttpServer(int port)throws IOException{
  SignedBinIntegrity.requireInitialized();
  if(!EmbeddedWebResources.available())throw new IOException("Web files unavailable. Check external folder or embedded payload.");
  String found=null;
  for(String p:new String[]{ExternalDataPaths.bootFile(),"www/"+ExternalDataPaths.bootFile(),"dir.html","www/dir.html"})if(EmbeddedWebResources.contains(p)){found=p;break;}
  if(found==null)throw new IOException("Embedded application has no home page");
  home=found;
  try{data="true".equals(System.getProperty("noon.external.static"))?null:new DesktopSearchRuntime();}catch(Exception e){throw new IOException("Initialize Lucene/SQLite services",e);}
  // Keep the requested public port stable for ordinary browsers.
  try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),64);}
  catch(BindException busy){
   try{if(data!=null)data.close();}catch(Exception ignored){}
   throw new BindException("Local port "+port+" is already in use. Close the other program or choose another port. / المنفذ المحلي مستخدم بالفعل. أغلق البرنامج الآخر أو اختر منفذاً آخر.");
  }
  pool=Executors.newFixedThreadPool(4);
  server.createContext("/",new HttpHandler(){public void handle(HttpExchange ex)throws IOException{serve(ex);}});
  server.setExecutor(pool);
 }
 public static EmbeddedWebHttpServer start(int port)throws IOException{
  EmbeddedWebHttpServer result=new EmbeddedWebHttpServer(port);
  try{result.server.start();return result;}catch(RuntimeException e){result.close();throw e;}
 }
 private String homeUrl(){try{return new java.net.URI(null,null,"/"+home,null).toASCIIString();}catch(java.net.URISyntaxException invalid){throw new IllegalStateException(invalid);}}
 public int port(){return server.getAddress().getPort();}
 public synchronized void close(){server.stop(0);for(HttpServer old:retired)old.stop(0);retired.clear();pool.shutdownNow();try{pool.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}try{if(data!=null)data.close();}catch(Exception e){throw new IllegalStateException("Stop Lucene worker",e);}}
 private synchronized void portSettings(HttpExchange ex,String path)throws Exception{
  String method=ex.getRequestMethod();
  File exe=new File(System.getProperty("noon.executable.path"));
  if("GET".equals(method)){
   int value=path.endsWith("/suggest")?SavedServerPort.suggest():SavedServerPort.read(exe,port());
   DesktopSearchRuntime.reply(ex,200,new org.json.JSONObject().put("ok",true).put("active_port",port()).put("server_port",value).toString());return;
  }
  if(!"POST".equals(method)||!path.equals("/__settings/port")){DesktopSearchRuntime.reply(ex,405,"{\"ok\":false,\"error\":\"Method not allowed\"}");return;}
  String origin=ex.getRequestHeaders().getFirst("Origin"),host=ex.getRequestHeaders().getFirst("Host");
  if(origin==null||!origin.equals("http://"+host)){DesktopSearchRuntime.reply(ex,403,"{\"ok\":false,\"error\":\"Local origin required\"}");return;}
  String content=ex.getRequestHeaders().getFirst("Content-Type");
  if(content==null||!content.toLowerCase(Locale.ROOT).startsWith("application/json")){DesktopSearchRuntime.reply(ex,415,"{\"ok\":false,\"error\":\"JSON required\"}");return;}
  int chosen;
  try{
   byte[] body=ex.getRequestBody().readNBytes(4097);if(body.length>4096)throw new IllegalArgumentException();
   Object value=new org.json.JSONObject(new String(body,StandardCharsets.UTF_8)).get("server_port");
   if(!(value instanceof Number))throw new IllegalArgumentException();
   double number=((Number)value).doubleValue();chosen=((Number)value).intValue();
   if(number!=chosen||chosen<1||chosen>65535)throw new IllegalArgumentException();
  }catch(Exception invalid){DesktopSearchRuntime.reply(ex,400,"{\"ok\":false,\"error\":\"Enter a port from 1 to 65535 / أدخل منفذاً من 1 إلى 65535\"}");return;}
  HttpServer next=null,old=server;
  if(chosen!=port()){
   try{next=HttpServer.create(new InetSocketAddress("127.0.0.1",chosen),64);}
   catch(BindException busy){DesktopSearchRuntime.reply(ex,409,"{\"ok\":false,\"error\":\"Port is busy. Choose another port / المنفذ مستخدم، اختر منفذاً آخر\"}");return;}
   next.createContext("/",new HttpHandler(){public void handle(HttpExchange request)throws IOException{serve(request);}});next.setExecutor(pool);
  }
  try{SavedServerPort.save(exe,chosen);if(next!=null){next.start();server=next;retired.add(old);}}
  catch(Exception failure){if(next!=null)next.stop(0);throw failure;}
  DesktopSearchRuntime.reply(ex,200,new org.json.JSONObject().put("ok",true).put("server_port",chosen).put("url","http://127.0.0.1:"+chosen+"/__settings/").toString());
  if(next!=null){
   // Finish this response before stopping the old listener; retain the data/index worker.
   Thread stop=new Thread(()->{try{Thread.sleep(1500);}catch(InterruptedException ignored){}old.stop(1);synchronized(EmbeddedWebHttpServer.this){retired.remove(old);}},"Noon-old-port");stop.setDaemon(true);stop.start();
  }
 }
 private static final String NW_CONTEXT_SCRIPT="(function(){\n if(navigator.userAgent.indexOf('NoonNW/')<0)return;\n var menu=null;\n function close(){if(menu && menu.parentNode)menu.parentNode.removeChild(menu);menu=null;}\n function send(action,value){window.top.postMessage({noonContext:true,action:action,value:value||''},location.protocol+'//'+location.host);}\n // The shell has file: origin, so postMessage needs '*' while the shell verifies the sender's HTTP origin.\n function command(action,value){window.top.postMessage({noonContext:true,action:action,value:value||''},'*');}\n document.addEventListener('contextmenu',function(e){\n  e.preventDefault();close();var link=e.target;while(link && !(link.tagName && link.tagName.toLowerCase()==='a'))link=link.parentNode;\n  menu=document.createElement('div');menu.style.cssText='position:fixed;z-index:2147483647;background:#fff;color:#222;border:1px solid #aaa;box-shadow:0 3px 12px #888;padding:4px;font:14px Arial;min-width:240px;direction:ltr';\n  function item(label,fn){var b=document.createElement('button');b.type='button';b.textContent=label;b.style.cssText='display:block;width:100%;padding:9px;border:0;text-align:left;background:#fff;color:#222;cursor:pointer';b.onclick=function(){close();fn();};menu.appendChild(b);}\n  if(link && link.href){var href=link.href;if(/^(https?:|mailto:)/i.test(href))item('Open link in default browser / \u0641\u062a\u062d \u0627\u0644\u0631\u0627\u0628\u0637 \u0641\u064a \u0627\u0644\u0645\u062a\u0635\u0641\u062d \u0627\u0644\u0627\u0641\u062a\u0631\u0627\u0636\u064a',function(){command('external',href);});item('Copy link / \u0646\u0633\u062e \u0627\u0644\u0631\u0627\u0628\u0637',function(){command('copy',href);});}\n  var selected=String(window.getSelection());if(selected)item('Copy selection / \u0646\u0633\u062e \u0627\u0644\u0646\u0635',function(){command('copy',selected);});\n  item('Back / \u0631\u062c\u0648\u0639',function(){history.back();});item('Forward / \u0627\u0644\u062a\u0627\u0644\u064a',function(){history.forward();});item('Reload / \u062a\u062d\u062f\u064a\u062b',function(){location.reload();});item('Clear cache and browsing history / \u0645\u0633\u062d \u0627\u0644\u062a\u062e\u0632\u064a\u0646 \u0627\u0644\u0645\u0624\u0642\u062a \u0648\u0633\u062c\u0644 \u0627\u0644\u062a\u0635\u0641\u062d',function(){command('clear-cache');});item('Clear all data / \u0645\u0633\u062d \u062c\u0645\u064a\u0639 \u0627\u0644\u0628\u064a\u0627\u0646\u0627\u062a',function(){command('clear-all');});item('Inspect / \u0641\u062d\u0635',function(){command('inspect');});item('About App / \u062d\u0648\u0644 \u0627\u0644\u062a\u0637\u0628\u064a\u0642',function(){command('about');});\n  document.body.appendChild(menu);menu.style.left=Math.max(0,Math.min(e.clientX,innerWidth-menu.offsetWidth))+'px';menu.style.top=Math.max(0,Math.min(e.clientY,innerHeight-menu.offsetHeight))+'px';\n },false);\n document.addEventListener('click',function(e){if(menu && !menu.contains(e.target))close();},true);\n document.addEventListener('keydown',function(e){if(e.keyCode===27)close();},false);\n})();";
 private void serve(HttpExchange ex)throws IOException{
  boolean head="HEAD".equals(ex.getRequestMethod());
  try{
   SignedBinIntegrity.checkRuntime();
   String startupPath=ex.getRequestURI().getPath();
   if(startupPath.equals("/__settings/port")||startupPath.equals("/__settings/port/suggest")){portSettings(ex,startupPath);return;}
   if(data==null && startupPath.startsWith("/__startup/")){
    ex.getResponseHeaders().set("Location",homeUrl());ex.sendResponseHeaders(302,-1);return;
   }
   if(startupPath.startsWith("/__startup/")){
    if(startupPath.equals("/__startup/start")||startupPath.equals("/__startup/continue")){
     if(!"POST".equals(ex.getRequestMethod())){DesktopSearchRuntime.reply(ex,405,"{\"ok\":false,\"error\":\"POST required\"}");return;}
     String origin=ex.getRequestHeaders().getFirst("Origin"),host=ex.getRequestHeaders().getFirst("Host");
     if(origin!=null&&!origin.equals("http://"+host)){DesktopSearchRuntime.reply(ex,403,"{\"ok\":false,\"error\":\"Local origin required\"}");return;}
     org.json.JSONObject status=startupPath.endsWith("/start")?data.beginStartup(homeUrl()):data.continueStartup(homeUrl());
     DesktopSearchRuntime.reply(ex,200,status.toString());return;
    }
    if(!head&&!"GET".equals(ex.getRequestMethod())){DesktopSearchRuntime.reply(ex,405,"{\"ok\":false,\"error\":\"GET/HEAD required\"}");return;}
    if(startupPath.equals("/__startup/status")){DesktopSearchRuntime.reply(ex,200,data.startupStatus(homeUrl()).toString());return;}
    if(startupPath.equals("/__startup/")||startupPath.equals("/__startup/index.html")){data.reopenStartup();send(ex,200,"text/html; charset=utf-8",StartupPage.html(),head);return;}
    if(startupPath.equals("/__startup/logo.png")){send(ex,200,"image/png",StartupPage.logo(),head);return;}
    send(ex,404,"text/plain; charset=utf-8",new byte[0],head);return;
   }
   if(startupPath.equals("/__settings/")||startupPath.equals("/__settings/index.html")||startupPath.equals("/www/index/settings.html")){
    if(!head&&!"GET".equals(ex.getRequestMethod())){ex.getResponseHeaders().set("Allow","GET, HEAD");DesktopSearchRuntime.reply(ex,405,"{\"ok\":false,\"error\":\"GET/HEAD required\"}");return;}
    send(ex,200,"text/html; charset=utf-8",SettingsPage.html(),head);return;
   }
   if(data!=null && data.handle(ex,ex.getRequestURI().getPath()))return;
   if(!head&&!"GET".equals(ex.getRequestMethod())){ex.getResponseHeaders().set("Allow","GET, HEAD");send(ex,405,"text/plain; charset=utf-8","GET/HEAD only".getBytes(StandardCharsets.UTF_8),false);return;}
   // getPath decodes percent escapes without turning literal '+' into a space.
   String path=ex.getRequestURI().getPath();
   if(path.indexOf('\0')>=0||path.indexOf('\\')>=0||Arrays.asList(path.split("/")).contains("..")){send(ex,400,"text/plain; charset=utf-8","Unsafe path".getBytes(StandardCharsets.UTF_8),head);return;}
   if("/__session/boot".equals(path)){send(ex,200,"application/json; charset=utf-8",("{\"ok\":true,\"boot_id\":\""+boot+"\",\"mode\":\"resources\"}").getBytes(StandardCharsets.UTF_8),head);return;}
   if("/".equals(path)||(type(path).startsWith("text/html")&&data!=null&&!data.startupReady())){
    ex.getResponseHeaders().set("Location",("/".equals(path)&&(data==null||data.startupReady()))?homeUrl():"/__startup/");ex.getResponseHeaders().set("Cache-Control","no-store");ex.sendResponseHeaders(302,-1);return;
   }
   if("/__noon/url-safety.js".equals(path)){send(ex,200,"application/javascript; charset=utf-8",LocalUrlSafety.script(),head);return;}
   if("/__noon/context.js".equals(path)){send(ex,200,"application/javascript; charset=utf-8",NW_CONTEXT_SCRIPT.getBytes(StandardCharsets.UTF_8),head);return;}
   byte[] bytes=data==null?EmbeddedWebResources.read(path):data.resource(path);
   if(bytes==null){send(ex,404,"text/plain; charset=utf-8","Not found".getBytes(StandardCharsets.UTF_8),head);return;}
   if(type(path).startsWith("text/html")){
    String html=LocalUrlSafety.inject(new String(bytes,StandardCharsets.UTF_8));String script="<script src=\"/__noon/context.js\"></script>";
    int close=html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");html=close>=0?html.substring(0,close)+script+html.substring(close):html+script;bytes=html.getBytes(StandardCharsets.UTF_8);
   }
   send(ex,200,type(path),bytes,head);
  }catch(SecurityException tampered){
   String path=ex.getRequestURI().getPath();
   if(path.equals("/__startup/start")||path.equals("/__startup/status")||path.equals("/__startup/continue")||path.startsWith("/__lucene/")||path.startsWith("/__search/")||path.startsWith("/__settings/port")||path.equals("/__session/boot"))
    DesktopSearchRuntime.reply(ex,403,new org.json.JSONObject().put("ok",false).put("fatal",true).put("kind","integrity").put("error",tampered.getMessage()).toString());
   else send(ex,403,"text/html; charset=utf-8",SignedBinIntegrity.errorHtml(),head);
  }
  catch(Exception e){if(ex.getRequestURI().getPath().startsWith("/__"))DesktopSearchRuntime.reply(ex,500,new org.json.JSONObject().put("ok",false).put("error",e.toString()).toString());else send(ex,500,"text/plain; charset=utf-8",e.toString().getBytes(StandardCharsets.UTF_8),head);}
  finally{ex.close();}
 }
 private static void send(HttpExchange ex,int code,String type,byte[] data,boolean head)throws IOException{
  ex.getResponseHeaders().set("Content-Type",type);ex.getResponseHeaders().set("Cache-Control","no-store");
  ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
  if(head){ex.getResponseHeaders().set("Content-Length",String.valueOf(data.length));ex.sendResponseHeaders(code,-1);return;}
  if(data.length==0){ex.sendResponseHeaders(code,-1);return;}
  ex.sendResponseHeaders(code,data.length);ex.getResponseBody().write(data);
 }
 private static String type(String path){
  String p=path.toLowerCase(Locale.ROOT);
  if(p.endsWith(".html")||p.endsWith(".htm"))return "text/html; charset=utf-8";
  if(p.endsWith(".js"))return "application/javascript; charset=utf-8";
  if(p.endsWith(".css"))return "text/css; charset=utf-8";
  if(p.endsWith(".json"))return "application/json; charset=utf-8";
  if(p.endsWith(".svg"))return "image/svg+xml";
  if(p.endsWith(".png"))return "image/png";
  if(p.endsWith(".jpg")||p.endsWith(".jpeg"))return "image/jpeg";
  if(p.endsWith(".gif"))return "image/gif";
  if(p.endsWith(".webp"))return "image/webp";
  if(p.endsWith(".ico"))return "image/x-icon";
  if(p.endsWith(".woff"))return "font/woff";
  if(p.endsWith(".woff2"))return "font/woff2";
  if(p.endsWith(".ttf"))return "font/ttf";
  if(p.endsWith(".xml"))return "application/xml; charset=utf-8";
  if(p.endsWith(".txt"))return "text/plain; charset=utf-8";
  if(p.endsWith(".pdf"))return "application/pdf";
  if(p.endsWith(".mp3"))return "audio/mpeg";
  if(p.endsWith(".mp4"))return "video/mp4";
  return "application/octet-stream";
 }
}
