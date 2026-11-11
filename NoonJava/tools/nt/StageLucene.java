import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;

/** Stage optional committed indexes next to app/db, without live writer files. */
public final class StageLucene {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("StageLucene SOURCE_LUCENE STAGED_LUCENE");
  int count=stage(Paths.get(args[0]),Paths.get(args[1]));
  System.out.println("Ready-made Lucene indexes packaged: "+count);
 }
 static int stage(Path source,Path destination)throws IOException{
  if(!Files.exists(source))return 0;
  if(!Files.isDirectory(source)||Files.isSymbolicLink(source))throw new IOException("lucene must be a regular folder");
  List<Path> folders=new ArrayList<Path>();
  try(DirectoryStream<Path> entries=Files.newDirectoryStream(source)){
   for(Path entry:entries){
    if(!Files.isDirectory(entry))continue;
    if(Files.isSymbolicLink(entry)||!entry.getFileName().toString().matches("(?i)(mn|sn|vb)\\d+_(normal|ngram|arstem)_index"))
     throw new IOException("Unexpected Lucene index folder: "+entry.getFileName());
    folders.add(entry);
   }
  }
  Collections.sort(folders);
  for(Path folder:folders){
   try(Directory directory=FSDirectory.open(folder);Lock lock=directory.obtainLock(IndexWriter.WRITE_LOCK_NAME);
       DirectoryReader reader=DirectoryReader.open(directory)){
    Path target=destination.resolve(folder.getFileName().toString().toLowerCase(Locale.ROOT));
    Files.createDirectories(target);
    for(String name:reader.getIndexCommit().getFileNames()){
     Path file=folder.resolve(name);
     if(Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new IOException("Invalid Lucene file: "+file);
     Files.copy(file,target.resolve(name));
    }
   }catch(IOException e){throw new IOException("Cannot package "+folder.getFileName()+"; close the indexing app and supply a committed Lucene 5.3 index",e);}
  }
  return folders.size();
 }
}
