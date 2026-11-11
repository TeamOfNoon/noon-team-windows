import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Runtime tamper guard for mutable content.sqlite databases.
 *
 * The normal signed integrity manifest cannot contain content.sqlite because
 * legitimate signed delta updates and SQLite VACUUM change those files.
 * This class therefore keeps a small authenticated database-state file.
 *
 * The HMAC key is embedded inside noon.jar at build time. noon.jar itself is
 * protected by IntegrityManager's RSA-signed static manifest. This is intended
 * to detect ordinary/manual changes to content.sqlite while still allowing the
 * application's own verified update and compact operations.
 */
public final class DbStateGuard {
    public static final String STATE_FILE="db_state.dat";
    public static final String TEMP_FILE="db_state.dat.tmp";
    public static final String PENDING_FILE="db_state.pending";
    public static final String PENDING_TEMP_FILE="db_state.pending.tmp";
    private static final String HEADER="NOON-DB-STATE-1";
    private static final String PENDING_HEADER="NOON-DB-PENDING-1";
    private static final String KEY_RESOURCE="/noon_db_state_key.bin";
    private DbStateGuard(){}

    /** Called only while creating/signing a fresh NT tree. */
    public static synchronized void initialize(File ntRoot)throws Exception{
        Map<String,String> current=scanDatabases(ntRoot);
        writeState(ntRoot,current);
        System.out.println("[OK] initialized authenticated SQLite state ("+current.size()+" database(s))");
    }

    /** Called at startup after the RSA-signed static application tree passes. */
    public static synchronized void verify(File ntRoot)throws Exception{
        File root=ntRoot.getCanonicalFile();
        State st=readVerifiedState(root);
        Map<String,String> current=scanDatabases(root);
        if(!st.hashes.equals(current)){
            // Crash-safe recovery: if the app had already authenticated an authorized
            // Update/Compact operation before SQLite bytes changed, a signed/HMACed
            // pending marker lets startup finish that state transition. Manual edits
            // have no valid pending marker and still fail.
            Pending pending=readVerifiedPending(root,false);
            if(pending!=null && canRecoverPending(st,current,pending)){
                st.hashes.put(pending.rel,current.get(pending.rel));
                writeState(root,st.hashes);
                clearPending(root);
                System.out.println("[DB-GUARD] recovered interrupted authorized SQLite change: "+pending.rel);
                return;
            }
            int removed=0;
            // Manual deletion is explicitly allowed. A missing trusted SQLite is
            // treated as an uninstall, not as tampering. But any trusted file that
            // still exists must remain byte-for-byte identical to its trusted hash.
            for(Map.Entry<String,String> e:st.hashes.entrySet()){
                String actual=current.get(e.getKey());
                if(actual==null){
                    removed++;
                    System.out.println("[DB-GUARD] manually removed SQLite accepted: "+e.getKey());
                    continue;
                }
                if(!e.getValue().equals(actual))
                    throw new SecurityException("Modified SQLite database: "+e.getKey());
            }
            // A new/untrusted SQLite cannot be dropped in manually. It must come
            // through the authorized installer, which refreshes db_state.dat.
            for(String p:current.keySet())
                if(!st.hashes.containsKey(p))
                    throw new SecurityException("Unexpected SQLite database: "+p);

            // If all differences are deletions only, accept them and rewrite the
            // authenticated state to the exact remaining set.
            if(removed>0){
                writeState(root,current);
                clearPending(root);
                System.out.println("[OK] manual SQLite deletion accepted: "+removed+" removed; "+current.size()+" database(s) remain protected");
                return;
            }
            throw new SecurityException("SQLite database integrity check failed");
        }
        // A marker can remain if an authorized operation made no byte change.
        Pending pending=readVerifiedPending(root,false);
        if(pending!=null) clearPending(root);
        System.out.println("[OK] SQLite database state verified ("+current.size()+" database(s))");
    }


    /** Verify the current database set before an authorized install/remove operation. */
    public static synchronized void beginAuthorizedDatabaseSetChange(File ntRoot)throws Exception{
        verify(ntRoot);
    }

    /** Trust the new complete database set after an authorized install/remove operation. */
    public static synchronized void finishAuthorizedDatabaseSetChange(File ntRoot)throws Exception{
        File root=ntRoot.getCanonicalFile();
        writeState(root,scanDatabases(root));
        clearPending(root);
        System.out.println("[DB-GUARD] trusted authorized SQLite database set change");
    }

