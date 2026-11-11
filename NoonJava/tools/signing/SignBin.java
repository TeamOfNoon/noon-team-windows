import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.util.*;
import java.util.stream.*;

/** Build machine only. Never copy this tool's key directory into a payload. */
public final class SignBin {
 static final String MANIFEST="NT/install_manifest.json",SIGNATURE="NT/install_manifest.sig";
 public static void main(String[] args)throws Exception{
  if(args.length<2||!(args[0].equals("prepare")||args[0].equals("sign")))throw new IllegalArgumentException("SignBin prepare PROJECT | sign PROJECT BIN");
  Path project=Path.of(args[1]).toRealPath();
  KeySettings settings=loadSettings(project);
  if(settings.file.startsWith(project))throw new IOException("Keep the signing private key outside the project and bin folders");
  Path resources=project.resolve("NoonJava/resources");if(Files.isDirectory(resources))for(Path f:list(resources))if(privateMaterial(f))throw new IOException("Private-key material found in runtime resources; move it outside the project");
  KeyPair pair=keys(settings);
  System.out.println("[SIGNING] Key location (build machine only): "+settings.file);
  Path anchor=project.resolve("NoonJava/resources/noon-integrity/public_key.der");atomicIfChanged(anchor,pair.getPublic().getEncoded());
  if(args[0].equals("prepare")){System.out.println("[SIGNING] Public verification key prepared; private key stays outside project/payload.");return;}
  if(args.length!=3)throw new IllegalArgumentException("SignBin sign PROJECT BIN");
  Path root=Path.of(args[2]).toRealPath();
  Path key=settings.file;if(key.startsWith(root))throw new IOException("NOON_SIGNING_PRIVATE_KEY must be outside the bin being packaged");
  List<Path> staged=list(root);
  for(Path f:staged)if(privateMaterial(f)){Files.delete(f);System.out.println("[SIGNING] Excluded private-key material from staged bin.");}
  atomicIfChanged(root.resolve("NT/noon_public_key.pem"),pem("PUBLIC KEY",pair.getPublic().getEncoded()));
  StringBuilder json=new StringBuilder("{\"format\":1,\"algorithm\":\"SHA256withRSA\",\"key_id\":\"").append(hex(sha(pair.getPublic().getEncoded()))).append("\",\"files\":[");
  int count=0;
  for(Path file:list(root)){
   String path=root.relativize(file).toString().replace(File.separatorChar,'/');if(mutable(path)||path.equals(MANIFEST)||path.equals(SIGNATURE))continue;
   if(count++>0)json.append(',');
   json.append("{\"path\":").append(quote(path)).append(",\"size\":").append(Files.size(file)).append(",\"sha256\":\"").append(hash(file)).append("\"}");
  }
  if(count==0)throw new IOException("No protected bin files");
  byte[] bytes=json.append("]}\n").toString().getBytes(StandardCharsets.UTF_8);
  Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(pair.getPrivate());signer.update(bytes);byte[] signature=signer.sign();
  signer.initVerify(pair.getPublic());signer.update(bytes);if(!signer.verify(signature))throw new GeneralSecurityException("Signing self-check failed");
  atomicIfChanged(root.resolve(MANIFEST),bytes);atomicIfChanged(root.resolve(SIGNATURE),signature);
  System.out.println("[SIGNING] Signed "+count+" protected files; bin/config.js and runtime indexes remain editable.");
 }
 static final class KeySettings{final Path file;final boolean create;KeySettings(Path f,boolean c){file=f;create=c;}}
 static KeySettings loadSettings(Path project)throws IOException{
  Properties options=new Properties();Path config=project.resolve("SigningSettings.properties");
  if(Files.exists(config))try(Reader reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)){options.load(reader);}
  String path=options.getProperty("private_key_path","../NoonKeys/noon_private.pem").trim();
  String create=options.getProperty("create_if_missing","true").trim();
  if(!create.equalsIgnoreCase("true")&&!create.equalsIgnoreCase("false"))throw new IOException("SigningSettings.properties: create_if_missing must be true or false");
  String override=System.getenv("NOON_SIGNING_PRIVATE_KEY");boolean env=override!=null&&!override.isBlank();
  if(env)path=override.trim();if(path.isEmpty())throw new IOException("SigningSettings.properties: private_key_path must not be empty");
  Path file=Path.of(path);if(!file.isAbsolute())file=project.resolve(file);
  file=file.toFile().getCanonicalFile().toPath();
  return new KeySettings(file,!env&&Boolean.parseBoolean(create));
 }
 static KeyPair keys(KeySettings settings)throws Exception{
  Path file=settings.file;Files.createDirectories(file.getParent());
  try(FileChannel channel=FileChannel.open(file.resolveSibling(file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.lock()){
   if(!Files.exists(file)){
    // Environment overrides and strict settings must point to an existing key.
    if(!settings.create)throw new IOException("Configured signing key not found: "+file+". Check SigningSettings.properties or NOON_SIGNING_PRIVATE_KEY.");
    KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);KeyPair pair=generator.generateKeyPair();atomicIfChanged(file,pem("PRIVATE KEY",pair.getPrivate().getEncoded()));
    try{Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
   }
   String text=Files.readString(file,StandardCharsets.US_ASCII);boolean pkcs1=text.contains("-----BEGIN RSA PRIVATE KEY-----");
   if(text.contains("ENCRYPTED")||(!pkcs1&&!text.contains("-----BEGIN PRIVATE KEY-----")))throw new IOException("Use an unencrypted RSA PKCS#8 or PKCS#1 PEM signing key");
   byte[] der=Base64.getMimeDecoder().decode(text.replaceAll("-----[^\\r\\n]+-----",""));
   if(pkcs1){byte[] version={2,1,0},algorithm={48,13,6,9,42,(byte)134,72,(byte)134,(byte)247,13,1,1,1,5,0};der=tlv(48,concat(version,algorithm,tlv(4,der)));}
   PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
   if(!(privateKey instanceof RSAPrivateCrtKey))throw new IOException("RSA CRT signing key required");
   RSAPrivateCrtKey rsa=(RSAPrivateCrtKey)privateKey;if(rsa.getModulus().bitLength()<2048)throw new IOException("RSA key must be at least 2048 bits");
   PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent()));return new KeyPair(publicKey,privateKey);
  }
 }
 static List<Path> list(Path root)throws IOException{try(Stream<Path> paths=Files.walk(root)){List<Path> files=paths.filter(p->!p.equals(root)).sorted().collect(Collectors.toList());List<Path> result=new ArrayList<>();for(Path p:files){if(Files.isSymbolicLink(p))throw new IOException("Symlinks are not allowed in signed bin: "+root.relativize(p));if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))result.add(p);}return result;}}
 static boolean mutable(String p){return p.equals("config.js")||p.startsWith("lucene/")||p.startsWith("cache/")||p.startsWith("userdata/");}
 static boolean privateMaterial(Path file)throws IOException{
  if(file.getFileName().toString().equalsIgnoreCase("noon_private.pem"))return true;
  // PEM keys are text files. Do not mistake a quoted key example inside a
  // SQLite database, JavaScript string, image, or other binary asset for a key.
  try(InputStream in=Files.newInputStream(file)){
   byte[] prefix=in.readNBytes(8192);for(byte value:prefix)if(value==0)return false;
   String text=new String(prefix,StandardCharsets.UTF_8).replace("\ufeff","");
   return java.util.regex.Pattern.compile("(?m)^-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----[\\t\\r ]*$").matcher(text).find();
  }
 }
 static String hash(Path f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(f)){byte[] b=new byte[1048576];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
 static byte[] sha(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
 static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
 static String quote(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
 static byte[] pem(String type,byte[] b){return ("-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(b)+"\n-----END "+type+"-----\n").getBytes(StandardCharsets.US_ASCII);}
 static void atomicIfChanged(Path path,byte[] b)throws IOException{Files.createDirectories(path.getParent());if(Files.isRegularFile(path)&&Arrays.equals(Files.readAllBytes(path),b))return;Path temp=Files.createTempFile(path.getParent(),"noon-sign-",".tmp");try{Files.write(temp,b);try(FileChannel f=FileChannel.open(temp,StandardOpenOption.WRITE)){f.force(true);}try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}}
 static byte[] concat(byte[]...all)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] a:all)b.write(a);return b.toByteArray();}
 static byte[] tlv(int tag,byte[] content)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(tag);int n=content.length;if(n<128)b.write(n);else{int count=0;for(int v=n;v>0;v>>>=8)count++;b.write(128|count);for(int i=count-1;i>=0;i--)b.write(n>>>(i*8));}b.write(content);return b.toByteArray();}
}
