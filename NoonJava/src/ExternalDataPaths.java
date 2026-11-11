import java.io.File;
import java.io.IOException;

/** Explicit developer data paths; the launcher has already checked -pass. */
final class ExternalDataPaths {
 static void setBootFile(String name)throws IOException{
  if(name==null||name.isEmpty()||name.indexOf(':')>=0||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf('?')>=0||name.indexOf('#')>=0||name.chars().anyMatch(c->c<32))throw new IOException("Invalid boot file name");
  for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Invalid boot file name");
  System.setProperty("noon.boot.file",name);
 }
 static String bootFile(){return System.getProperty("noon.boot.file","dir.html");}
 static boolean hasHome(File folder){String name=bootFile();return new File(folder,name).isFile()||new File(folder,"www/"+name).isFile()||new File(folder,"dir.html").isFile()||new File(folder,"www/dir.html").isFile();}

 static File detect(File supplied)throws IOException{return detect(supplied,"auto",null);}
 static File detect(File supplied,String contentType,String appPath)throws IOException{
  if(!contentType.equals("auto")&&!contentType.equals("default")&&!contentType.equals("static"))throw new IOException("external_content_type must be default, static, or auto");
  File base=supplied.getCanonicalFile();
  if(!base.isDirectory())throw new IOException("Auto-detect folder does not exist: "+base);
  File web=null;
  String[] candidates=appPath!=null?new String[]{appPath}:contentType.equals("static")?new String[]{"","app","NT/app.dat","app.dat"}:new String[]{"NT/app.dat","app","app.dat",""};
  for(String relative:candidates){
   File candidate=new File(relative);if(!candidate.isAbsolute())candidate=new File(base,relative);
   if(hasHome(candidate)){web=candidate.getCanonicalFile();break;}
  }
  if(web==null)throw new IOException("No app found under "+base+" (expected NT/app.dat, app, or dir.html)");
  File databases=null,dataRoot=base;
  if(!contentType.equals("static")){
   File ancestor=web;
   for(int depth=0;ancestor!=null&&depth<5&&databases==null;depth++,ancestor=ancestor.getParentFile()){
    for(String relative:new String[]{"NT/sqlites","db","sqlites"}){
     File candidate=new File(ancestor,relative);
     if(candidate.isDirectory()){databases=candidate.getCanonicalFile();dataRoot=relative.equals("sqlites")&&ancestor.getName().equals("NT")?ancestor.getParentFile():ancestor;break;}
    }
   }
  }
  if(contentType.equals("default")&&databases==null)throw new IOException("Default content requires NT/sqlites, db, or sqlites beside the app");
  System.setProperty("noon.external.bin.root",dataRoot.getCanonicalPath());
  System.setProperty("noon.external.static",databases==null?"true":"false");
  if(databases==null)System.clearProperty("noon.sqlite.detected");
  else System.setProperty("noon.sqlite.detected",databases.getPath());
  System.setProperty("noon.lucene.detected",new File(dataRoot,"lucene").getCanonicalPath());
  File config=new File(base,"config.js");
  if(config.isFile())System.setProperty("noon.config.path",config.getCanonicalPath());
  return web;
 }
 static void apply()throws IOException{
  if("true".equals(System.getProperty("noon.external.secure"))){
   File bin=new File(System.getProperty("noon.bin.path")).getCanonicalFile();
   String db=System.getProperty("noon.sqlite.override"),index=System.getProperty("noon.lucene.override");
   if(db!=null&&!new File(db).getCanonicalFile().equals(new File(bin,"NT/sqlites").getCanonicalFile()))throw new SecurityException("Secure mode requires the default SQLite folder");
   if(index!=null&&!new File(index).getCanonicalFile().equals(new File(bin,"lucene").getCanonicalFile()))throw new SecurityException("Secure mode requires the default Lucene folder");
  }
  String sqlite=System.getProperty("noon.sqlite.override",System.getProperty("noon.sqlite.detected"));
  if(sqlite!=null){
   File root=new File(sqlite).getCanonicalFile();
   if(!root.isDirectory())throw new IOException("SQLite folder does not exist: "+root);
   System.setProperty("noon.sqlite.path",root.getPath());
   System.setProperty("noon.external.static","false");
   // Retain templates/config at the data root's parent (db or NT/sqlites layout).
   System.setProperty("noon.nt.path",root.getParentFile().getCanonicalPath());
  }
  String lucene=System.getProperty("noon.lucene.override",System.getProperty("noon.lucene.detected"));
  if(lucene!=null){
   File root=new File(lucene).getCanonicalFile();
   if(root.exists()&&!root.isDirectory())throw new IOException("Lucene path must be a folder: "+root);
   System.setProperty("noon.lucene.path",root.getPath());
  }
 }
}
