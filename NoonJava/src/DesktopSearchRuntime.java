import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;
import org.json.JSONObject;

/** Donor Lucene/SQLite services attached to the existing native launcher's HTTP server. */
final class DesktopSearchRuntime implements AutoCloseable {
 final File nt, luceneDir;
 final LuceneIndexManager indexes;
 final LuceneSearchService search;
 private boolean startupStarted, startupBypass;
 DesktopSearchRuntime() throws Exception {
  File bin=new File(System.getProperty("noon.bin.path"));
  nt=new File(System.getProperty("noon.nt.path",new File(bin,"NT").getPath())).getCanonicalFile();
  luceneDir=new File(System.getProperty("noon.lucene.path",new File(new File(System.getProperty("noon.executable.path")).getCanonicalFile().getParentFile(),"NT/lucene").getPath())).getCanonicalFile();
  indexes=new LuceneIndexManager(nt,luceneDir);
  search=new LuceneSearchService(luceneDir,nt);

 }
 // Re-entering the server startup screen reruns checks, but never duplicates a running update.
 synchronized void reopenStartup()throws Exception{
  if(!new JSONObject(indexes.snapshotJson()).optBoolean("busy")){startupStarted=false;startupBypass=false;}
 }
 synchronized JSONObject startupStatus(String home)throws Exception{
  JSONObject status=new JSONObject(indexes.snapshotJson());
  String phase=!startupStarted?"waiting":status.optBoolean("busy")?"running":!status.optString("error").isEmpty()?"failed":status.optBoolean("stopped")?"stopped":"done";
  status.put("phase",startupBypass?"done":phase).put("home",home).put("checking_databases",indexes.checkingDatabases()).put("databases_checked",indexes.databasesChecked()).put("no_indexes",indexes.noIndexesToSync());
  return status;
 }
 synchronized JSONObject beginStartup(String home)throws Exception{
  JSONObject status=startupStatus(home);
  if(!startupStarted||"failed".equals(status.getString("phase"))||"stopped".equals(status.getString("phase"))){
   startupStarted=true;startupBypass=false;indexes.synchronizeExistingOnLaunch();
  }
  return startupStatus(home);
 }
 synchronized boolean startupReady()throws Exception{
  return "done".equals(startupStatus("/").getString("phase"));
 }
 synchronized JSONObject continueStartup(String home)throws Exception{
  JSONObject status=startupStatus(home);
  if(!startupStarted||status.optBoolean("busy"))throw new IOException("Wait for the startup check or stop the update first");
  startupBypass=true;return startupStatus(home);
 }
 String mode() throws Exception {return UpdateConfig.loadSearchIndexType(nt);}
 String engine() throws Exception {String m=mode();return "lucene".equals(m)?m:("json_bin".equals(m)?"bin":"js");}
 boolean handle(HttpExchange ex,String path) throws Exception {
  if(!path.startsWith("/__lucene/")&&!path.startsWith("/__search/"))return false;
  String method=ex.getRequestMethod();
  boolean mutation=path.equals("/__lucene/build")||path.equals("/__lucene/stop")||path.equals("/__lucene/delete");
  if(!(mutation?"POST":"GET").equals(method)){reply(ex,405,new JSONObject().put("ok",false).put("error",mutation?"POST required":"GET required").toString());return true;}
  if(mutation){
   // A web page on another origin must not trigger local index mutations.
   String origin=ex.getRequestHeaders().getFirst("Origin");
   String host=ex.getRequestHeaders().getFirst("Host");
   if(origin!=null&&!origin.equals("http://"+host)){reply(ex,403,"{\"ok\":false,\"error\":\"Local origin required\"}");return true;}
  }
  String query=ex.getRequestURI().getRawQuery(), result;
  int status=200;
  if(path.equals("/__lucene/forms"))result=indexes.formsJson();
  else if(path.equals("/__lucene/status"))result=indexes.statusJson();
  else if(path.equals("/__lucene/build")){result=indexes.startBuild(param(query,"forms"));status=result.contains("\"ok\":true")?202:409;}
  else if(path.equals("/__lucene/delete")){result=indexes.startDelete(param(query,"forms"));status=result.contains("\"ok\":true")?202:409;}
  else if(path.equals("/__lucene/stop")){result=indexes.stopBuild();status=result.contains("\"ok\":true")?200:409;}
  else if(path.equals("/__search/config"))result=new JSONObject().put("ok",true).put("search_index_type",mode()).put("search_engin_type",engine()).toString();
  else if(path.equals("/__search/cancel")){search.cancelSearch(param(query,"token"));result="{\"ok\":true}";}
  else if(path.equals("/__search/lucene"))result=search.searchCancellable(param(query,"token"),param(query,"q"),param(query,"kind"),param(query,"mode"),param(query,"sort"),number(param(query,"page"),1),number(param(query,"size"),10),param(query,"forms"));
  else{status=404;result="{\"ok\":false,\"error\":\"Unknown search endpoint\"}";}
  reply(ex,status,result);return true;
 }
 byte[] resource(String path) throws Exception {
  if(path.startsWith("/__bin/"))return null;
  String relative=path;while(relative.startsWith("/"))relative=relative.substring(1);
  byte[] data=DynamicJs.render(nt,relative);
  if(data==null)data=EmbeddedWebResources.read(path);
  if(data==null&&path.endsWith("/res/SW.js"))data=SearchSnippetPreview.defaultStopWords().getBytes(StandardCharsets.UTF_8);
  if(data==null)return null;
  if(path.endsWith("/res/config.js"))return SearchIndexConfig.withRuntimeState(data,nt,luceneDir,mode());
  if(path.endsWith("/res/cache_update.js"))return ("window.cache_update="+UpdateConfig.loadCacheUpdate(nt)+";window.search_index_type="+JSONObject.quote(mode())+";window.search_engin_type="+JSONObject.quote(engine())+";").getBytes(StandardCharsets.UTF_8);
  if(path.endsWith("/res/index/pages/settings/app.js"))return (new String(data,StandardCharsets.UTF_8)+"\n"+SettingsPage.script()).getBytes(StandardCharsets.UTF_8);
  return WebRuntimeAssets.transform(path,data);
 }
 static String param(String query,String key) throws Exception {
  if(query!=null)for(String part:query.split("&")){int p=part.indexOf('=');if(key.equals(p<0?part:part.substring(0,p)))return URLDecoder.decode(p<0?"":part.substring(p+1),"UTF-8");}return "";
 }
 static int number(String value,int fallback){try{return Integer.parseInt(value);}catch(Exception e){return fallback;}}
 static void reply(HttpExchange ex,int status,String json) throws IOException {
  byte[] data=json.getBytes(StandardCharsets.UTF_8);
  ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
  ex.getResponseHeaders().set("Cache-Control","no-store");
  ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
  if("HEAD".equals(ex.getRequestMethod())){ex.sendResponseHeaders(status,-1);return;}
  ex.sendResponseHeaders(status,data.length);ex.getResponseBody().write(data);
 }
 public void close() throws Exception {indexes.close();}
}
