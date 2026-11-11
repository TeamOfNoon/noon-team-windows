import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Web files stay in the EXE; SQLite/config/templates get a persistent writable data directory. */
final class EmbeddedBin {
 static File prepare(File exe) throws Exception {
  List<String> files=new ArrayList<String>();
  for(String name:EmbeddedWebResources.names())if(name.startsWith("__bin/")&&!name.startsWith("__bin/lucene/"))files.add(name);
  Collections.sort(files);
  MessageDigest content=MessageDigest.getInstance("SHA-256");
  for(String name:files){content.update(name.getBytes(StandardCharsets.UTF_8));content.update((byte)0);EmbeddedWebResources.digest(name,content);}
  String version=hex(content.digest()).substring(0,24);
  MessageDigest identity=MessageDigest.getInstance("SHA-256");
  String app=exe.getCanonicalPath().toLowerCase(Locale.ROOT);
  String id=hex(identity.digest(app.getBytes(StandardCharsets.UTF_8))).substring(0,16);
  String local=System.getenv("LOCALAPPDATA");
  File home=local==null||local.isEmpty()?new File(System.getProperty("user.home"),".local/share"):new File(local);
  File bin=new File(home,"Noon/"+exe.getName().replaceAll("[^A-Za-z0-9._-]","_")+"-"+id+"/"+version+"/bin").getCanonicalFile();
  Files.createDirectories(new File(bin,"NT").toPath());
  for(String name:files){
   File target=new File(bin,name.substring(6)).getCanonicalFile();
   if(!target.toPath().startsWith(bin.toPath()))throw new IOException("Unsafe embedded bin path");
   // Never replace a user's writable data or a completed local index on relaunch.
   if(target.isFile())continue;
   Files.createDirectories(target.getParentFile().toPath());
   Path temp=Files.createTempFile(target.getParentFile().toPath(),"noon-data-",".tmp");
   try{
    try(OutputStream out=Files.newOutputStream(temp)){EmbeddedWebResources.copy(name,out);}
    try{Files.move(temp,target.toPath());}catch(FileAlreadyExistsException anotherProcess){/* same app initialized it */}
   }finally{Files.deleteIfExists(temp);}
  }
  System.setProperty("noon.bin.path",bin.getPath());
  System.setProperty("noon.embedded.config",new File(bin,"config.js").getPath());
  return bin;
 }
 private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
}
