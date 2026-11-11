import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** Discovers and reads the plain SQLite layout used by viewer.php. */
final class PlainSqliteCatalog {
    private static final ConcurrentMap<String,Set<String>> COLUMN_CACHE = new ConcurrentHashMap<String,Set<String>>();
    private static final ConcurrentMap<String,Map<Long,Map<String,Object>>> USER_CACHE = new ConcurrentHashMap<String,Map<Long,Map<String,Object>>>();
    private static final ConcurrentMap<String,Set<Long>> IMAM_POST_CACHE = new ConcurrentHashMap<String,Set<Long>>();
    private static final ConcurrentMap<String,String> FORUM_TITLE_CACHE = new ConcurrentHashMap<String,String>();
    private static final Pattern FORM = Pattern.compile("(?i)(?:^|[^a-z0-9])(mn|sn|vb)[_-]?(\\d+)(?=[^0-9]|$)");
    final File nt;
    final File sqliteRoot;
    final SqlTemplateConfig templates;
    final Map<String,FormDb> forms = new LinkedHashMap<String,FormDb>();
    File mnTree, vbTree, countMn, countSn, countVb;

    static final class FormDb {
        String key, family, forumId;
        File data, extra;
    }

    PlainSqliteCatalog(File nt) throws Exception {
        this(nt, System.getProperty("noon.sqlite.path")==null?null:new File(System.getProperty("noon.sqlite.path")));
    }

    // Export uses bin/db directly while retaining the same template rules.
    PlainSqliteCatalog(File nt, File databases) throws Exception {
        this.nt = nt.getCanonicalFile();
        this.templates = new SqlTemplateConfig(this.nt);
        File s = databases == null ? new File(this.nt,"sqlites") : databases;
        this.sqliteRoot = s.isDirectory() ? s.getCanonicalFile() : this.nt;
        scan(this.sqliteRoot);
    }

    private void scan(File ignored) throws Exception {
        // Current NT layout only.  Every installed forum is represented by one
        // directory directly under NT/sqlites named <form>_sqlite containing
        // one matching SQLite file, e.g. mn33_sqlite/mn33.sqlite.
        // fo_tree_sqlite/fo_tree.sqlite is metadata only and never counts as an
        // installed forum.
        File[] dirs=sqliteRoot.listFiles(); if(dirs==null)return;
        Arrays.sort(dirs,new Comparator<File>(){public int compare(File x,File y){return x.getName().compareToIgnoreCase(y.getName());}});
        Pattern folder=Pattern.compile("(?i)^((?:mn|sn|vb)\\d+)_sqlite$");
        for(File d:dirs){
            if(!d.isDirectory())continue;
            String dn=d.getName();
            if("fo_tree_sqlite".equalsIgnoreCase(dn)){
                File t=findNamedSqlite(d,"fo_tree"); if(t!=null){mnTree=t;vbTree=t;}
                continue;
            }
            if("mn_tree_sqlite".equalsIgnoreCase(dn)){File t=findNamedSqlite(d,"mn_tree");if(t!=null)mnTree=t;continue;}
            if("vb_tree_sqlite".equalsIgnoreCase(dn)){File t=findNamedSqlite(d,"vb_tree");if(t!=null)vbTree=t;continue;}
            Matcher fm=folder.matcher(dn); if(!fm.matches())continue;
            String key=fm.group(1).toLowerCase(Locale.ROOT);
            Matcher km=Pattern.compile("(?i)^(mn|sn|vb)(\\d+)$").matcher(key); if(!km.matches())continue;
            File db=findNamedSqlite(d,key); if(db==null)continue;
            // A form DB is valid only if it really contains the viewer data.
            try{
                Class.forName("org.sqlite.JDBC");
                Connection c=SignedBinIntegrity.readOnlySqlite(db);
                try{if(!hasTable(c,"threads")||!hasTable(c,"posts"))continue;}finally{c.close();}
            }catch(Exception e){continue;}
            FormDb fd=new FormDb(); fd.key=key; fd.family=km.group(1).toLowerCase(Locale.ROOT);
            fd.forumId=String.valueOf(Integer.parseInt(km.group(2))); fd.data=db; fd.extra=db;
            forms.put(key,fd);
        }
    }

    private static File findNamedSqlite(File dir,String base)throws IOException{
        String[] ext={".sqlite",".sqlite3",".db"};
        for(String e:ext){File f=new File(dir,base+e);if(f.isFile())return f.getCanonicalFile();}
        return null;
    }

