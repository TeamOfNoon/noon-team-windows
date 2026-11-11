import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Builder export only. Restores plain app/db and materializes SQLite viewer responses. */
final class AppDataExporter {
 static String export(File executable,File destination)throws Exception{
  File bin=destination.getCanonicalFile();
  if(!bin.isDirectory()||Objects.requireNonNull(bin.list()).length!=0)
   throw new IOException("Extraction requires an empty staging folder");
  System.setProperty("noon.executable.path",executable.getCanonicalPath());
  SignedBinIntegrity.verifyEmbedded();
  Path root=bin.toPath(),app=root.resolve("app"),db=root.resolve("db");
  Files.createDirectories(app);Files.createDirectories(db);
  int webFiles=0,databaseFiles=0;
  List<String> names=new ArrayList<String>(EmbeddedWebResources.names());Collections.sort(names);
  for(String name:names){
   String relative;
   if(name.startsWith("__bin/NT/sqlites/")){relative="db/"+name.substring("__bin/NT/sqlites/".length());databaseFiles++;}
   else if(name.startsWith("__bin/NT/templets/"))relative="templets/"+name.substring("__bin/NT/templets/".length());
   else if(name.equals("__bin/config.js"))relative="config.js";
   else if(name.startsWith("__bin/lucene/"))relative="lucene/"+name.substring("__bin/lucene/".length());
   else if(name.startsWith("__bin/"))continue;
   else {relative="app/"+name;webFiles++;}
   Path target=safeTarget(root,relative);Files.createDirectories(target.getParent());
   try(OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){EmbeddedWebResources.copy(name,out);}
  }
  if(webFiles==0)throw new IOException("This EXE contains no embedded web app");
  if(databaseFiles==0)throw new IOException("This EXE contains no embedded SQLite data");
  PlainSqliteCatalog catalog=new PlainSqliteCatalog(bin,db.toFile());
  // Sources omit this large tree; regenerate it from the restored SQLite content.
  DynamicJs.extractAll(catalog,app.toFile());
  generateHtml(catalog,app);
  WebRuntimeAssets.export(app.resolve("www"));
  StringBuilder warning=new StringBuilder();
  File[] folders=db.toFile().listFiles();
  if(folders!=null)for(File folder:folders){
   String key=folder.getName().replaceFirst("(?i)_sqlite$","").toLowerCase(Locale.ROOT);
   if(folder.isDirectory()&&folder.getName().matches("(?i)(mn|sn|vb)\\d+_sqlite")&&!catalog.forms.containsKey(key))
    warning.append("\nViewer not generated for unreadable/unsupported database / تعذر توليد ملفات العرض لقاعدة البيانات: ").append(folder.getName());
  }
  return "Viewer generated from SQLite / تم توليد ملفات العرض من SQLite.\nForms / المنتديات: "+catalog.forms.size()+warning;
 }
 // Keep HTML entry pages alongside the generated JS, pointing at the restored viewer.
 private static void generateHtml(PlainSqliteCatalog cat,Path app)throws Exception{
  Path viewer=app.resolve("www/viewer");
  html(viewer.resolve("index.html"),"../viewer.html");
  for(PlainSqliteCatalog.FormDb form:cat.forms.values()){
   String key=PlainSqliteCatalog.publicKey(form),family="vb".equals(form.family)?"vb":"mn";
   Path base=viewer.resolve("forum_"+key);
   String url="../../viewer.html?fo="+form.forumId+"&ftype="+family;
   html(base.resolve("index.html"),url);
   try(java.util.stream.Stream<Path> paths=Files.walk(base.resolve("js_json"))){
    for(Path path:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator){
     String name=path.getFileName().toString();java.util.regex.Matcher m;
     m=java.util.regex.Pattern.compile("thread_(\\d+)_pa_(\\d+)\\.js").matcher(name);
     if(m.matches()){
      html(base.resolve(name.substring(0,name.length()-3)+".html"),url+"&th="+m.group(1)+"&pa="+m.group(2));
      if("1".equals(m.group(2)))html(base.resolve("thread_"+m.group(1)+".html"),url+"&th="+m.group(1));
      continue;
     }
     m=java.util.regex.Pattern.compile("post_(\\d+)\\.js").matcher(name);
     if(m.matches()){html(base.resolve("post_"+m.group(1)+".html"),url+"&po="+m.group(1));continue;}
     m=java.util.regex.Pattern.compile("forum_(?:mn|vb)\\d+_pa_(\\d+)_order_([A-Za-z]+)_lastpost\\.js").matcher(name);
     if(m.matches()){
      html(base.resolve(name.substring(0,name.length()-3)+".html"),url+"&pa="+m.group(1)+"&order="+m.group(2)+"_lastpost");
      if(!Files.exists(base.resolve("forum_"+key+"_pa_"+m.group(1)+".html")))html(base.resolve("forum_"+key+"_pa_"+m.group(1)+".html"),url+"&pa="+m.group(1));
     }
    }
   }
  }
 }
 private static void html(Path file,String url)throws IOException{
  Files.createDirectories(file.getParent());
  String escaped=url.replace("&","&amp;").replace("\"","&quot;");
  String page="<!doctype html><html><head><meta charset=\"utf-8\"><title>Noon</title></head><body><a href=\""+escaped+"\">Open viewer / فتح العرض</a><script>location.replace("+org.json.JSONObject.quote(url)+"+location.hash);</script></body></html>";
  Files.write(file,page.getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 private static Path safeTarget(Path root,String name)throws IOException{
  if(name.indexOf('\\')>=0||name.indexOf(':')>=0||name.indexOf('\0')>=0)throw new IOException("Unsafe embedded extraction path");
  Path relative=Paths.get(name);
  for(Path part:relative)if(part.toString().equals("..")||part.toString().equals("."))throw new IOException("Unsafe embedded extraction path");
  Path target=root.resolve(relative).normalize();
  if(relative.isAbsolute()||!target.startsWith(root)||target.equals(root))throw new IOException("Unsafe embedded extraction path");
  return target;
 }
}
