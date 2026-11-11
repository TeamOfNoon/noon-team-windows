import java.io.*;
import java.nio.file.*;
/** Build-time entry point; never exposes build commands in the shipping EXE. */
public class BuildNt {
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("BuildNt APP DB OUTPUT_NT");
  File key=new File(new File(BuildNt.class.getProtectionDomain().getCodeSource().getLocation().toURI()),"noon_db_state_key.bin");
  if(!key.isFile()){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.write(key.toPath(),bytes);}
  DtBuilder.run(new File(args[0]),new File(args[1]),new File(args[2]));
 }
}
