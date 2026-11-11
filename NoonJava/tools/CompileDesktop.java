import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import javax.tools.*;

/** Finds the Native Image API in this GraalVM installation, not a fixed SDK filename. */
public class CompileDesktop {
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("CompileDesktop GRAALVM_HOME SOURCE OUTPUT");
  Path home=Paths.get(args[0]).toRealPath();
  List<String> cp=new ArrayList<String>();cp.add(new File(args[2]).getAbsolutePath());
  Path dependencyDir=Paths.get("lib");
  if(Files.isDirectory(dependencyDir))try(Stream<Path> jars=Files.list(dependencyDir)){
   for(Path jar:jars.filter(j->j.toString().endsWith(".jar")).sorted().collect(Collectors.toList()))cp.add(jar.toAbsolutePath().toString());
  }
  boolean api=false;Set<String> modules=new LinkedHashSet<String>();
  Path lib=home.resolve("lib");
  try(Stream<Path> paths=Files.walk(lib)){
   for(Path p:paths.filter(f->f.toString().endsWith(".jar")).sorted().collect(Collectors.toList())){
    try(ZipFile z=new ZipFile(p.toFile())){
     boolean nativeApi=z.getEntry("org/graalvm/nativeimage/c/CContext.class")!=null;
     boolean wordApi=z.getEntry("org/graalvm/word/PointerBase.class")!=null;
     boolean collections=z.getEntry("org/graalvm/collections/EconomicMap.class")!=null;
     if(nativeApi||wordApi||collections){cp.add(p.toAbsolutePath().toString());api|=nativeApi;System.out.println("[SDK] "+p);}
    }
   }
  }
  // Some GraalVM releases put their API in the JDK runtime image instead of standalone jars.
  Path runtimeModules=FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
  try(Stream<Path> paths=Files.list(runtimeModules)){
   for(Path m:paths.collect(Collectors.toList())){
    if(Files.exists(m.resolve("org/graalvm/nativeimage/c/CContext.class"))){modules.add(m.getFileName().toString());api=true;}
    if(Files.exists(m.resolve("org/graalvm/word/PointerBase.class")))modules.add(m.getFileName().toString());
   }
  }
  if(!api)throw new IOException("Native Image API not found under "+home+" or in its runtime modules. Use a complete GraalVM installation with native-image.");
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  if(compiler==null)throw new IOException("GraalVM JDK compiler missing");
  Files.createDirectories(Paths.get(args[2]));
  List<String> options=new ArrayList<String>(Arrays.asList("-encoding","UTF-8","-cp",String.join(File.pathSeparator,cp),"-d",args[2],"-h",Paths.get(args[2]).toAbsolutePath().getParent().toString()));
  if(!modules.isEmpty()){
   System.out.println("[SDK modules] "+String.join(",",modules));
   options.add("--add-modules");options.add(String.join(",",modules));
  }
  Path source=Paths.get(args[1]);
  if(Files.isDirectory(source)){try(Stream<Path> sources=Files.walk(source)){for(Path f:sources.filter(p->p.toString().endsWith(".java")).sorted().collect(Collectors.toList()))options.add(f.toString());}}
  else options.add(args[1]);
  int result=compiler.run(null,System.out,System.err,options.toArray(new String[0]));
  if(result!=0)System.exit(result);
 }
}
