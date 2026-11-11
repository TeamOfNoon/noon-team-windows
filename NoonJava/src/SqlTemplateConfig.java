import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** SQLite/JS generation rules. Built-in defaults drive the desktop directly from SQLite; an optional signed NT/templets/sqlite-js.properties may override them. */
final class SqlTemplateConfig {
    private final Properties p = new Properties();
    private final File source;

    SqlTemplateConfig(File nt) throws Exception {
        File f = new File(new File(nt.getCanonicalFile(), "templets"), "sqlite-js.properties");
        this.source=f;
        // Runtime data is never taken from this properties file.  These are only
        // route/query/wrapper rules.  Actual forum/thread/post data is read from
        // NT/sqlites by PlainSqliteCatalog + DynamicJs for every requested JS file.
        DefaultSqlTemplates.apply(p);
        if(f.isFile()){
            InputStream in=new FileInputStream(f);
            try{ p.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
            finally{ in.close(); }
        }
    }

    String get(String key) {
        String v=p.getProperty(key);
        if(v==null) throw new IllegalStateException("Missing template key '"+key+"' in "+source);
        return decode(v);
    }

    String sql(String key, Object... kv) {
        String s=get("sql."+key);
        if(kv!=null){
            if((kv.length&1)!=0) throw new IllegalArgumentException("Template replacements must be key/value pairs");
            for(int i=0;i<kv.length;i+=2)s=s.replace("${"+String.valueOf(kv[i])+"}", String.valueOf(kv[i+1]));
        }
        return s;
    }

    String js(String key){ return get("js."+key); }

    String render(String namespace,String key,Object... kv) {
        String s=get(namespace+"."+key);
        if(kv!=null){
            if((kv.length&1)!=0) throw new IllegalArgumentException("Template replacements must be key/value pairs");
            for(int i=0;i<kv.length;i+=2)s=s.replace("${"+String.valueOf(kv[i])+"}", String.valueOf(kv[i+1]));
        }
        return s;
    }

    String js(String key,Object... kv){ return render("js",key,kv); }

    String file(String name) throws Exception {
        if(name==null || name.indexOf("..")>=0 || name.indexOf('/')>=0 || name.indexOf('\\')>=0)
            throw new IllegalArgumentException("Unsafe templet file name: "+name);
        File f=new File(source.getParentFile(),name);
        if(!f.isFile()) throw new FileNotFoundException("Signed templet file not found: "+f);
        byte[] b=java.nio.file.Files.readAllBytes(f.toPath());
        return new String(b,StandardCharsets.UTF_8);
    }
    String expr(String key,Object... kv){ return render("expr",key,kv); }
    String route(String key){ return get("route."+key); }
    String output(String key,Object... kv){ return render("output",key,kv); }
    int integer(String key){ return Integer.parseInt(get("config."+key).trim()); }
    String config(String key){ return get("config."+key); }
    long math(String key,Object... kv){ java.util.Map<String,Long> vars=new java.util.HashMap<String,Long>(); if(kv!=null){if((kv.length&1)!=0)throw new IllegalArgumentException("Math variables must be key/value pairs");for(int i=0;i<kv.length;i+=2)vars.put(String.valueOf(kv[i]),Long.parseLong(String.valueOf(kv[i+1])));} return TemplateMath.eval(get("math."+key),vars); }
    String[] list(String key){
        String raw=get("config."+key).trim(); if(raw.length()==0)return new String[0];
        String[] a=raw.split("\\s*,\\s*"); return a;
    }

    private static String decode(String s){
        return s.replace("\\n","\n").replace("\\r","\r").replace("\\t","\t");
    }
}
