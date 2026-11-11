import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.stream.*;
import org.json.*;

/** Trust anchor is a DLL resource, never a public key supplied by a writable bin. */
final class SignedBinIntegrity {
 static final String MESSAGE="Application files were modified or are not genuine. / تم تعديل ملفات التطبيق أو أن النسخة غير أصلية.";
 static final String MANIFEST="NT/install_manifest.json",SIGNATURE="NT/install_manifest.sig";
 private static Plan plan;
 private static Path disk;
 private static boolean external;
 private static String failure;
 private static long lastCheck,lastFull;
 private static final Map<String,String> stamps=new HashMap<>();
 private static final class Item{final String path,hash;final long size;Item(String p,String h,long s){path=p;hash=h;size=s;}}
 private static final class Plan{final byte[] manifest,signature;final Map<String,Item> files;Plan(byte[] m,byte[] s,Map<String,Item> f){manifest=m;signature=s;files=f;}}
 static SecurityException bad(String detail){return new SecurityException(MESSAGE+"\n"+detail);}
 private static PublicKey anchor()throws Exception{
  try(InputStream in=SignedBinIntegrity.class.getResourceAsStream("/noon-integrity/public_key.der")){
   if(in==null)throw bad("Verification key is missing. Rebuild the full solution.");
   return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(in.readAllBytes()));
  }
 }
 private static Plan parse(byte[] manifest,byte[] signature)throws Exception{
  if(manifest==null||signature==null||manifest.length>32*1024*1024||signature.length>16384)throw bad("Signed install manifest is missing or invalid.");
  PublicKey key=anchor();Signature verify=Signature.getInstance("SHA256withRSA");verify.initVerify(key);verify.update(manifest);
  if(!verify.verify(signature))throw bad("Manifest signature does not match the trusted public key.");
  JSONObject json=new JSONObject(new String(manifest,java.nio.charset.StandardCharsets.UTF_8));
  if(json.getInt("format")!=1||!json.getString("algorithm").equals("SHA256withRSA")||!json.getString("key_id").equals(hex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()))))throw bad("Unsupported signed manifest.");
  JSONArray list=json.getJSONArray("files");Map<String,Item> files=new TreeMap<>();Set<String> cases=new HashSet<>();
  for(int i=0;i<list.length();i++){
   JSONObject o=list.getJSONObject(i);String p=o.getString("path"),h=o.getString("sha256");long size=o.getLong("size");
   safeName(p);if(mutable(p)||p.equals(MANIFEST)||p.equals(SIGNATURE)||!h.matches("[0-9a-f]{64}")||size<0||!cases.add(p.toLowerCase(Locale.ROOT)))throw bad("Invalid/duplicate signed file entry.");
   files.put(p,new Item(p,h,size));
  }
  if(!files.containsKey("NT/app.dat/dir.html")||!files.containsKey("NT/noon_public_key.pem"))throw bad("Signed application entry files are missing.");
  return new Plan(manifest,signature,Collections.unmodifiableMap(files));
 }
 private interface Verification{void run()throws Exception;}
 private static void guarded(Verification task)throws Exception{try{task.run();}catch(Exception e){String detail=e.getMessage();if(detail!=null&&detail.contains(MESSAGE))throw e;throw bad(detail==null?"Signed bin verification failed.":detail);}}
 static synchronized void verifyEmbedded()throws Exception{guarded(()->verifyEmbeddedImpl());}
 static synchronized void verifyPreparedBin(File bin)throws Exception{guarded(()->verifyPreparedBinImpl(bin));}
 static synchronized void verifyExternal(File bin,File web)throws Exception{guarded(()->verifyExternalImpl(bin,web));}
 static boolean mutable(String p){return p.equals("config.js")||p.startsWith("lucene/")||p.startsWith("cache/")||p.startsWith("userdata/");}
 private static void safeName(String p){if(p.isEmpty()||p.startsWith("/")||p.indexOf('\\')>=0||p.indexOf(':')>=0||p.chars().anyMatch(c->c<32))throw bad("Unsafe signed path.");for(String part:p.split("/",-1))if(part.equals("..")||part.equals(".")||part.isEmpty())throw bad("Unsafe signed path.");}
 private static String logical(String resource){return resource.startsWith("__bin/")?resource.substring(6):"NT/app.dat/"+resource;}
 private static String resource(String logical){return logical.startsWith("NT/app.dat/")?logical.substring(11):"__bin/"+logical;}
 private static synchronized void verifyEmbeddedImpl()throws Exception{
  failure=null;disk=null;plan=null;stamps.clear();external=false;
  Plan p=parse(EmbeddedWebResources.readBounded("__bin/"+MANIFEST,32*1024*1024),EmbeddedWebResources.readBounded("__bin/"+SIGNATURE,16384));
  Set<String> actual=new TreeSet<>();
  for(String name:EmbeddedWebResources.names()){
   String logical=logical(name);safeName(logical);if(mutable(logical)||logical.equals(MANIFEST)||logical.equals(SIGNATURE))continue;actual.add(logical);
  }
  if(!actual.equals(p.files.keySet()))throw bad("Embedded file list differs from the signed manifest.");
  for(Item item:p.files.values()){
   MessageDigest hash=MessageDigest.getInstance("SHA-256");EmbeddedWebResources.digest(resource(item.path),hash);
   if(!hex(hash.digest()).equals(item.hash))throw bad("Modified embedded file: "+item.path);
  }
  plan=p;
 }
 private static synchronized void verifyPreparedBinImpl(File bin)throws Exception{
  requireInitialized();disk=bin.getCanonicalFile().toPath();external=false;verifyDisk(true);lastCheck=lastFull=System.nanoTime();
 }
 private static synchronized void verifyExternalImpl(File bin,File web)throws Exception{
  failure=null;plan=null;stamps.clear();external=true;disk=bin.getCanonicalFile().toPath();
  if(!web.getCanonicalFile().toPath().equals(disk.resolve("NT/app.dat")))throw bad("External mode requires a signed bin and --app pointing to bin/NT/app.dat. Loose unsigned app folders cannot be served.");
  plan=parse(bounded(target(MANIFEST),32*1024*1024),bounded(target(SIGNATURE),16384));verifyDisk(true);lastCheck=lastFull=System.nanoTime();
 }
 static synchronized void allowUnsignedExternal(){failure=null;plan=null;disk=null;external=true;stamps.clear();}
 private static boolean unsignedExternal(){return System.getProperty("noon.external.root")!=null && "false".equals(System.getProperty("noon.external.secure"));}
 static synchronized void requireInitialized(){if(unsignedExternal())return;if(failure!=null)throw bad(failure);if(plan==null)throw bad("Signed bin verification has not completed.");}
 private static Path target(String name)throws IOException{safeName(name);Path file=disk.resolve(name).normalize();if(!file.startsWith(disk)||!file.toFile().getCanonicalFile().toPath().startsWith(disk))throw bad("Protected file escapes bin.");Path part=disk;for(Path p:disk.relativize(file)){part=part.resolve(p);if(Files.isSymbolicLink(part))throw bad("Symlink in protected bin.");}return file;}
 private static void verifyDisk(boolean full)throws Exception{
  if(!MessageDigest.isEqual(bounded(target(MANIFEST),plan.manifest.length),plan.manifest)||!MessageDigest.isEqual(bounded(target(SIGNATURE),plan.signature.length),plan.signature))throw bad("Installed manifest or signature was changed.");
  Set<String> expected=new TreeSet<>(),actual=new TreeSet<>();
  for(Item item:plan.files.values()){
   if(!external&&item.path.startsWith("NT/app.dat/"))continue;expected.add(item.path);
   Path file=target(item.path);BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
   if(!a.isRegularFile()||a.size()!=item.size)throw bad("Missing or modified protected file: "+item.path);
   String stamp=a.size()+":"+a.lastModifiedTime()+":"+a.fileKey();
   if(full||!stamp.equals(stamps.get(item.path))){if(!hash(file).equals(item.hash))throw bad("Modified protected file: "+item.path);stamps.put(item.path,stamp);}
  }
  try(Stream<Path> walk=Files.walk(disk)){
   for(Path f:(Iterable<Path>)walk::iterator){if(f.equals(disk))continue;if(Files.isSymbolicLink(f))throw bad("Symlink in installed bin.");if(!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS))continue;String rel=disk.relativize(f).toString().replace(File.separatorChar,'/');if(ignoredSqliteSidecar(rel))continue;if(!mutable(rel)&&!rel.equals(MANIFEST)&&!rel.equals(SIGNATURE))actual.add(rel);}
  }
  if(!actual.equals(expected))throw bad("Installed protected file list was changed.");
 }
 private static boolean ignoredSqliteSidecar(String rel){
  // The previous read-only connection could create these for WAL-mode snapshots.
  // Immutable connections never read them. They are not trusted database content.
  if(!rel.startsWith("NT/sqlites/")||plan.files.containsKey(rel))return false;
  String base=rel.endsWith("-wal")||rel.endsWith("-shm")?rel.substring(0,rel.length()-4):"";
  String lower=base.toLowerCase(Locale.ROOT);
  return plan.files.containsKey(base)&&(lower.endsWith(".sqlite")||lower.endsWith(".sqlite3")||lower.endsWith(".db"));
 }
 static synchronized void checkRuntime(){
  if(unsignedExternal())return;requireInitialized();long now=System.nanoTime();if(disk==null||now-lastCheck<250000000L)return;
  try{boolean full=now-lastFull>30000000000L;verifyDisk(full);lastCheck=now;if(full)lastFull=now;}
  catch(Exception e){failure=e.getMessage();throw bad(failure);}
 }
 static synchronized void validateRead(String name,byte[] bytes)throws IOException{
  if(plan==null||bytes==null)return;String p=logical(name.replaceFirst("^/+",""));Item item=plan.files.get(p);if(item==null)return;
  try{if(bytes.length!=item.size||!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(item.hash)){failure="Modified application resource: "+p;throw bad(failure);}}
  catch(GeneralSecurityException e){throw new IOException(e);}
 }
 private static byte[] bounded(Path p,int limit)throws IOException{
  if(Files.size(p)>limit)throw bad("Signed metadata size was changed.");
  try(InputStream in=Files.newInputStream(p)){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw bad("Signed metadata size was changed.");return bytes;}
 }
 private static String hash(Path p)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[1048576];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
 private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
 static java.sql.Connection readOnlySqlite(File file)throws Exception{
  checkRuntime();org.sqlite.SQLiteConfig config=new org.sqlite.SQLiteConfig();config.setReadOnly(true);
  return java.sql.DriverManager.getConnection("jdbc:sqlite:"+file.getCanonicalFile().toURI().toASCIIString()+"?mode=ro&immutable=1",config.toProperties());
 }
 static byte[] errorHtml(){return ("<!doctype html><html><meta charset=\"utf-8\"><title>Noon</title><body style=\"font:20px Tahoma,Arial;padding:48px;text-align:center\"><h1>Noon / نون</h1><p>Application files were modified or are not genuine.</p><p dir=\"rtl\">تم تعديل ملفات التطبيق أو أن النسخة غير أصلية.</p><p>Reinstall a genuine signed copy. / أعد تثبيت نسخة أصلية موقعة.</p></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);}
}
