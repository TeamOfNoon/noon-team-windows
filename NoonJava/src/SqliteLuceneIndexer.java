import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.json.JSONObject;

/** Builds the existing Lucene 5.3 indexes directly from the current NT/sqlites layout. */
public final class SqliteLuceneIndexer {
    public static interface Progress { void onProgress(int processed,int total,int indexed,String message); }
    private SqliteLuceneIndexer() {}

    public static void run(PlainSqliteCatalog.FormDb form, File luceneRoot, Progress progress) throws Exception {
        run(form,luceneRoot,"",progress);
    }

    public static void run(PlainSqliteCatalog.FormDb form, File luceneRoot, String forumTitle, Progress progress) throws Exception {
        run(form,luceneRoot,forumTitle,progress,false);
    }

    /** Manual indexing rebuilds every selected range, including unchanged ready indexes. */
    public static void run(PlainSqliteCatalog.FormDb form, File luceneRoot, String forumTitle, Progress progress, boolean force) throws Exception {
        if(form==null || form.data==null || !form.data.isFile()) throw new IOException("Forum SQLite is not installed");
        String forum= form.family+form.forumId;
        String stamp=databaseStamp(form,forumTitle,fileHash(form.data),form.extra!=null?fileHash(form.extra):"");
        if(!force&&DatLuceneIndexer.Writers.sourceMatches(luceneRoot,forum,stamp)){
            if(progress!=null)progress.onProgress(1,1,0,"Database unchanged; skipped");
            return;
        }
        Class.forName("org.sqlite.JDBC");
        Connection c=SignedBinIntegrity.readOnlySqlite(form.data);
        try{
            if(!hasTable(c,"posts")) throw new IOException("posts table not found: "+form.data.getAbsolutePath());
            final String family="vb".equalsIgnoreCase(form.family)?"vb":("sn".equalsIgnoreCase(form.family)?"sn":"mn");
            final String fid=form.forumId;
            final String section=family+fid;
            final Map<String,String> threadTitles=loadThreadTitles(c);
            final Map<String,String> userNames=loadUserNames(form.extra);
            final Map<String,JSONObject> extraDates=loadDates(form.extra,form.data);
            int total=count(c,"posts"), processed=0, indexed=0;
            if(progress!=null)progress.onProgress(0,total,0,"Reading "+form.data.getName());
            DatLuceneIndexer.Writers w=new DatLuceneIndexer.Writers(section,fid,luceneRoot);
            if(force)w.canSkip=false;
            boolean completed=false;
            try{
                Statement st=c.createStatement();
                ResultSet r=st.executeQuery("SELECT * FROM posts");
                try{
                    ResultSetMetaData md=r.getMetaData();
                    Map<String,Integer> cols=new LinkedHashMap<String,Integer>();
                    for(int i=1;i<=md.getColumnCount();i++)cols.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),i);
                    while(r.next()){
                        if(Thread.currentThread().isInterrupted()) throw new RuntimeException("__LUCENE_STOPPED__");
                        processed++;
                        String postid=get(r,cols,"postid","post_id","id");
                        if(postid.length()==0) continue;
                        String threadid=get(r,cols,"threadid","thread_id","tid");
                        String userid=get(r,cols,"userid","user_id","uid");
                        String username=get(r,cols,"username","user_name","author","poster");
                        if(username.length()==0 && userid.length()>0){String u=userNames.get(userid);if(u!=null)username=u;}
                        String title=get(r,cols,"title","post_title","posttitle","subject");
                        String body=get(r,cols,"long_text","pagetext","content","content_html","body","text","message");

                        String forumid=get(r,cols,"forumid","forum_id","fid");
                        if(forumid.length()==0)forumid=fid;
                        String threadTitle=threadTitles.get(threadid);if(threadTitle==null)threadTitle="";
                        JSONObject o=new JSONObject();
                        o.put("postid",postid);o.put("threadid",threadid);o.put("forumid",forumid);o.put("forum_type",family);
                        o.put("userid",userid);o.put("username",username);o.put("title",title);o.put("thread_title",threadTitle);o.put("pagetext",body);o.put("forum_title",forumTitle==null||forumTitle.trim().length()==0?section:forumTitle);
                        for(String key:OriginalDateFields.KEYS){String v=get(r,cols,key);if(v.isEmpty()&&extraDates.containsKey(postid))v=extraDates.get(postid).optString(key,"");o.put(key,v);}
                        o.put("source_date",get(r,cols,"dateline","date","published","datetime"));
                        o.put("source_edit",get(r,cols,"editdateline","editdate","edited","lastupdate"));
                        String hash=sourceHash(o,family+fid,postid,threadid);
                        if(!w.unchanged(postid,hash)){
                            o.put("source_hash",hash);
                            o.put("pagetext",cleanPostTextForIndex(body));
                            DatLuceneIndexer.indexPost(w,o,postid,threadid,forumid,family);
                        }
                        if(Thread.currentThread().isInterrupted()) throw new RuntimeException("__LUCENE_STOPPED__");
                        w.count++;indexed++;
                        // Report every row so a stop request is observed immediately.
                        if(progress!=null)progress.onProgress(processed,total,indexed,"Indexed posts: "+indexed);
                    }
                } finally {r.close();st.close();}
                if(Thread.currentThread().isInterrupted()) throw new RuntimeException("__LUCENE_STOPPED__");
                if(progress!=null)progress.onProgress(total,total,indexed,"Finalizing "+section);
                if(Thread.currentThread().isInterrupted()) throw new RuntimeException("__LUCENE_STOPPED__");
                w.removeDeleted();w.setSourceStamp(stamp);
                completed=true;
            } finally {
                if(completed) w.close(); else w.abort();
            }
        } finally {c.close();}
    }

    /** Strip forum markup before Lucene sees the post body. */
    static String cleanPostTextForIndex(String raw){
        if(raw==null||raw.length()==0)return "";
        String s=VBulletinBbCode.toHtml(raw);
        // Block BBCode separates paragraphs; inline formatting never splits a word.
        s=s.replaceAll("(?is)\\[(?:/?(?:quote|list|left|center|right|indent|code|php|html|h|hr)|\\*)(?:=[^\\]\\r\\n]*)?\\]", " ");
        s=s.replaceAll("(?is)\\[(?:/?[a-z][a-z0-9_]*)(?:=[^\\]\\r\\n]*)?\\]", "");
        // Convert any stored HTML to plain visible text and decode HTML entities.
        try{s=SearchTextSupport.searchPlainText(s);}catch(Throwable ignore){}
        // Normalize whitespace.
        s=s.replace('\u00A0',' ').replaceAll("\\s+", " ").trim();
        return s;
    }

    private static boolean hasTable(Connection c,String table)throws SQLException{
        PreparedStatement p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND lower(name)=lower(?) LIMIT 1");
        p.setString(1,table);ResultSet r=p.executeQuery();try{return r.next();}finally{r.close();p.close();}
    }
    private static int count(Connection c,String table)throws SQLException{Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM \""+table.replace("\"","\"\"")+"\"");try{return r.next()?r.getInt(1):0;}finally{r.close();s.close();}}
    private static Map<String,JSONObject> loadDates(File extra,File data){
        Map<String,JSONObject> out=new HashMap<String,JSONObject>();if(extra==null||!extra.isFile()||extra.equals(data))return out;
        try(Connection c=SignedBinIntegrity.readOnlySqlite(extra)){
            if(!hasTable(c,"posts"))return out;
            Set<String> columns=new HashSet<String>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info(posts)")){while(r.next())columns.add(r.getString("name"));}
            String id=columns.contains("postid")?"postid":columns.contains("post_id")?"post_id":null;if(id==null)return out;
            String sql="SELECT "+id;for(String key:OriginalDateFields.KEYS)if(columns.contains(key))sql+=",\""+key+"\"";sql+=" FROM posts";
            try(Statement st=c.createStatement();ResultSet r=st.executeQuery(sql)){Map<String,Integer> cols=new HashMap<String,Integer>();ResultSetMetaData md=r.getMetaData();for(int i=1;i<=md.getColumnCount();i++)cols.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),i);while(r.next()){JSONObject o=new JSONObject();for(String key:OriginalDateFields.KEYS)o.put(key,get(r,cols,key));out.put(get(r,cols,id),o);}}
        }catch(Exception ignored){}return out;
    }
    // Android fallback for older indexes missing original-date fields.
    static Map<String,JSONObject> originalDates(PlainSqliteCatalog.FormDb form) throws Exception {
        Map<String,JSONObject> primary=loadDates(form.data,null),extra=loadDates(form.extra,form.data);
        for(Map.Entry<String,JSONObject> entry:extra.entrySet()){
            JSONObject row=primary.get(entry.getKey());
            if(row==null){primary.put(entry.getKey(),entry.getValue());continue;}
            for(String key:OriginalDateFields.KEYS)if(OriginalDateFields.first(row,key).isEmpty())row.put(key,entry.getValue().optString(key,""));
        }
        return primary;
    }
    private static String get(ResultSet r,Map<String,Integer> cols,String...names)throws SQLException{for(String n:names){Integer i=cols.get(n.toLowerCase(Locale.ROOT));if(i!=null){Object v=r.getObject(i.intValue());if(v!=null)return String.valueOf(v);}}return "";}
    private static Map<String,String> loadThreadTitles(Connection c){Map<String,String> out=new HashMap<String,String>();try{if(!hasTable(c,"threads"))return out;Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM threads");try{ResultSetMetaData md=r.getMetaData();Map<String,Integer> cols=new HashMap<String,Integer>();for(int i=1;i<=md.getColumnCount();i++)cols.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),i);while(r.next()){String id=get(r,cols,"threadid","thread_id","id");String t=get(r,cols,"title","thread_title","threadtitle","subject");if(id.length()>0)out.put(id,t);}}finally{r.close();s.close();}}catch(Exception ignore){}return out;}
    private static Map<String,String> loadUserNames(File extra){Map<String,String> out=new HashMap<String,String>();if(extra==null||!extra.isFile())return out;Connection c=null;try{c=SignedBinIntegrity.readOnlySqlite(extra);String table=hasTable(c,"users")?"users":(hasTable(c,"user")?"user":null);if(table==null)return out;Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM \""+table+"\"");try{ResultSetMetaData md=r.getMetaData();Map<String,Integer> cols=new HashMap<String,Integer>();for(int i=1;i<=md.getColumnCount();i++)cols.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT),i);while(r.next()){String id=get(r,cols,"userid","user_id","useid","id");String n=get(r,cols,"username","user_name","name","nickname");if(id.length()>0&&n.length()>0)out.put(id,n);}}finally{r.close();s.close();}}catch(Exception ignore){}finally{try{if(c!=null)c.close();}catch(Exception ignore){}}return out;}
    static String databaseStamp(PlainSqliteCatalog.FormDb form, String title, String dataHash, String extraHash) throws Exception {
        return sourceHash(new JSONObject().put("forum_title", title).put("pagetext", dataHash).put("username", extraHash), form.family + form.forumId, "database", "");
    }
    private static String fileHash(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) { cancelled(); digest.update(buffer,0,n); }
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i=0;i<bytes.length;i++) { int b=bytes[i]&255; out[i*2]=digits[b>>>4]; out[i*2+1]=digits[b&15]; }
        return new String(out);
    }
    private static String sourceHash(JSONObject o, String form, String id, String thread) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        java.util.List<String> fields = new java.util.ArrayList<>();
        fields.add(DatLuceneIndexer.Writers.HASH_VERSION); fields.add(form); fields.add(id); fields.add(thread);
        for (String key : new String[]{"title","username","userid","thread_title","pagetext","forum_title","source_date","source_edit","forumid"}) fields.add(o.optString(key,""));
        for(String key:OriginalDateFields.KEYS)fields.add(o.optString(key,""));
        for (String field : fields) {
            byte[] bytes = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return hex(digest.digest());
    }
    private static void cancelled() throws IOException { if(Thread.currentThread().isInterrupted())throw new IOException("__LUCENE_STOPPED__"); }

}
