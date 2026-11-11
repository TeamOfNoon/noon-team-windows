import java.io.*;
import java.util.*;
import org.apache.lucene.store.*;

/** Read-only Lucene Directory over the raw files embedded inside noon.exe. */
final class EmbeddedLuceneDirectory extends Directory {
 private final Map<String,String> files=new TreeMap<String,String>();
 private volatile boolean closed;
 private static boolean enabled(){return System.getProperty("noon.external.root")==null&&(System.getProperty("noon.executable.path")!=null||System.getProperty("noon.resource.jar")!=null);}
 static Set<String> folders()throws IOException{
  Set<String> out=new TreeSet<String>();if(!enabled())return out;
  for(String name:EmbeddedWebResources.names()){
   if(!name.startsWith("__bin/lucene/"))continue;
   String relative=name.substring(13);int slash=relative.indexOf('/');
   if(slash>0&&relative.substring(0,slash).matches("(mn|sn|vb)\\d+_(normal|ngram|arstem)_index")&&relative.substring(slash+1).startsWith("segments_"))out.add(relative.substring(0,slash));
  }return out;
 }
 static boolean exists(String folder)throws IOException{return folders().contains(folder);}
 EmbeddedLuceneDirectory(String folder)throws IOException{
  if(!enabled()||!folder.matches("(mn|sn|vb)\\d+_(normal|ngram|arstem)_index"))throw new FileNotFoundException(folder);
  String prefix="__bin/lucene/"+folder+"/";
  for(String resource:EmbeddedWebResources.names())if(resource.startsWith(prefix)){
   String name=resource.substring(prefix.length());
   if(name.length()==0||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.equals(".")||name.equals(".."))throw new IOException("Invalid embedded index filename");
   files.put(name,resource);
  }
 }
 protected void ensureOpen(){if(closed)throw new AlreadyClosedException("Embedded index directory closed");}
 private String resource(String name)throws IOException{ensureOpen();String resource=files.get(name);if(resource==null)throw new FileNotFoundException(name);return resource;}
 public String[] listAll(){ensureOpen();return files.keySet().toArray(new String[files.size()]);}
 public long fileLength(String name)throws IOException{return EmbeddedWebResources.resourceLength(resource(name));}
 public IndexInput openInput(String name,IOContext context)throws IOException{return EmbeddedWebResources.openIndexInput(resource(name),context);}
 private IOException readOnly(){return new IOException("Embedded indexes are read-only; build new indexes in NT/lucene beside the EXE");}
 public void deleteFile(String name)throws IOException{throw readOnly();}
 public IndexOutput createOutput(String name,IOContext context)throws IOException{throw readOnly();}
 public void sync(Collection<String> names)throws IOException{throw readOnly();}
 public void renameFile(String source,String destination)throws IOException{throw readOnly();}
 public Lock obtainLock(String name)throws IOException{throw readOnly();}
 public void close(){closed=true;}
}
