import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Edit only the server_port value; retain the user's other configuration text. */
final class SavedServerPort {
 static File file(File exe)throws IOException{
  File parent=exe.getCanonicalFile().getParentFile();String configured=System.getProperty("noon.config.path");
  File selected=configured==null||configured.isEmpty()?new File(parent,"bin/config.js"):new File(configured);
  if(!selected.isAbsolute())selected=new File(parent,configured);
  if(selected.isDirectory())selected=new File(selected,"config.js");return selected.getCanonicalFile();
 }
 static int read(File exe,int fallback)throws Exception{
  File f=file(exe);if(!f.isFile()){File defaultFile=new File(exe.getCanonicalFile().getParentFile(),"bin/config.js");if(defaultFile.isFile())f=defaultFile;else return fallback;}
  Document d=new Document(Files.readAllBytes(f.toPath()));Property p=find(d.text);
  if(p==null)return fallback;
  String value=strip(d.text.substring(p.start,p.end));
  if(value.startsWith("\"")||value.startsWith("'"))value=value.substring(1,value.length()-1);
  if(!value.matches("[0-9]+"))throw new IOException("server_port must be a number from 1 to 65535");
  int port=Integer.parseInt(value);if(port<1||port>65535)throw new IOException("server_port must be 1..65535");return port;
 }
 static int suggest()throws IOException{
  try(ServerSocket socket=new ServerSocket()){socket.setReuseAddress(false);socket.bind(new InetSocketAddress("127.0.0.1",0));return socket.getLocalPort();}
 }
 static void save(File exe,int port)throws Exception{
  if(port<1||port>65535)throw new IOException("Invalid port");
  File target=file(exe);byte[] bytes;
  if(target.isFile())bytes=Files.readAllBytes(target.toPath());
  else {bytes=EmbeddedWebResources.read("__bin/config.js");if(bytes==null)bytes="{}\n".getBytes(StandardCharsets.UTF_8);}
  Document doc=new Document(bytes);String updated=update(doc.text,port);
  Path parent=target.toPath().getParent();Files.createDirectories(parent);Path temp=Files.createTempFile(parent,"noon-config-",".tmp");
  try{
   Files.write(temp,doc.encode(updated));
   try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
   try{Files.move(temp,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
   catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
  }finally{Files.deleteIfExists(temp);}
 }
 static String update(String text,int port)throws IOException{
  Property p=find(text);
  if(p!=null){String raw=text.substring(p.start,p.end);int end=p.end;
   // Preserve whitespace or comments after a normal numeric/string port literal.
   int at=skip(text,p.start);if(text.charAt(at)=='\"'||text.charAt(at)=='\'')end=quotedEnd(text,at);
   else {int n=at;while(n<p.end&&Character.isDigit(text.charAt(n)))n++;if(n>at)end=n;}
   if(skip(text,end)!=p.end)end=p.end;
   return text.substring(0,p.start)+port+text.substring(end);
  }
  int open=root(text),first=skip(text,open+1);boolean empty=first<text.length()&&text.charAt(first)=='}';
  return text.substring(0,open+1)+"\n  \"server_port\": "+port+(empty?"":",")+text.substring(open+1);
 }
 static String putValue(String text,String key,String json,boolean onlyIfMissing)throws IOException{
  Property p=find(text,key);
  if(p!=null){
   if(onlyIfMissing)return text;
   int start=skip(text,p.start),end=p.end;
   if(text.charAt(start)=='\"'||text.charAt(start)=='\'')end=quotedEnd(text,start);
   return text.substring(0,p.start)+json+text.substring(end);
  }
  int open=root(text),first=skip(text,open+1);boolean empty=first<text.length()&&text.charAt(first)=='}';
  return text.substring(0,open+1)+"\n  "+org.json.JSONObject.quote(key)+": "+json+(empty?"":",")+text.substring(open+1);
 }
 static void saveBuildSettings(File target,int port,String version,File app)throws Exception{
  if(port<1||port>65535)throw new IOException("Invalid port");
  target=target.getCanonicalFile();
  Document doc=new Document(target.isFile()?Files.readAllBytes(target.toPath()):"(function (w) {\n  w.NT_CONFIG = {};\n})(window);\n".getBytes(StandardCharsets.UTF_8));
  String text=update(doc.text,port);
  if(System.getProperty("noon.build.app_type")!=null)text=putValue(text,"app_type",org.json.JSONObject.quote(System.getProperty("noon.build.app_type")),false);
  text=putValue(text,"app_version",org.json.JSONObject.quote(version),false);
  text=putValue(text,"boot_file",org.json.JSONObject.quote(ExternalDataPaths.bootFile()),false);
  if(app!=null){
  text=putValue(text,"external_content_type",org.json.JSONObject.quote("auto"),true);
  File selectedApp=app.getCanonicalFile();
  for(String relative:new String[]{"NT/app.dat","app","app.dat",""}){File candidate=new File(selectedApp,relative);if(ExternalDataPaths.hasHome(candidate)){selectedApp=candidate.getCanonicalFile();break;}}
  String appPath;try{appPath=target.getParentFile().toPath().relativize(selectedApp.toPath()).toString().replace(File.separatorChar,'/');}catch(IllegalArgumentException differentDrive){appPath=selectedApp.getCanonicalPath();}
  if(appPath.isEmpty())appPath=".";
  text=putValue(text,"external_app_path",org.json.JSONObject.quote(appPath),false);
  }
  if(System.getProperty("noon.build.password.required")!=null){
   org.json.JSONObject config=ExternalConfigStartup.withoutCredentials(AppIndexConfig.parse(text));
   if(Boolean.parseBoolean(System.getProperty("noon.build.password.required")))config.put("pass",System.getProperty("noon.build.password","noon"));
   text="(function (w) {\n  w.NT_CONFIG = "+config.toString(2)+";\n})(window);\n";
  }
  Files.createDirectories(target.getParentFile().toPath());
  Path temporary=Files.createTempFile(target.getParentFile().toPath(),"noon-build-config-",".tmp");
  try{Files.write(temporary,doc.encode(text));try{Files.move(temporary,target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,target.toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}
 }
 private static final class Document{
  final String text;final Charset charset;final byte[] bom;
  Document(byte[] b)throws CharacterCodingException{
   int start=0;Charset c=StandardCharsets.UTF_8;byte[] prefix=new byte[0];
   if(b.length>=3&&(b[0]&255)==239&&(b[1]&255)==187&&(b[2]&255)==191){start=3;prefix=Arrays.copyOf(b,3);}
   else if(b.length>=2&&(b[0]&255)==255&&(b[1]&255)==254){start=2;c=StandardCharsets.UTF_16LE;prefix=Arrays.copyOf(b,2);}
   else if(b.length>=2&&(b[0]&255)==254&&(b[1]&255)==255){start=2;c=StandardCharsets.UTF_16BE;prefix=Arrays.copyOf(b,2);}
   text=c.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b,start,b.length-start)).toString();charset=c;bom=prefix;
  }
  byte[] encode(String value){byte[] body=value.getBytes(charset),result=Arrays.copyOf(bom,bom.length+body.length);System.arraycopy(body,0,result,bom.length,body.length);return result;}
 }
 private static final class Property{final int start,end;Property(int a,int b){start=a;end=b;}}
 private static String strip(String value)throws IOException{int start=skip(value,0);int end=value.length();while(end>start&&Character.isWhitespace(value.charAt(end-1)))end--;String token=value.substring(start,end);int comment=token.indexOf("//");if(comment>=0)token=token.substring(0,comment).trim();comment=token.indexOf("/*");if(comment>=0)token=token.substring(0,comment).trim();return token;}
 private static int skip(String s,int i)throws IOException{
  while(i<s.length()){
   if(Character.isWhitespace(s.charAt(i))){i++;continue;}
   if(s.startsWith("//",i)){int n=s.indexOf('\n',i+2);i=n<0?s.length():n+1;continue;}
   if(s.startsWith("/*",i)){int n=s.indexOf("*/",i+2);if(n<0)throw new IOException("Unclosed config comment");i=n+2;continue;}break;
  }return i;
 }
 private static int quotedEnd(String s,int i)throws IOException{char quote=s.charAt(i++);while(i<s.length()){char c=s.charAt(i++);if(c=='\\'){i++;continue;}if(c==quote)return i;}throw new IOException("Unclosed config string");}
 private static int root(String s)throws IOException{
  int first=skip(s,0);if(first<s.length()&&s.charAt(first)=='{')return first;
  // Recognize window.NT_CONFIG = {...}, including an enclosing IIFE.
  for(int i=first;i<s.length();){i=skip(s,i);if(i>=s.length())break;char c=s.charAt(i);
   if(c=='\"'||c=='\''||c=='`'){i=quotedEnd(s,i);continue;}
   if(Character.isJavaIdentifierStart(c)){int end=i+1;while(end<s.length()&&Character.isJavaIdentifierPart(s.charAt(end)))end++;
    if(s.substring(i,end).equals("NT_CONFIG")){int eq=skip(s,end);if(eq<s.length()&&s.charAt(eq)=='='){int open=skip(s,eq+1);if(open<s.length()&&s.charAt(open)=='{')return open;}}
    i=end;
   }else i++;
  }throw new IOException("Cannot locate JSON or NT_CONFIG object; configuration left unchanged");
 }
 private static Property find(String s)throws IOException{return find(s,"server_port");}
 private static Property find(String s,String wanted)throws IOException{
  int i=skip(s,root(s)+1);Property found=null;
  while(i<s.length()&&s.charAt(i)!='}'){
   int start=i,end;String key;
   if(s.charAt(i)=='\"'||s.charAt(i)=='\''){end=quotedEnd(s,i);key=s.substring(i+1,end-1);}
   else{end=i;while(end<s.length()&&Character.isJavaIdentifierPart(s.charAt(end)))end++;if(end==i)throw new IOException("Invalid config property");key=s.substring(i,end);}
   i=skip(s,end);if(i>=s.length()||s.charAt(i)!=':')throw new IOException("Invalid config object");i=skip(s,i+1);start=i;
   int nested=0;
   while(i<s.length()){
    char c=s.charAt(i);if(c=='\"'||c=='\''||c=='`'){i=quotedEnd(s,i);continue;}
    if(s.startsWith("//",i)||s.startsWith("/*",i)){i=skip(s,i);continue;}
    if(nested==0&&(c==','||c=='}'))break;
    if(c=='{'||c=='['||c=='(')nested++;
    else if(c=='}'||c==']'||c==')'){if(--nested<0)throw new IOException("Invalid nested config");}i++;
   }
   if(i>=s.length())throw new IOException("Unclosed config object");end=i;
   if(key.equals(wanted)){if(found!=null)throw new IOException("Duplicate "+wanted+" properties");found=new Property(start,end);}
   if(s.charAt(i)=='}')return found;i=skip(s,i+1);
  }
  if(i>=s.length())throw new IOException("Unclosed config object");return found;
 }
}
