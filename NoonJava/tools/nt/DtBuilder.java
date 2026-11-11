import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Plain dtbuild: no compression, no encryption. App assets + SQLite are copied normally and then signed by Start. */
public final class DtBuilder {
    private DtBuilder(){}

    public static void run(File app,File dbDir,File nt)throws Exception{
        app=normalizeAppRoot(app);
        dbDir=normalizeDbRoot(dbDir,app);
        nt=nt.getCanonicalFile();
        if(!app.isDirectory())throw new IOException("App folder not found: "+app);
        if(!dbDir.isDirectory())throw new IOException("Database folder not found: "+dbDir);
        if(!nt.exists()&&!nt.mkdirs())throw new IOException("Cannot create "+nt);

        File appDat=new File(nt,"app.dat");
        replaceDirectory(appDat);
        copyApp(app,appDat);
        File startupPage=new File(appDat,"dir.html");
        if(!startupPage.isFile())throw new IOException("Required NT startup page not found: "+startupPage+" (expected source app/dir.html)");

        File sqlites=new File(nt,"sqlites");
        replaceDirectory(sqlites);
        copySqlites(dbDir,sqlites);

        // Editable source templets live beside bin/app and are copied into NT.
        File binRoot=app.getParentFile();
        File templateSource=new File(binRoot,"templets");
        if(!templateSource.isDirectory()&&"NT".equalsIgnoreCase(binRoot.getName())){
            File projectTemplates=new File(binRoot.getParentFile(),"templets");
            if(projectTemplates.isDirectory())templateSource=projectTemplates;
        }
        File templateOut=new File(nt,"templets");
        if(!templateSource.isDirectory())throw new IOException("Template folder not found: "+templateSource);
        replaceDirectory(templateOut);
        copyTree(templateSource,templateOut);

        // content.sqlite is intentionally mutable after verified remote updates.
        // Seed its authenticated runtime state every time TD/DT build creates NT.
        DbStateGuard.initialize(nt);

        System.out.println("[PLAIN-DTBUILD-V2]");
        System.out.println("[OK] plain app DAT folder: "+appDat);
        System.out.println("[OK] startup page: "+startupPage+" -> http://localhost:<port>/dir.html");
        System.out.println("[OK] plain SQLite folder:  "+sqlites);
        System.out.println("[OK] DB layout preserved exactly (root + subfolders)");
        System.out.println("[OK] app/www/viewer excluded from NT (reference/source only)");
        System.out.println("[OK] templets: "+new File(nt,"templets"));
        System.out.println("[OK] no compression / no encryption");
    }

