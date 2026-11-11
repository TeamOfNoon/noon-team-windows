import java.io.File;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;

/** Native entry points for local web serving, SQLite and Lucene search/indexing. */
public final class NoonJavaLibrary {
 private static EmbeddedWebHttpServer server;
 private static String lastError="";
 @CEntryPoint(name="noon_java_set_build_type")
 public static int setBuildType(IsolateThread thread,CCharPointer value){String type=CTypeConversion.toJavaString(value);if(!type.equals("MN")&&!type.equals("SN"))return -1;System.setProperty("noon.build.app_type",type);return 0;}
 @CEntryPoint(name="noon_java_set_build_password")
 public static int setBuildPassword(IsolateThread thread,int required,CCharPointer password){System.setProperty("noon.build.password.required",required==0?"false":"true");System.setProperty("noon.build.password",CTypeConversion.toJavaString(password));return 0;}
 @CEntryPoint(name="noon_java_verify_signed_bin")
 public static int verifySignedBin(IsolateThread thread,CCharPointer folder){
  try{File bin=new File(CTypeConversion.toJavaString(folder));SignedBinIntegrity.verifyExternal(bin,new File(bin,"NT/app.dat"));lastError="";return 0;}catch(Throwable e){lastError=e.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_set_boot_file")
 public static int setBootFile(IsolateThread thread,CCharPointer name){try{ExternalDataPaths.setBootFile(CTypeConversion.toJavaString(name));return 0;}catch(Throwable e){lastError=e.toString();return -1;}}
 @CEntryPoint(name="noon_java_set_content_mode")
 public static int setContentMode(IsolateThread thread,int external){System.setProperty("noon.policy.mode",external==0?"embedded":"external");return 0;}
 @CEntryPoint(name="noon_java_set_external_password")
 public static int setExternalPassword(IsolateThread thread,CCharPointer password){System.setProperty("noon.policy.password",CTypeConversion.toJavaString(password));return 0;}
 @CEntryPoint(name="noon_java_set_external_policy")
 public static int setExternalPolicy(IsolateThread thread,int secure,int passwordRequired,int authorized){
  System.setProperty("noon.policy.secure",secure==0?"false":"true");
  System.setProperty("noon.policy.require_password",passwordRequired==0?"false":"true");
  System.setProperty("noon.policy.authorized",authorized==0?"false":"true");
  System.setProperty("noon.external.secure",secure==0?"false":"true");return 0;
 }
 @CEntryPoint(name="noon_java_set_app_role")
 public static int setAppRole(IsolateThread thread,int child,int secure){
  System.setProperty("noon.generated.child",child==0?"false":"true");
  System.setProperty("noon.generated.secure",secure==0?"false":"true");return 0;
 }
 @CEntryPoint(name="noon_java_set_config_startup")
 public static int setConfigStartup(IsolateThread thread,int enabled){System.setProperty("noon.config.startup.enabled",enabled==0?"false":"true");return 0;}
 @CEntryPoint(name="noon_java_set_external_root")
 public static int setExternalRoot(IsolateThread thread,CCharPointer root){
  try{File folder=new File(CTypeConversion.toJavaString(root)).getCanonicalFile();
   if("true".equals(System.getProperty("noon.auto.detect")))folder=ExternalDataPaths.detect(folder);
   System.setProperty("noon.external.root",folder.getPath());return 0;}catch(Throwable e){lastError=e.toString();return -1;}
 }

 @CEntryPoint(name="noon_java_set_auto_detect")
 public static int setAutoDetect(IsolateThread thread,int enabled){System.setProperty("noon.auto.detect",enabled==0?"false":"true");return 0;}
 @CEntryPoint(name="noon_java_set_external_security")
 public static int setExternalSecurity(IsolateThread thread,int secure){
  System.setProperty("noon.external.secure",secure==0?"false":"true");return 0;
 }
 @CEntryPoint(name="noon_java_set_lucene_path")
 public static int setLucenePath(IsolateThread thread,CCharPointer path){
  try{System.setProperty("noon.lucene.override",CTypeConversion.toJavaString(path));return 0;}catch(Throwable e){lastError=e.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_set_sqlite_path")
 public static int setSqlitePath(IsolateThread thread,CCharPointer path){
  try{System.setProperty("noon.sqlite.override",CTypeConversion.toJavaString(path));return 0;}catch(Throwable e){lastError=e.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_set_config_path")
 public static int setConfigPath(IsolateThread thread,CCharPointer path){
  try{System.setProperty("noon.config.path",CTypeConversion.toJavaString(path));return 0;}catch(Throwable e){lastError=e.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_last_error")
 public static int lastError(IsolateThread thread,CCharPointer buffer,int capacity){
  if(capacity<=0)return 0;
  byte[] bytes=lastError.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  int count=Math.min(bytes.length,capacity-1);
  for(int i=0;i<count;i++)buffer.write(i,bytes[i]);buffer.write(count,(byte)0);return count;
 }
 /** Check bitmask: Lucene=1, SQLite=2, JSON=4, jsoup=8, Commons Lang=16; 0=all pass. */
 @CEntryPoint(name="noon_java_save_build_settings")
 public static int saveBuildSettings(IsolateThread thread,CCharPointer config,int port,CCharPointer version,CCharPointer app){
  try{SavedServerPort.saveBuildSettings(new File(CTypeConversion.toJavaString(config)),port,CTypeConversion.toJavaString(version),CTypeConversion.toJavaString(app).isEmpty()?null:new File(CTypeConversion.toJavaString(app)));return 0;}catch(Throwable e){lastError=e.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_check_dependencies")
 public static int checkDependencies(IsolateThread thread){return DependencyCheck.run();}
 @CEntryPoint(name="noon_java_start")
 public static int start(IsolateThread thread,CCharPointer executable,int port){
  try{
   lastError="";
   if(server!=null)return -2;
   if(port<0||port>65535)return -3;
   String path=CTypeConversion.toJavaString(executable);
   System.setProperty("noon.executable.path",new File(path).getCanonicalPath());
   ExternalConfigStartup.configure(new File(path));
   if(System.getProperty("noon.external.root")==null){SignedBinIntegrity.verifyEmbedded();File bin=EmbeddedBin.prepare(new File(path));SignedBinIntegrity.verifyPreparedBin(bin);}
   else {
    File web=new File(System.getProperty("noon.external.root")).getCanonicalFile();
    if(!web.isDirectory() || !ExternalDataPaths.hasHome(web))throw new java.io.IOException("External folder must contain dir.html or www/dir.html: "+web);
    String detectedBin=System.getProperty("noon.external.bin.root");
    File bin=detectedBin==null?null:new File(detectedBin);
    for(File f=web;bin==null&&f!=null;f=f.getParentFile()){
     if(new File(f,"NT/sqlites").isDirectory() || new File(f,"db").isDirectory()){bin=f;break;}
    }
    boolean secure=!"false".equals(System.getProperty("noon.external.secure"));
    String sqliteOverride=System.getProperty("noon.sqlite.override",System.getProperty("noon.sqlite.detected"));
    if(bin==null && !secure && sqliteOverride!=null){
     File databases=new File(sqliteOverride).getCanonicalFile();
     if(!databases.isDirectory())throw new java.io.IOException("SQLite folder does not exist: "+databases);
     bin=databases.getParentFile();
    }
    if(bin==null && secure)throw new java.io.IOException("Secure mode requires signed bin/NT/app.dat");
    if(bin==null){
     // Use the parent's embedded databases when testing only a replacement web folder.
     System.clearProperty("noon.external.root");
     try{SignedBinIntegrity.verifyEmbedded();bin=EmbeddedBin.prepare(new File(path));}
     finally{System.setProperty("noon.external.root",web.getPath());}
    }
    System.setProperty("noon.bin.path",bin.getCanonicalPath());
    if(secure)SignedBinIntegrity.verifyExternal(bin,web);else SignedBinIntegrity.allowUnsignedExternal();
    if(new File(bin,"db").isDirectory()){
     System.setProperty("noon.nt.path",bin.getCanonicalPath());
     System.setProperty("noon.sqlite.path",new File(bin,"db").getCanonicalPath());
    }
   }
   File activeBin=new File(System.getProperty("noon.bin.path"));
   System.setProperty("noon.lucene.path",new File(System.getProperty("noon.external.root")==null?new File(new File(path).getCanonicalFile().getParentFile(),"NT"):activeBin,"lucene").getCanonicalPath());
   ExternalDataPaths.apply();
   server=EmbeddedWebHttpServer.start(port);return server.port();
  }catch(Throwable error){lastError=error.toString();for(Throwable cause=error.getCause();cause!=null&&cause!=error;cause=cause.getCause())lastError+="\nCaused by: "+cause;return error instanceof java.net.BindException?-4:-1;}
 }
 // Builder-only native call: no HTTP server or startup/index synchronization.
 @CEntryPoint(name="noon_java_export_app_data")
 public static int exportAppData(IsolateThread thread,CCharPointer executable,CCharPointer destination){
  try{
   lastError=AppDataExporter.export(new File(CTypeConversion.toJavaString(executable)),new File(CTypeConversion.toJavaString(destination)));
   return 0;
  }catch(Throwable error){lastError=error.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_export_indexed_app_data_v2")
 public static int exportIndexedAppData(IsolateThread thread,CCharPointer executable,CCharPointer destination,int format,int kinds,CCharPointer ranges,int ranks){
  try{
   if(format!=1&&format!=2)throw new IllegalArgumentException("Choose index_js or index_bin");
   if(kinds<1||(kinds&~7)!=0)throw new IllegalArgumentException("Select an index type");
   if(ranks<1||(ranks&~15)!=0)throw new IllegalArgumentException("Select a ranking");
   File folder=new File(CTypeConversion.toJavaString(destination));
   lastError=AppDataExporter.export(new File(CTypeConversion.toJavaString(executable)),folder);
   java.util.Set<String> selected=new java.util.LinkedHashSet<String>();for(String range:CTypeConversion.toJavaString(ranges).split(","))if(!range.trim().isEmpty())selected.add(range.trim().toLowerCase(java.util.Locale.ROOT));
   if(selected.isEmpty())throw new IllegalArgumentException("Select at least one available range");
   lastError+="\n"+CompactIndexExporter.generate(new PlainSqliteCatalog(folder,new File(folder,"db")),new File(folder,"app"),format==1,kinds,selected,ranks);
   return 0;
  }catch(Throwable error){lastError=error.toString();return -1;}
 }
 @CEntryPoint(name="noon_java_suggest_port")
 public static int suggestPort(IsolateThread thread){try{return SavedServerPort.suggest();}catch(Throwable e){lastError=e.toString();return -1;}}
 @CEntryPoint(name="noon_java_read_saved_port")
 public static int readSavedPort(IsolateThread thread,CCharPointer exe,int fallback){try{return SavedServerPort.read(new File(CTypeConversion.toJavaString(exe)),fallback);}catch(Throwable e){lastError=e.toString();return -1;}}
 @CEntryPoint(name="noon_java_save_port")
 public static int savePort(IsolateThread thread,CCharPointer exe,int port){try{SavedServerPort.save(new File(CTypeConversion.toJavaString(exe)),port);return 0;}catch(Throwable e){lastError=e.toString();return -1;}}
 @CEntryPoint(name="noon_java_current_port")
 public static int currentPort(IsolateThread thread){return server==null?0:server.port();}
 @CEntryPoint(name="noon_java_stop")
 public static int stop(IsolateThread thread){
  try{if(server!=null){server.close();server=null;}return 0;}catch(Throwable error){return -1;}
 }
}