    /** Write an authenticated crash-recovery marker immediately before an authorized SQLite mutation. */
    public static synchronized void beginAuthorizedChange(File ntRoot,File db)throws Exception{
        File root=ntRoot.getCanonicalFile();
        File file=db.getCanonicalFile();
        if(!file.isFile())throw new FileNotFoundException("SQLite database missing before authorized operation: "+file);
        if(!isProtectedDatabase(root,file))throw new SecurityException("Refusing to authorize database outside NT/sqlites: "+file);
        if(!isInside(root,file))throw new SecurityException("SQLite database is outside NT tree: "+file);
        State st=readVerifiedState(root);
        Map<String,String> current=scanDatabases(root);
        if(!st.hashes.equals(current))throw new SecurityException("SQLite database changed before authorized operation");
        String rel=relative(root,file);
        String old=st.hashes.get(rel);
        if(old==null)throw new SecurityException("SQLite database is not in trusted state: "+rel);
        writePending(root,rel,old);
        System.out.println("[DB-GUARD] authorized SQLite change started: "+rel);
    }

    /**
     * Refresh exactly one database after an authorized application operation.
     * Call only after a verified signed delta has been applied or VACUUM/Compact
     * completed successfully and its JDBC connection has been closed.
     */
    public static synchronized void refreshDatabase(File ntRoot,File db)throws Exception{
        File root=ntRoot.getCanonicalFile();
        File file=db.getCanonicalFile();
        if(!file.isFile())throw new FileNotFoundException("SQLite database missing after authorized operation: "+file);
        if(!isProtectedDatabase(root,file))throw new SecurityException("Refusing to trust database outside NT/sqlites: "+file);
        if(!isInside(root,file))throw new SecurityException("SQLite database is outside NT tree: "+file);

        State st=readVerifiedState(root);
        String rel=relative(root,file);
        if(!st.hashes.containsKey(rel))throw new SecurityException("SQLite database is not in trusted state: "+rel);
        // The target database is expected to differ after the authorized operation,
        // but every other database must still match the previously trusted state.
        Map<String,String> current=scanDatabases(root);
        if(!current.keySet().equals(st.hashes.keySet()))throw new SecurityException("SQLite database set changed unexpectedly during authorized operation");
        for(Map.Entry<String,String> e:st.hashes.entrySet())if(!e.getKey().equals(rel)){
            String a=current.get(e.getKey());
            if(!e.getValue().equals(a))throw new SecurityException("Another SQLite database was modified unexpectedly: "+e.getKey());
        }
        st.hashes.put(rel,current.get(rel));
        writeState(root,st.hashes);
        clearPending(root);
        System.out.println("[DB-GUARD] trusted authorized SQLite change: "+rel);
    }


    private static boolean canRecoverPending(State st,Map<String,String> current,Pending p){
        if(p==null||p.rel==null||p.oldHash==null)return false;
        if(!st.hashes.keySet().equals(current.keySet()))return false;
        String trustedOld=st.hashes.get(p.rel);
        if(trustedOld==null||!trustedOld.equals(p.oldHash))return false;
        for(Map.Entry<String,String> e:st.hashes.entrySet()){
            if(e.getKey().equals(p.rel))continue;
            if(!e.getValue().equals(current.get(e.getKey())))return false;
        }
        return current.get(p.rel)!=null;
    }

    private static void writePending(File root,String rel,String oldHash)throws Exception{
        String body=PENDING_HEADER+"\n"+oldHash+"\t"+rel+"\n";
        String tag=Base64.getEncoder().encodeToString(hmac(body.getBytes(StandardCharsets.UTF_8)));
        byte[] all=(body+"hmac\t"+tag+"\n").getBytes(StandardCharsets.UTF_8);
        Path tmp=new File(root,PENDING_TEMP_FILE).toPath(), dst=new File(root,PENDING_FILE).toPath();
        Files.write(tmp,all,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
        try{Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING);}
    }