    private boolean hasTable(Connection c,String table)throws SQLException{
        PreparedStatement q=c.prepareStatement(templates.sql("has_table"));
        q.setString(1,table); ResultSet r=q.executeQuery();
        try{return r.next();}finally{r.close();q.close();}
    }
    private boolean tableHasColumn(Connection c,String table,String col)throws SQLException{
        Statement st=c.createStatement(); ResultSet r=st.executeQuery(templates.sql("table_info","table",table.replace("\"","\"\"")));
        try{while(r.next())if(col.equalsIgnoreCase(r.getString("name")))return true;return false;}finally{r.close();st.close();}
    }

    FormDb form(String key){
        if(key==null)return null;
        String requested=key.toLowerCase(Locale.ROOT).trim();
        FormDb direct=forms.get(requested); if(direct!=null)return direct;
        Matcher m=FORM.matcher("_"+requested+"_"); if(!m.find())return null;
        String fam=m.group(1).toLowerCase(Locale.ROOT);
        String fid=String.valueOf(Integer.parseInt(m.group(2)));
        return forms.get(fam+fid);
    }


    static String publicKey(FormDb f){
        if(f==null)return "";
        return ("vb".equals(f.family)?"vb":"mn") + f.forumId;
    }

    Connection open(File f)throws Exception{
        if(f==null||!f.isFile())return null;
        Class.forName("org.sqlite.JDBC");
        Connection c=SignedBinIntegrity.readOnlySqlite(f);
        adaptYearArchive(c);
        try(Statement st=c.createStatement()){
            for(String sql:templates.sql("open_init").split("\\|\\|")){ if(sql.trim().length()>0) st.execute(sql.trim()); }
        }catch(SQLException ignore){}
        return c;
    }

    /** Read the year metadata schema through a connection-local compatibility view.
     *  Source SQLite files remain unchanged; year post IDs are not metadata row IDs. */
    private void adaptYearArchive(Connection c)throws SQLException{
        Set<String> cols=new HashSet<String>();
        try(Statement st=c.createStatement();ResultSet r=st.executeQuery("PRAGMA main.table_info(threads)")){while(r.next())cols.add(r.getString("name").toLowerCase(Locale.ROOT));}
        if(cols.contains("threadid")||!cols.contains("year")||!cols.contains("name")||!cols.contains("type"))return;
        // The no-years archive uses threadid=0 and a postid=0 summary alongside
        // individual posts. Publish positive IDs; the viewer opens its summary only.
        String noYearId="(SELECT id FROM main.threads WHERE lower(type) NOT IN ('hijri','miladi') AND year=0 ORDER BY id LIMIT 1)";
        List<String> postColumns=new ArrayList<String>();
        try(Statement st=c.createStatement();ResultSet r=st.executeQuery("PRAGMA main.table_info(posts)")){
            while(r.next()){
                String name=r.getString("name");
                if("threadid".equalsIgnoreCase(name))postColumns.add("CASE WHEN p.threadid=0 THEN COALESCE("+noYearId+",p.threadid) ELSE p.threadid END AS threadid");
                else if("postid".equalsIgnoreCase(name))postColumns.add("CASE WHEN p.postid=0 THEN (SELECT COALESCE(MAX(postid),0)+1 FROM main.posts) ELSE p.postid END AS postid");
                else postColumns.add("p.\""+name.replace("\"","\"\"")+"\"");
            }
        }
        StringBuilder projection=new StringBuilder();for(String field:postColumns){if(projection.length()>0)projection.append(',');projection.append(field);}
        try(Statement st=c.createStatement()){
            st.execute("CREATE TEMP VIEW posts AS SELECT "+projection+" FROM main.posts p");
        }
        String sql="CREATE TEMP VIEW threads AS SELECT t.*, "
            +"COALESCE((SELECT p.threadid FROM posts p WHERE p.src_link=t.url LIMIT 1),NULLIF(t.year,0),t.id) AS threadid, "
            +"CASE WHEN lower(t.type)='hijri' THEN 'Hijri Year '||t.year WHEN lower(t.type)='miladi' THEN 'Year '||t.year ELSE 'NO_YEAR' END AS title, "
            +"0 AS sticky,0 AS lastpost,0 AS dateline,0 AS userid, "
            +"COALESCE((SELECT p.postid FROM posts p WHERE p.src_link=t.url LIMIT 1),(SELECT p.postid FROM posts p WHERE t.year=0 AND p.threadid=t.id ORDER BY p.position,p.postid LIMIT 1),0) AS firstpostid, "
            +"0 AS replycount FROM main.threads t";
        try(Statement st=c.createStatement()){st.execute(sql);}
        // table_info now resolves the temporary views instead of the source tables.
        String key=c.getMetaData().getURL();COLUMN_CACHE.remove(key+"|threads");COLUMN_CACHE.remove(key+"|posts");
    }

