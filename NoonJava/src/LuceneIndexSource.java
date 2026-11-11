import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.store.*;

/** Local completed indexes override embedded ones; deletions can hide bundled types. */
final class LuceneIndexSource {
 static boolean local(File directory){
  File[] files=directory.listFiles();if(files==null)return false;
  for(File file:files)if(file.isFile()&&file.getName().startsWith("segments_"))return true;
  return false;
 }
 private static File hidden(File directory){return new File(new File(directory.getParentFile(),".noon-hidden-bundled"),directory.getName()+".hidden");}
 static boolean exists(File directory){
  if(local(directory))return true;if(hidden(directory).isFile())return false;
  try{return EmbeddedLuceneDirectory.exists(directory.getName());}catch(IOException e){throw new java.io.UncheckedIOException("Cannot read embedded Lucene indexes",e);}
 }
 static Directory open(File directory)throws IOException{
  if(local(directory))return FSDirectory.open(directory.toPath());
  if(hidden(directory).isFile()||!EmbeddedLuceneDirectory.exists(directory.getName()))return null;
  return new EmbeddedLuceneDirectory(directory.getName());
 }
 static Set<String> names(File root)throws IOException{
  Set<String> names=new TreeSet<String>();File[] children=root.listFiles();
  if(children!=null)for(File file:children)if(file.isDirectory()&&local(file))names.add(file.getName());
  for(String name:EmbeddedLuceneDirectory.folders())if(!hidden(new File(root,name)).isFile())names.add(name);
  return names;
 }
 static void hideBundled(File directory)throws IOException{
  if(!EmbeddedLuceneDirectory.exists(directory.getName()))return;
  File marker=hidden(directory);Files.createDirectories(marker.getParentFile().toPath());Files.write(marker.toPath(),new byte[0]);
 }
 /** Deleting writable indexes restores the original EXE indexes, including an old deletion marker. */
 static void restoreBundled(File directory)throws IOException{
  Files.deleteIfExists(hidden(directory).toPath());
 }
}
