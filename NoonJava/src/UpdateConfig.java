import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Embedded web defaults, embedded desktop config, external config, runtime state.
 * Configuration is merged in memory; neither source is rewritten at startup.
 */
final class UpdateConfig {
 private static File findConfig(File nt)throws Exception {
  String configured=System.getProperty("noon.config.path");
  if(configured!=null&&!configured.isEmpty())return new File(configured).getCanonicalFile();
  String exe=System.getProperty("noon.executable.path");
  return exe!=null?new File(new File(new File(exe).getCanonicalFile().getParentFile(),"bin"),"config.js"):
   new File(nt.getCanonicalFile().getParentFile(),"config.js");
 }
 static JSONObject merge(JSONObject base,JSONObject overrides){
  JSONObject result=new JSONObject(base.toString());
  for(String key:overrides.keySet()){
   Object value=overrides.get(key);
   if(value instanceof JSONObject && result.optJSONObject(key)!=null)
    value=merge(result.getJSONObject(key),(JSONObject)value);
   result.put(key,value);
  }
  return result;
 }
 public static JSONObject loadObject(File nt)throws Exception{
  byte[] bytes;
  boolean embedded=System.getProperty("noon.executable.path")!=null || System.getProperty("noon.resource.jar")!=null || System.getProperty("noon.external.root")!=null;
  if(embedded){
   // Raw EXE payload uses www/ for bin/app/www; never use cached extracted config.
   bytes=EmbeddedWebResources.read("www/res/config.js");
   if(bytes==null && System.getProperty("noon.external.root")!=null)bytes=EmbeddedWebResources.read("res/config.js");
   if(bytes==null)throw new java.io.IOException("Missing app res/config.js");
  }else{
   File web=new File(nt.getCanonicalFile().getParentFile(),"app/www/res/config.js");
   bytes=web.isFile()?Files.readAllBytes(web.toPath()):null;
  }
  JSONObject base=bytes==null?new JSONObject():AppIndexConfig.parse(new String(bytes,StandardCharsets.UTF_8));
  if(embedded){
   // Read the original packaged bin config, not its mutable extracted copy.
   byte[] desktop=EmbeddedWebResources.read("__bin/config.js");
   if(desktop!=null)base=merge(base,AppIndexConfig.parse(new String(desktop,StandardCharsets.UTF_8)));
  }
  String binPath=System.getProperty("noon.bin.path");
  if(System.getProperty("noon.external.root")!=null && binPath!=null){File local=new File(binPath,"config.js");if(local.isFile())base=merge(base,AppIndexConfig.parse(new String(Files.readAllBytes(local.toPath()),StandardCharsets.UTF_8)));}
  File external=findConfig(nt);
  if(external.isFile())base=merge(base,AppIndexConfig.parse(new String(Files.readAllBytes(external.toPath()),StandardCharsets.UTF_8)));
  return ExternalConfigStartup.withoutCredentials(base);
 }
 public static String loadString(File nt,String key,String def)throws Exception{
  String value=loadObject(nt).optString(key,def);
  return value==null||value.trim().isEmpty()?def:value.trim();
 }
 public static String loadSearchIndexType(File nt)throws Exception{
  String value=loadString(nt,"search_index_type",null);
  if(value==null)value=loadString(nt,"search_engin_type",null);
  if(value==null)value=loadString(nt,"search_engine_type",null);
  if(value==null)value=loadString(nt,"search_engn_type",null);
  if(value==null)return "json_js";
  value=value.toLowerCase(java.util.Locale.ROOT);
  if("lucen".equals(value)||"lucy".equals(value))value="lucene";
  if("js".equals(value)||"json".equals(value)||"json-js".equals(value))value="json_js";
  if("bin".equals(value)||"binary".equals(value)||"json-bin".equals(value))value="json_bin";
  return "lucene".equals(value)||"json_bin".equals(value)?value:"json_js";
 }
 public static boolean loadBoolean(File nt,String key,boolean def)throws Exception{
  Object v=loadObject(nt).opt(key);
  if(v==null||v==JSONObject.NULL)return def;
  if(v instanceof Boolean)return (Boolean)v;
  String x=String.valueOf(v).trim();
  if("true".equalsIgnoreCase(x)||"1".equals(x)||"yes".equalsIgnoreCase(x)||"on".equalsIgnoreCase(x))return true;
  if("false".equalsIgnoreCase(x)||"0".equals(x)||"no".equalsIgnoreCase(x)||"off".equalsIgnoreCase(x))return false;
  return def;
 }
 public static long loadCacheUpdate(File nt)throws Exception{return loadObject(nt).optLong("cache_update",0);}
}