    static Set<String> columns(Connection c,String table,SqlTemplateConfig templates)throws SQLException{
        String key;
        try{key=c.getMetaData().getURL()+"|"+table.toLowerCase(Locale.ROOT);}catch(Exception e){key=String.valueOf(System.identityHashCode(c))+"|"+table.toLowerCase(Locale.ROOT);}
        Set<String> cached=COLUMN_CACHE.get(key); if(cached!=null)return cached;
        Set<String>s=new HashSet<String>(); Statement st=c.createStatement();ResultSet r=st.executeQuery(templates.sql("table_info","table",table.replace("\"","\"\"")));
        try{while(r.next())s.add(r.getString("name").toLowerCase(Locale.ROOT));}finally{r.close();st.close();}
        Set<String> frozen=Collections.unmodifiableSet(s); COLUMN_CACHE.putIfAbsent(key,frozen); return COLUMN_CACHE.get(key);
    }

    static Map<String,Object> row(ResultSet r)throws SQLException{
        Map<String,Object> m=new LinkedHashMap<String,Object>();ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),r.getObject(i));return m;
    }
    static String str(Map<String,Object> m,String... keys){for(String k:keys){Object v=m.get(k.toLowerCase(Locale.ROOT));if(v!=null&&String.valueOf(v).length()>0)return String.valueOf(v);}return "";}
    static String displayText(Map<String,Object> m,String... keys){return cleanDisplayText(str(m,keys));}
    static String cleanDisplayText(String s){
        if(s==null||s.length()==0)return "";
        String x=s;
        for(int i=0;i<3;i++){String y=Parser.unescapeEntities(x,false);if(y.equals(x))break;x=y;}
        if(x.indexOf('<')>=0&&x.indexOf('>')>=0)x=Jsoup.parseBodyFragment(x).text();
        for(int i=0;i<2;i++){String y=Parser.unescapeEntities(x,false);if(y.equals(x))break;x=y;}
        return x.replace('\u00A0',' ').trim();
    }
    static long lng(Map<String,Object> m,String... keys){String s=str(m,keys);try{return Long.parseLong(s);}catch(Exception e){try{return (long)Double.parseDouble(s);}catch(Exception x){return 0;}}}
    static int integer(Map<String,Object> m,String... keys){return (int)lng(m,keys);}

    Map<Long,Map<String,Object>> users(FormDb f)throws Exception{
        Map<Long,Map<String,Object>> empty=new LinkedHashMap<Long,Map<String,Object>>();if(f==null||f.extra==null)return empty;
        String key=f.extra.getCanonicalPath();
        Map<Long,Map<String,Object>> cached=USER_CACHE.get(key);if(cached!=null)return cached;
        Map<Long,Map<String,Object>> out=new LinkedHashMap<Long,Map<String,Object>>();
        Connection c=open(f.extra);try{
            Statement s=c.createStatement();ResultSet r=s.executeQuery(templates.sql("users_all"));
            try{while(r.next()){Map<String,Object>x=row(r);long id=lng(x,"useid","userid","user_id");if(id!=0)out.put(id,x);}}finally{r.close();s.close();}
        }catch(SQLException ignore){}finally{c.close();}
        Map<Long,Map<String,Object>> frozen=Collections.unmodifiableMap(out);
        USER_CACHE.putIfAbsent(key,frozen);return USER_CACHE.get(key);
    }

    Map<String,Object> extraThread(FormDb f,long tid)throws Exception{
        if(f==null||f.extra==null)return null;Connection c=open(f.extra);try{
            if(!columns(c,"threads",templates).contains("threadid"))return null;
            PreparedStatement q=c.prepareStatement(templates.sql("extra_thread_one"));q.setLong(1,tid);ResultSet r=q.executeQuery();
            try{return r.next()?row(r):null;}finally{r.close();q.close();}
        }catch(SQLException ignore){return null;}finally{c.close();}
    }

    Map<Long,Map<String,Object>> extraThreads(FormDb f,Collection<Long> tids)throws Exception{
        Map<Long,Map<String,Object>> out=new LinkedHashMap<Long,Map<String,Object>>();if(f==null||f.extra==null||tids==null||tids.isEmpty())return out;
        Connection c=open(f.extra);try{
            if(!columns(c,"threads",templates).contains("threadid"))return out;
            List<Long> a=new ArrayList<Long>(new LinkedHashSet<Long>(tids));
            int batchSize=templates.integer("sqlite.batch_size");
            for(int from=0;from<a.size();from=(int)templates.math("batch_next","total",a.size(),"from",from,"batch_size",batchSize)){
                int to=(int)templates.math("batch_end","total",a.size(),"from",from,"batch_size",batchSize);StringBuilder ph=new StringBuilder();
                for(int i=from;i<to;i++){if(i>from)ph.append(',');ph.append('?');}
                String sql=templates.sql("extra_threads_in","placeholders",ph.toString());
                PreparedStatement q=c.prepareStatement(sql);for(int i=from;i<to;i++)q.setLong((int)templates.math("bind_index","index",i,"from",from),a.get(i));ResultSet r=q.executeQuery();
                try{while(r.next()){Map<String,Object>x=row(r);out.put(lng(x,"threadid"),x);}}finally{r.close();q.close();}
            }
        }catch(SQLException ignore){}finally{c.close();}return out;
    }

    Map<String,Object> extraPost(FormDb f,long pid)throws Exception{
        if(f==null||f.extra==null)return null;Connection c=open(f.extra);try{
            if(!columns(c,"posts",templates).contains("postid"))return null;
            PreparedStatement q=c.prepareStatement(templates.sql("extra_post_one"));q.setLong(1,pid);ResultSet r=q.executeQuery();
            try{return r.next()?row(r):null;}finally{r.close();q.close();}
        }catch(SQLException ignore){return null;}finally{c.close();}
    }

    Set<Long> imamPostIds(FormDb f)throws Exception{
        if(f==null||f.extra==null)return Collections.emptySet();
        String key=f.extra.getCanonicalPath();Set<Long> cached=IMAM_POST_CACHE.get(key);if(cached!=null)return cached;
        Set<Long> out=new HashSet<Long>();Connection c=open(f.extra);try{
            Set<String> cols=columns(c,"posts",templates);String uid=null;for(String k:templates.list("columns.post_userid"))if(cols.contains(k)){uid=k;break;}
            if(uid!=null&&cols.contains("postid")){
                Statement st=c.createStatement();ResultSet r=st.executeQuery(templates.sql("imam_posts","uid_column",uid,"imam_userid",templates.integer("imam.userid")));
                try{while(r.next())out.add(r.getLong(1));}finally{r.close();st.close();}
            }
        }catch(SQLException ignore){}finally{c.close();}
        Set<Long> frozen=Collections.unmodifiableSet(out);IMAM_POST_CACHE.putIfAbsent(key,frozen);return IMAM_POST_CACHE.get(key);
    }

    Map<Long,Map<String,Object>> extraPosts(FormDb f)throws Exception{
        Map<Long,Map<String,Object>> out=new LinkedHashMap<Long,Map<String,Object>>();if(f==null||f.extra==null)return out;Connection c=open(f.extra);try{Statement s=c.createStatement();ResultSet r=s.executeQuery(templates.sql("extra_posts_all"));try{while(r.next()){Map<String,Object>x=row(r);out.put(lng(x,"postid"),x);}}finally{r.close();s.close();}}catch(SQLException ignore){}finally{c.close();}return out;
    }

    Map<Long,Map<String,Object>> extraThreads(FormDb f)throws Exception{
        Map<Long,Map<String,Object>> out=new LinkedHashMap<Long,Map<String,Object>>();if(f==null||f.extra==null)return out;Connection c=open(f.extra);try{Statement s=c.createStatement();ResultSet r=s.executeQuery(templates.sql("extra_threads_all"));try{while(r.next()){Map<String,Object>x=row(r);out.put(lng(x,"threadid"),x);}}finally{r.close();s.close();}}catch(SQLException ignore){}finally{c.close();}return out;
    }

    String forumTitle(FormDb f)throws Exception{
        if(f==null)return "";File tree="vb".equals(f.family)?vbTree:mnTree;if(tree==null)return "";
        String key=tree.getCanonicalPath()+"|"+f.forumId;String cached=FORUM_TITLE_CACHE.get(key);if(cached!=null)return cached;
        String title="";Connection c=open(tree);try{
            Set<String> cols=columns(c,"forums",templates);
            String idCol=cols.contains("forumid")?"forumid":(cols.contains("id")?"id":null);
            String titleCol=cols.contains("title")?"title":(cols.contains("name")?"name":null);
            if(idCol!=null&&titleCol!=null){
                PreparedStatement p=c.prepareStatement("SELECT \""+titleCol.replace("\"","\"\"")+"\" FROM forums WHERE \""+idCol.replace("\"","\"\"")+"\"=? LIMIT 1");
                p.setString(1,f.forumId);ResultSet r=p.executeQuery();try{title=r.next()?String.valueOf(r.getString(1)):"";}finally{r.close();p.close();}
            }else{
                PreparedStatement p=c.prepareStatement(templates.sql("forum_title"));p.setString(1,f.forumId);ResultSet r=p.executeQuery();try{title=r.next()?String.valueOf(r.getString(1)):"";}finally{r.close();p.close();}
            }
        }finally{c.close();}
        FORUM_TITLE_CACHE.putIfAbsent(key,title);return FORUM_TITLE_CACHE.get(key);
    }
}
