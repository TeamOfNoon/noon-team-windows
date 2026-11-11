import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Same raw appended-file format as the MFC demo: no archive or decompressor. */
final class EmbeddedWebResources {
 private static final class Entry {
  final long offset;final int size;
  Entry(long offset,int size){this.offset=offset;this.size=size;}
 }
 private static volatile Map<String,Entry> entries;
 private static File executable;
 private static File jar;
 private static File external;
 private static File externalFile(String p)throws IOException{
  if(external==null){String root=System.getProperty("noon.external.root");if(root!=null)external=new File(root).getCanonicalFile();}
  if(external==null)return null;
  String name=p.replace('\\','/');while(name.startsWith("/"))name=name.substring(1);
  if(name.indexOf('\0')>=0 || Arrays.asList(name.split("/")).contains(".."))throw new IOException("Unsafe external path");
  if(!new File(external,"www").isDirectory() && new File(external,"res").isDirectory() && name.startsWith("www/"))name=name.substring(4);
  File file=new File(external,name).getCanonicalFile();
  if(!file.toPath().startsWith(external.toPath()))throw new IOException("External path escapes root");
  return file;
 }

 private static String normalize(String p){
  if(p==null)return null;p=p.replace('\\','/');while(p.startsWith("/"))p=p.substring(1);
  if(p.startsWith("app/"))p=p.substring(4);
  if(p.indexOf('\0')>=0||Arrays.asList(p.split("/")).contains(".."))return null;return p;
 }
 private static long littleLong(RandomAccessFile in)throws IOException{return Long.reverseBytes(in.readLong());}
 private static Map<String,Entry> load()throws IOException{
  if(entries!=null)return entries;
  synchronized(EmbeddedWebResources.class){
   if(entries!=null)return entries;
   String jarPath=System.getProperty("noon.resource.jar");
   if(jarPath!=null){
    File source=new File(jarPath).getCanonicalFile();Map<String,Entry> map=new HashMap<String,Entry>();
    try(ZipFile zip=new ZipFile(source)){
     Enumeration<? extends ZipEntry> all=zip.entries();while(all.hasMoreElements()){
      ZipEntry e=all.nextElement();if(e.isDirectory()||!e.getName().startsWith("noon-web/"))continue;
      String name=normalize(e.getName().substring(9));long size=e.getSize();
      if(name==null||name.isEmpty()||size<0||size>Integer.MAX_VALUE||e.getMethod()!=ZipEntry.STORED||map.put(name,new Entry(0,(int)size))!=null)throw new IOException("Invalid raw JAR resource");
     }
    }
    jar=source;entries=Collections.unmodifiableMap(map);return entries;
   }
   String path=System.getProperty("noon.executable.path");
   if(path==null)throw new IOException("Native executable path missing");
   File exe=new File(path).getCanonicalFile();Map<String,Entry> map=new HashMap<String,Entry>();
   try(RandomAccessFile in=new RandomAccessFile(exe,"r")){
    long length=in.length();if(length<24)throw new IOException("Web payload missing");
    in.seek(length-24);byte[] magic=new byte[8];in.readFully(magic);
    if(!Arrays.equals(magic,"NHTMLPK1".getBytes(StandardCharsets.US_ASCII)))throw new IOException("Raw web payload missing. Rebuild using BUILD_DESKTOP_WEBVIEW2.bat.");
    long indexOffset=littleLong(in),indexSize=littleLong(in);
    if(indexOffset<64||indexSize<1||indexSize>64*1024*1024||indexSize>length-24)throw new IOException("Invalid web manifest bounds");
    long actualIndex=length-24-indexSize,shift=actualIndex-indexOffset;
    if(actualIndex<64)throw new IOException("Invalid relocated manifest");
    byte[] bytes=new byte[(int)indexSize];in.seek(actualIndex);in.readFully(bytes);
    String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\n");
    if(lines.length<2)throw new IOException("Empty web manifest");
    for(int i=1;i<lines.length;i++){
     if(lines[i].length()==0)continue;String[] fields=lines[i].split("\t",-1);
     if(fields.length!=3)throw new IOException("Invalid web manifest entry");
     String name=normalize(fields[0]);long offset,size;
     try{offset=Long.parseLong(fields[1]);size=Long.parseLong(fields[2]);}catch(NumberFormatException e){throw new IOException("Invalid web entry numbers",e);}
     if(offset<64||offset>indexOffset||size<0||size>Integer.MAX_VALUE||size>indexOffset-offset)throw new IOException("Invalid recorded web bounds");
     offset+=shift;
     if(name==null||name.length()==0||offset<64||offset>actualIndex||size>actualIndex-offset||map.size()>=1000000||map.put(name,new Entry(offset,(int)size))!=null)throw new IOException("Invalid web file bounds or duplicate path");
    }
   }
   executable=exe;entries=Collections.unmodifiableMap(map);return entries;
  }
 }
 static boolean available()throws IOException{File f=externalFile("");return f!=null?f.isDirectory():!load().isEmpty();}
 static boolean contains(String p)throws IOException{File f=externalFile(p);return f!=null?f.isFile():load().containsKey(normalize(p));}
 static byte[] readBounded(String p,int limit)throws IOException{
  File f=externalFile(p);if(f!=null){if(!f.isFile())return null;if(f.length()>limit)throw new IOException("Signed metadata is too large");try(InputStream in=java.nio.file.Files.newInputStream(f.toPath())){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Signed metadata is too large");return bytes;}}
  Entry entry=load().get(normalize(p));if(entry==null)return null;if(entry.size>limit)throw new IOException("Signed metadata is too large");return read(p);
 }
 static byte[] read(String p)throws IOException{
  File f=externalFile(p);if(f!=null){if(!f.isFile())return null;byte[] bytes=java.nio.file.Files.readAllBytes(f.toPath());
   String auth=System.getProperty("noon.external.auth.config");
   if(auth!=null && f.getCanonicalPath().equals(auth))return ExternalConfigStartup.webConfig(bytes);
   SignedBinIntegrity.validateRead(p,bytes);return bytes;}

  Entry e=load().get(normalize(p));if(e==null)return null;
  byte[] bytes=new byte[e.size];
  if(jar!=null){try(ZipFile zip=new ZipFile(jar)){
    ZipEntry file=zip.getEntry("noon-web/"+normalize(p));if(file==null)throw new IOException("JAR resource missing");
    try(DataInputStream in=new DataInputStream(zip.getInputStream(file))){in.readFully(bytes);if(in.read()!=-1)throw new IOException("JAR resource size changed");}SignedBinIntegrity.validateRead(p,bytes);return bytes;
   }}
  try(RandomAccessFile in=new RandomAccessFile(executable,"r")){in.seek(e.offset);in.readFully(bytes);}SignedBinIntegrity.validateRead(p,bytes);return bytes;
 }

 static long resourceLength(String name)throws IOException{
  Entry entry=load().get(normalize(name));if(entry==null)throw new FileNotFoundException(name);return entry.size;
 }
 static org.apache.lucene.store.IndexInput openIndexInput(String name,org.apache.lucene.store.IOContext context)throws IOException{
  Entry entry=load().get(normalize(name));if(entry==null)throw new FileNotFoundException(name);
  if(jar!=null)return new EmbeddedIndexInput(name,read(name),context);
  return new EmbeddedIndexInput(name,executable,entry.offset,entry.size,context);
 }
 static Set<String> names()throws IOException{return load().keySet();}
 static void copy(String name,OutputStream out)throws IOException {
  Entry e=load().get(normalize(name));if(e==null)throw new IOException("Missing embedded data: "+name);
  if(jar!=null){try(ZipFile zip=new ZipFile(jar);InputStream in=zip.getInputStream(zip.getEntry("noon-web/"+normalize(name)))){byte[] buffer=new byte[1048576];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}return;}
  try(RandomAccessFile in=new RandomAccessFile(executable,"r")){in.seek(e.offset);long left=e.size;byte[] buffer=new byte[1048576];while(left>0){int n=in.read(buffer,0,(int)Math.min(left,buffer.length));if(n<0)throw new EOFException();out.write(buffer,0,n);left-=n;}}
 }
 static void digest(String name,final java.security.MessageDigest digest)throws IOException {
  copy(name,new OutputStream(){public void write(int value){digest.update((byte)value);}public void write(byte[] data,int offset,int count){digest.update(data,offset,count);}});
 }
}
