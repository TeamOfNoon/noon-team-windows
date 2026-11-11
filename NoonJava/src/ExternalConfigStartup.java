import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Optional password-authorized bin beside the EXE. Never evaluates JavaScript. */
final class ExternalConfigStartup {
 static boolean configure(File executable)throws Exception{
  boolean embedded="embedded".equals(System.getProperty("noon.policy.mode"));
  boolean explicit=System.getProperty("noon.external.root")!=null && !"true".equals(System.getProperty("noon.config.startup.enabled"));
  boolean requirePassword=Boolean.parseBoolean(System.getProperty("noon.policy.require_password","true"));
  boolean authorized=Boolean.parseBoolean(System.getProperty("noon.policy.authorized","false"));
  File exeFolder=executable.getCanonicalFile().getParentFile();
  String configured=System.getProperty("noon.config.path");
  File config=configured==null||configured.isEmpty()?new File(exeFolder,"bin/config.js"):new File(configured);
  if(!config.isAbsolute())config=new File(exeFolder,configured);
  if(config.isDirectory())config=new File(config,"config.js");
  config=config.getCanonicalFile();
  File defaultBin=new File(exeFolder,"bin").getCanonicalFile();
  if(!config.isFile()&&!config.getParentFile().equals(defaultBin)){
   File fallback=new File(defaultBin,"config.js");if(fallback.isFile())config=fallback;
  }
  File bin=config.getParentFile();
  JSONObject settings=new JSONObject();
  if(config.isFile()){
   if(config.length()>4*1024*1024)throw new IOException("External config is too large");
   String text=new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8);
   if(text.startsWith("\uFEFF"))text=text.substring(1);
   try{settings=AppIndexConfig.parse(text);}catch(Exception invalid){throw new IOException("Cannot parse external config: "+config,invalid);}
  }
  if(settings.has("boot_file")){Object boot=settings.opt("boot_file");if(!(boot instanceof String))throw new IOException("boot_file must be a file name");ExternalDataPaths.setBootFile((String)boot);}
  // Embedded payload remains the fallback only when no external app is available.
  if(embedded&&System.getProperty("noon.external.root")==null&&!available(bin,settings)&&(bin.equals(defaultBin)||!available(defaultBin,settings)))return false;
  Object secureValue=settings.opt("secure");
  if(secureValue!=null&&!(secureValue instanceof Boolean))throw new IOException("secure must be true or false");
  boolean secure=Boolean.parseBoolean(System.getProperty("noon.policy.secure","false"))||Boolean.TRUE.equals(secureValue);
  boolean hasPassword=settings.has("pass")||settings.has("external_password")||settings.has("password");
  Object password=settings.has("pass")?settings.opt("pass"):settings.has("external_password")?settings.opt("external_password"):settings.opt("password");
  authorized=authorized||password instanceof String&&System.getProperty("noon.policy.password","noon").equals(password);
  boolean selectsExternal=secure||settings.has("external_content_type")||settings.has("external_app_path");
  if(requirePassword&&!authorized){
   if(hasPassword||System.getProperty("noon.external.root")!=null||selectsExternal)throw new SecurityException("Access denied.");
   return false;
  }
  if(explicit&&!secure)return false;
  if(!bin.equals(defaultBin)&&!available(bin,settings)){
   if(available(defaultBin,settings))bin=defaultBin;
  }
  if(!selectsExternal){
   boolean available=false;
   for(String relative:new String[]{"NT/app.dat","app","app.dat",""}){
    File candidate=new File(bin,relative);if(ExternalDataPaths.hasHome(candidate)){available=true;break;}
   }
   if(!available)return false;
  }
  Object type=settings.opt("external_content_type");
  if(type!=null && !(type instanceof String))throw new IOException("external_content_type must be default, static, or auto");
  String contentType=type==null?"auto":(String)type;
  Object folder=settings.opt("external_app_path");
  if(folder!=null && (!(folder instanceof String)||((String)folder).trim().isEmpty()))throw new IOException("external_app_path must be a folder path");
  if(secure){
   File signedWeb=new File(bin,"NT/app.dat").getCanonicalFile();
   if(folder!=null){File requested=new File((String)folder);if(!requested.isAbsolute())requested=new File(bin,(String)folder);requested=requested.getCanonicalFile();if(requested.getName().equals("app.dat")&&requested.getParentFile()!=null&&requested.getParentFile().getName().equals("NT")){bin=requested.getParentFile().getParentFile();signedWeb=requested;}}
   if(contentType.equals("static"))throw new IOException("Secure mode requires the signed default bin layout");
   if(folder!=null){File selected=new File((String)folder);if(!selected.isAbsolute())selected=new File(bin,(String)folder);if(!selected.getCanonicalFile().equals(signedWeb))throw new IOException("Secure mode requires NT/app.dat");}
   if(explicit&&!new File(System.getProperty("noon.external.root")).getCanonicalFile().equals(signedWeb))throw new SecurityException("Secure mode requires the signed default bin layout");
   contentType="default";folder="NT/app.dat";
  }
  File web=ExternalDataPaths.detect(bin,contentType,(String)folder);
  System.setProperty("noon.config.path",config.getCanonicalPath());
  System.setProperty("noon.external.root",web.getPath());
  System.setProperty("noon.external.secure",secure?"true":"false");
  if(config.isFile())System.setProperty("noon.external.auth.config",config.getCanonicalPath());
  return true;
 }
 static boolean available(File root,JSONObject settings)throws IOException{
  String custom=settings.optString("external_app_path","");
  String[] folders=custom.isEmpty()?new String[]{"NT/app.dat","app","app.dat",""}:new String[]{custom};
  for(String relative:folders){File f=new File(relative);if(!f.isAbsolute())f=new File(root,relative);if(ExternalDataPaths.hasHome(f))return true;}
  return false;
 }
 static JSONObject withoutCredentials(JSONObject config){
  JSONObject clean=new JSONObject(config.toString());clean.remove("pass");clean.remove("external_password");clean.remove("password");return clean;
 }
 static byte[] webConfig(byte[] bytes)throws IOException{
  try{return ("window.NT_CONFIG = "+withoutCredentials(AppIndexConfig.parse(new String(bytes,StandardCharsets.UTF_8))).toString(2)+";\n").getBytes(StandardCharsets.UTF_8);}
  catch(Exception invalid){throw new IOException("Cannot serve external configuration",invalid);}
 }
}