    /** Copy an additional dtbuild --in item into the NT root.
     *  Files are copied as NT/<basename>. Directories are copied as
     *  NT/<directory-name>/... so an extra input cannot silently flatten
     *  or overwrite the normal app/sqlites layout.
     */
    public static void copyExtraInput(File input,File nt)throws Exception{
        input=input.getCanonicalFile();
        nt=nt.getCanonicalFile();
        if(!input.exists())throw new IOException("Extra input not found: "+input);
        if(!nt.exists()&&!nt.mkdirs())throw new IOException("Cannot create "+nt);

        if(input.isFile()){
            File out=new File(nt,input.getName());
            Files.copy(input.toPath(),out.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            System.out.println("[OK] extra input file: "+input+" -> "+out);
            return;
        }
        if(input.isDirectory()){
            File out=new File(nt,input.getName());
            if(out.exists())deleteTree(out.toPath());
            copyTree(input,out);
            System.out.println("[OK] extra input folder: "+input+" -> "+out);
            return;
        }
        throw new IOException("Unsupported extra input: "+input);
    }

    public static void restoreApp(File nt,File out)throws Exception{
        File src=new File(nt.getCanonicalFile(),"app.dat");
        if(!src.isDirectory())throw new IOException("Plain app.dat folder not found: "+src);
        out=out.getCanonicalFile();
        File existingConfig=new File(out,"www/res/config.js");
        byte[] savedConfig=existingConfig.isFile()?Files.readAllBytes(existingConfig.toPath()):null;
        replaceDirectory(out);
        final Path root=src.toPath(),to=out.toPath();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{Files.createDirectories(to.resolve(root.relativize(d)));return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Path x=to.resolve(root.relativize(f));if(x.getParent()!=null)Files.createDirectories(x.getParent());Files.copy(f,x,StandardCopyOption.REPLACE_EXISTING);return FileVisitResult.CONTINUE;}
        });
        if(savedConfig!=null){File restoredConfig=new File(out,"www/res/config.js");if(restoredConfig.getParentFile()!=null)Files.createDirectories(restoredConfig.getParentFile().toPath());Files.write(restoredConfig.toPath(),savedConfig);}
    }

    public static void restoreDb(File nt,File out)throws Exception{
        File src=new File(nt.getCanonicalFile(),"sqlites");
        if(!src.isDirectory())throw new IOException("Plain SQLite folder not found: "+src);
        out=out.getCanonicalFile();replaceDirectory(out);
        final Path root=src.toPath(),to=out.toPath();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{Files.createDirectories(to.resolve(root.relativize(d)));return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Path x=to.resolve(root.relativize(f));if(x.getParent()!=null)Files.createDirectories(x.getParent());Files.copy(f,x,StandardCopyOption.REPLACE_EXISTING);return FileVisitResult.CONTINUE;}
        });
    }

    private static void replaceDirectory(File d)throws IOException{
        if(d.exists())deleteTree(d.toPath());
        Files.createDirectories(d.toPath());
    }

    private static void copyApp(final File src,final File dst)throws IOException{
        final Path root=src.toPath(),to=dst.toPath();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException{
                Path rel=root.relativize(dir);
                if(hasIndexPhpSegment(rel)||isGeneratedViewerData(rel)||isGeneratedJsIndex(rel))return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(rel));return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException{
                Path rel=root.relativize(file);
                if(hasIndexPhpSegment(rel)||"index.php".equalsIgnoreCase(file.getFileName().toString()))return FileVisitResult.CONTINUE;
                Path out=to.resolve(rel);if(out.getParent()!=null)Files.createDirectories(out.getParent());Files.copy(file,out,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean hasIndexPhpSegment(Path rel){for(Path p:rel)if("index.php".equalsIgnoreCase(p.toString()))return true;return false;}

    /** Lucene supplies search indexes; do not embed the generated JS index data. */
    private static boolean isGeneratedJsIndex(Path rel){
        if(rel==null)return false;
        for(Path part:rel)if("whxdata_js".equalsIgnoreCase(part.toString()))return true;
        return false;
    }

    /** app/www/viewer contains generated legacy forum/post JS used only as a
     *  reference/source snapshot. Runtime data is generated directly from SQLite,
     *  so never package this large folder into NT. Keep viewer.html + res/viewer.
     */
    private static boolean isGeneratedViewerData(Path rel){
        if(rel==null||rel.getNameCount()<2)return false;
        return "www".equalsIgnoreCase(rel.getName(0).toString()) &&
               "viewer".equalsIgnoreCase(rel.getName(1).toString());
    }

    private static File normalizeAppRoot(File input)throws IOException{
        File f=input.getCanonicalFile();
        if(new File(f,"www").isDirectory())return f;
        File nested=new File(f,"app");
        if(nested.isDirectory()&&new File(nested,"www").isDirectory())return nested.getCanonicalFile();
        return f;
    }

    private static File normalizeDbRoot(File input,File appRoot)throws IOException{
        File f=input==null?null:input.getCanonicalFile();
        if(f!=null&&f.isDirectory()){
            File nested=new File(f,"db_sqlite");
            if(nested.isDirectory())return nested.getCanonicalFile();
            nested=new File(f,"db");
            if(nested.isDirectory()&&!containsSqliteDirectlyOrBelow(f))return nested.getCanonicalFile();
            return f;
        }
        // New compact project layout: app and db are siblings.
        File parent=appRoot==null?null:appRoot.getParentFile();
        if(parent!=null){
            File sibling=new File(parent,"db");if(sibling.isDirectory())return sibling.getCanonicalFile();
            sibling=new File(parent,"db_sqlite");if(sibling.isDirectory())return sibling.getCanonicalFile();
        }
        return f==null?new File("db").getCanonicalFile():f;
    }

    private static boolean containsSqliteDirectlyOrBelow(File dir){
        if(dir==null||!dir.isDirectory())return false;
        File[] a=dir.listFiles();if(a==null)return false;
        for(File x:a){String n=x.getName().toLowerCase(Locale.ROOT);if(x.isFile()&&(n.endsWith(".sqlite")||n.endsWith(".sqlite3")||n.endsWith(".db")))return true;}
        return false;
    }

    private static void copySqlites(final File src,final File dst)throws IOException{
        final Path root=src.toPath();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException{
                String n=file.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean stateMeta=n.equals("state.json.js");
                // Count metadata is SQLite-only. Do not package legacy count_*.json.js sidecars.
                if(!(n.endsWith(".sqlite")||n.endsWith(".sqlite3")||n.endsWith(".db")||stateMeta))return FileVisitResult.CONTINUE;
                Path rel=root.relativize(file);
                Path out=dst.toPath().resolve(rel);
                if(out.getParent()!=null)Files.createDirectories(out.getParent());
                if(stateMeta)Files.copy(file,out,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
                else snapshotSqlite(file,out);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** SQLite backup includes committed WAL pages, without changing schema or rows.
     *  Only the staged output is changed; signing runs after this method succeeds. */
    static void snapshotSqlite(Path source,Path destination)throws IOException{
        Path temporary=null;
        try{
            Class.forName("org.sqlite.JDBC");
            temporary=Files.createTempFile(destination.getParent(),".noon-snapshot-",".sqlite");
            org.sqlite.SQLiteConfig read=new org.sqlite.SQLiteConfig();read.setReadOnly(true);read.setBusyTimeout(5000);
            String sourceUrl="jdbc:sqlite:"+source.toRealPath().toUri().toASCIIString()+"?mode=ro";
            try(java.sql.Connection input=java.sql.DriverManager.getConnection(sourceUrl,read.toProperties())){
                int result=((org.sqlite.SQLiteConnection)input).getDatabase().backup("main",temporary.toString(),null,100,50,100);
                if(result!=0)throw new java.sql.SQLException("SQLite backup failed: "+result);
            }
            // Make the staged snapshot standalone. Never checkpoint the source.
            try(java.sql.Connection output=java.sql.DriverManager.getConnection("jdbc:sqlite:"+temporary.toString());
                java.sql.Statement query=output.createStatement()){
                try(java.sql.ResultSet mode=query.executeQuery("PRAGMA journal_mode=DELETE")){
                    if(!mode.next()||!"delete".equalsIgnoreCase(mode.getString(1)))throw new IOException("Cannot make a standalone SQLite snapshot");
                }
                try(java.sql.ResultSet check=query.executeQuery("PRAGMA integrity_check")){
                    if(!check.next()||!"ok".equalsIgnoreCase(check.getString(1))||check.next())throw new IOException("SQLite snapshot failed integrity_check");
                }
            }
            Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);temporary=null;
            System.out.println("[OK] standalone SQLite snapshot: "+source.getFileName());
        }catch(Exception failure){
            throw new IOException("Cannot package database "+source+": "+failure.getMessage()+
                ". Source database was not rewritten. Missing/corrupt source pages cannot be restored by signing; provide a complete source database (and its WAL if present).",failure);
        }finally{
            if(temporary!=null){Files.deleteIfExists(temporary);Files.deleteIfExists(Paths.get(temporary+"-wal"));Files.deleteIfExists(Paths.get(temporary+"-shm"));Files.deleteIfExists(Paths.get(temporary+"-journal"));}
        }
    }

    private static void copyTree(final File src,final File dst)throws IOException{
        final Path root=src.toPath(),to=dst.toPath();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a)throws IOException{Files.createDirectories(to.resolve(root.relativize(d)));return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Path o=to.resolve(root.relativize(f));if(o.getParent()!=null)Files.createDirectories(o.getParent());Files.copy(f,o,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);return FileVisitResult.CONTINUE;}
        });
    }
    private static void deleteTree(Path p)throws IOException{Files.walkFileTree(p,new SimpleFileVisitor<Path>(){public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Files.deleteIfExists(f);return FileVisitResult.CONTINUE;}public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{Files.deleteIfExists(d);return FileVisitResult.CONTINUE;}});}
}