    private static Pending readVerifiedPending(File root,boolean required)throws Exception{
        File f=new File(root,PENDING_FILE);
        if(!f.isFile()){if(required)throw new SecurityException("Authorized SQLite marker is missing");return null;}
        List<String> lines=Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);
        if(lines.size()!=3||!PENDING_HEADER.equals(lines.get(0))||!lines.get(2).startsWith("hmac\t"))throw new SecurityException("Bad authorized SQLite marker");
        String rec=lines.get(1);int tab=rec.indexOf('\t');
        if(tab!=64)throw new SecurityException("Bad authorized SQLite marker record");
        String old=rec.substring(0,tab), rel=rec.substring(tab+1);
        if(rel.length()==0||rel.contains(".."))throw new SecurityException("Bad authorized SQLite marker path");
        String body=lines.get(0)+"\n"+lines.get(1)+"\n";
        byte[] expected=hmac(body.getBytes(StandardCharsets.UTF_8));
        byte[] got;try{got=Base64.getDecoder().decode(lines.get(2).substring(5).trim());}catch(Exception e){throw new SecurityException("Bad authorized SQLite marker tag");}
        if(!MessageDigest.isEqual(expected,got))throw new SecurityException("Authorized SQLite marker was modified manually");
        return new Pending(rel,old);
    }

    private static void clearPending(File root)throws IOException{
        Files.deleteIfExists(new File(root,PENDING_FILE).toPath());
        Files.deleteIfExists(new File(root,PENDING_TEMP_FILE).toPath());
    }

    private static State readVerifiedState(File ntRoot)throws Exception{
        File f=new File(ntRoot,STATE_FILE);
        if(!f.isFile())throw new SecurityException("Database integrity state is missing; rebuild/sign NT once with this project version");
        List<String> lines=Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);
        if(lines.size()<2||!HEADER.equals(lines.get(0)))throw new SecurityException("Bad/old database integrity state; rebuild/sign NT once");
        String last=lines.get(lines.size()-1);
        if(!last.startsWith("hmac\t"))throw new SecurityException("Database integrity state has no authentication tag");
        String supplied=last.substring(5).trim();
        StringBuilder body=new StringBuilder();
        for(int i=0;i<lines.size()-1;i++)body.append(lines.get(i)).append('\n');
        byte[] expected=hmac(body.toString().getBytes(StandardCharsets.UTF_8));
        byte[] got;
        try{got=Base64.getDecoder().decode(supplied);}catch(IllegalArgumentException e){throw new SecurityException("Bad database integrity authentication tag");}
        if(!MessageDigest.isEqual(expected,got))throw new SecurityException("Database integrity state was modified manually");
        Map<String,String> hashes=new TreeMap<String,String>();
        for(int i=1;i<lines.size()-1;i++){
            String x=lines.get(i);if(x.length()==0)continue;
            int tab=x.indexOf('\t');
            if(tab!=64)throw new SecurityException("Bad database integrity state line");
            String hash=x.substring(0,tab), rel=x.substring(tab+1);
            if(rel.length()==0||rel.contains(".."))throw new SecurityException("Bad database path in integrity state");
            hashes.put(rel,hash);
        }
        return new State(hashes);
    }

    private static void writeState(File ntRoot,Map<String,String> hashes)throws Exception{
        File root=ntRoot.getCanonicalFile();
        StringBuilder body=new StringBuilder();body.append(HEADER).append('\n');
        for(Map.Entry<String,String> e:new TreeMap<String,String>(hashes).entrySet())body.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        String tag=Base64.getEncoder().encodeToString(hmac(body.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] all=(body.toString()+"hmac\t"+tag+"\n").getBytes(StandardCharsets.UTF_8);
        Path tmp=new File(root,TEMP_FILE).toPath(), dst=new File(root,STATE_FILE).toPath();
        Files.write(tmp,all,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE);
        try{Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,dst,StandardCopyOption.REPLACE_EXISTING);}
    }

    private static Map<String,String> scanDatabases(File ntRoot)throws Exception{
        final File root=ntRoot.getCanonicalFile();final Path rp=root.toPath();final Map<String,String> m=new TreeMap<String,String>();
        Files.walkFileTree(rp,new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes a)throws IOException{
                String rel=rp.relativize(p).toString().replace('\\','/');
                String low=rel.toLowerCase(java.util.Locale.ROOT);
                if(low.startsWith("sqlites/") && (low.endsWith(".sqlite")||low.endsWith(".sqlite3")||low.endsWith(".db"))){
                    try{m.put(rel,sha256(p));}catch(Exception e){throw new IOException(e);}
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return m;
    }


    private static boolean isProtectedDatabase(File root,File f)throws Exception{
        if(!isInside(root,f))return false;
        String rel=relative(root,f).toLowerCase(java.util.Locale.ROOT);
        return rel.startsWith("sqlites/") && (rel.endsWith(".sqlite")||rel.endsWith(".sqlite3")||rel.endsWith(".db"));
    }

    private static boolean isInside(File root,File f)throws Exception{
        Path r=root.getCanonicalFile().toPath(), p=f.getCanonicalFile().toPath();return p.startsWith(r);
    }
    private static String relative(File root,File f)throws Exception{return root.toPath().relativize(f.toPath()).toString().replace('\\','/');}
    private static byte[] hmac(byte[] data)throws Exception{
        byte[] key=loadKey();Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(data);
    }
    private static byte[] loadKey()throws Exception{
        InputStream in=DbStateGuard.class.getResourceAsStream(KEY_RESOURCE);
        if(in==null)throw new SecurityException("Embedded database-state key is missing; rebuild noon.jar");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[256];int n;try{while((n=in.read(b))>0)out.write(b,0,n);}finally{in.close();}
        byte[] k=out.toByteArray();if(k.length<32)throw new SecurityException("Embedded database-state key is invalid");return k;
    }
    private static String sha256(Path p)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");InputStream in=Files.newInputStream(p);byte[] b=new byte[65536];int n;try{while((n=in.read(b))>0)d.update(b,0,n);}finally{in.close();}
        StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format("%02x",x&255));return s.toString();
    }
    private static final class State{final Map<String,String> hashes;State(Map<String,String> h){hashes=h;}}
    private static final class Pending{final String rel,oldHash;Pending(String r,String h){rel=r;oldHash=h;}}
}
