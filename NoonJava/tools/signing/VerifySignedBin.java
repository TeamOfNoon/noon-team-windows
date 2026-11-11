import java.io.File;
/** Reusable signed-bin verification tool; takes its key from the trusted runtime resource. */
public final class VerifySignedBin {
 public static void main(String[] args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("VerifySignedBin BIN_FOLDER");
  File bin=new File(args[0]).getCanonicalFile();SignedBinIntegrity.verifyExternal(bin,new File(bin,"NT/app.dat"));
  System.out.println("[OK] signed manifest and every protected file verified");
 }
}
