import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import org.json.*;

/** Generates the legacy index.php JavaScript directly from the plain SQLite databases. */
final class DynamicJs {
    private static final ConcurrentMap<String,CatalogEntry> CATALOGS=new ConcurrentHashMap<String,CatalogEntry>();
    private static final class CatalogEntry{
        final long stamp; final PlainSqliteCatalog catalog;
        CatalogEntry(long stamp,PlainSqliteCatalog catalog){this.stamp=stamp;this.catalog=catalog;}
    }
    private DynamicJs(){}

    // Cheap recursive fingerprint.  Adding/replacing/removing a SQLite file now
    // refreshes the catalog automatically instead of requiring an app restart.
    private static long sqliteStamp(File f){
        if(f==null||!f.exists())return 0L;
        if(f.isFile()){
            String n=f.getName().toLowerCase(Locale.ROOT);
            if(!(n.endsWith(".sqlite")||n.endsWith(".sqlite3")||n.endsWith(".db")))return 0L;
            return f.lastModified() ^ (f.length()<<1) ^ n.hashCode();
        }
        long h=f.lastModified();File[] a=f.listFiles();if(a==null)return h;
        Arrays.sort(a,new Comparator<File>(){public int compare(File x,File y){return x.getName().compareToIgnoreCase(y.getName());}});
        for(File x:a)h=31*h+sqliteStamp(x);
        return h;
    }
    private static PlainSqliteCatalog catalog(File nt)throws Exception{
        String key=nt.getCanonicalPath();
        File sqliteDir=new File(nt,"sqlites");
        long stamp=sqliteStamp(sqliteDir.isDirectory()?sqliteDir:nt);
        CatalogEntry old=CATALOGS.get(key);
        if(old!=null&&old.stamp==stamp)return old.catalog;
        PlainSqliteCatalog fresh=new PlainSqliteCatalog(nt);
        CATALOGS.put(key,new CatalogEntry(stamp,fresh));
        return fresh;
    }

    static byte[] render(File nt,String path)throws Exception{
        String p=path.replace('\\','/');
        PlainSqliteCatalog cat=catalog(nt);
        if(p.endsWith(cat.templates.route("index_mn_suffix")))return index(cat,false);
        if(p.endsWith(cat.templates.route("index_vb_suffix")))return index(cat,true);
        Matcher m=Pattern.compile(cat.templates.route("post"),Pattern.CASE_INSENSITIVE).matcher(p);if(m.matches())return post(cat,m.group(1),Long.parseLong(m.group(2)));
        m=Pattern.compile(cat.templates.route("thread"),Pattern.CASE_INSENSITIVE).matcher(p);if(m.matches())return thread(cat,m.group(1),Long.parseLong(m.group(2)),Integer.parseInt(m.group(3)));
        m=Pattern.compile(cat.templates.route("forum"),Pattern.CASE_INSENSITIVE).matcher(p);if(m.matches())return forum(cat,m.group(1),Integer.parseInt(m.group(2)),m.group(3),false);
        m=Pattern.compile(cat.templates.route("sticky"),Pattern.CASE_INSENSITIVE).matcher(p);if(m.matches())return forum(cat,m.group(1),1,m.group(2),true);
        return null;
    }


    static byte[] vb5IndexHtml(File nt)throws Exception{
        PlainSqliteCatalog cat=catalog(nt);PlainSqliteCatalog.FormDb f=cat.form("vb5");
        if(f==null||f.data==null)return null;
        Connection c=cat.open(f.data);try{
            List<Map<String,Object>> rows=vb5YearRows(c,cat.templates);
            String title=cat.forumTitle(f);if(title==null||title.trim().length()==0)title="VB5 Year Archive";
            StringBuilder miladi=new StringBuilder(),hijri=new StringBuilder(),noYear=new StringBuilder();
            for(Map<String,Object>x:rows){long tid=PlainSqliteCatalog.lng(x,"threadid");String g=vb5YearGroup(x),label=vb5YearLabel(x);StringBuilder b="miladi".equals(g)?miladi:("hijri".equals(g)?hijri:noYear);b.append("<a class=\"year\" href=\"/www/viewer.html?fo=5&amp;ftype=vb&amp;th=").append(tid).append("\">").append(html(label)).append("</a>");}
            String css="body{margin:0;background:#f5f6f8;color:#1f2937;font-family:Arial,Tahoma,sans-serif}main{max-width:980px;margin:24px auto;padding:0 16px}.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px}.back{color:#2563eb;text-decoration:none}.title{font-size:24px;font-weight:700;margin:0}.group{background:#fff;border:1px solid #e5e7eb;border-radius:12px;margin:14px 0;padding:18px;box-shadow:0 1px 2px rgba(0,0,0,.04)}.group h2{font-size:18px;margin:0 0 14px}.years{display:grid;grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:9px}.year{display:block;text-align:center;padding:10px 8px;border:1px solid #d7dce2;border-radius:8px;background:#fafafa;color:#111827;text-decoration:none;font-weight:700}.year:hover{background:#eef2f7;border-color:#aeb8c5}.count{font-size:12px;color:#6b7280;font-weight:400;margin-inline-start:6px}@media(max-width:600px){main{margin:12px auto}.years{grid-template-columns:repeat(3,1fr)}}";
            String html="<!doctype html><html dir=\"rtl\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+html(title)+"</title><style>"+css+"</style></head><body><main><div class=\"top\"><h1 class=\"title\">"+html(title)+"</h1><a class=\"back\" href=\"../index.html\">Back</a></div>"+
                groupHtml("السنوات الهجرية (Hijri Years)",hijri,groupCount(rows,"hijri"))+groupHtml("السنوات الميلادية (Miladi Years)",miladi,groupCount(rows,"miladi"))+groupHtml("بدون سنة (No Year)",noYear,groupCount(rows,"no_year"))+"</main></body></html>";
            return html.getBytes(StandardCharsets.UTF_8);
        }finally{c.close();}
    }

    private static String groupHtml(String name,StringBuilder links,int count){return "<section class=\"group\"><h2>"+name+" <span class=\"count\">("+count+")</span></h2><div class=\"years\">"+links.toString()+"</div></section>";}
    private static int groupCount(List<Map<String,Object>> rows,String group){int n=0;for(Map<String,Object>x:rows)if(group.equals(vb5YearGroup(x)))n++;return n;}
    private static String html(String s){if(s==null)return "";return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}

    static void extractAll(File nt,File appRoot)throws Exception{
        extractAll(catalog(nt),appRoot);
    }

    static void extractAll(PlainSqliteCatalog cat,File appRoot)throws Exception{
        File root=new File(new File(appRoot,"www"),"viewer");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create "+root);
        write(root,cat.templates.output("index_mn"),index(cat,false));
        write(root,cat.templates.output("index_vb"),index(cat,true));
        for(PlainSqliteCatalog.FormDb f:cat.forms.values()){
            if(f.data==null)continue;
            String publicKey=PlainSqliteCatalog.publicKey(f);
            File base=new File(root,"forum_"+publicKey+File.separator+"js_json");
            File forums=new File(base,"forums"),threads=new File(base,"threads"),posts=new File(base,"posts");
            forums.mkdirs();threads.mkdirs();posts.mkdirs();
            Connection c=cat.open(f.data);
            try{
                int normal=countNormalThreads(c,cat.templates);
                int forumPageSize=cat.templates.integer("forum.page_size"); int pages=isVb5(f)?1:(int)cat.templates.math("last_page","total",normal,"page_size",forumPageSize);
                for(String order:new String[]{cat.templates.config("order.asc_token"),cat.templates.config("order.desc_token")}){
                    write(forums,cat.templates.output("sticky","publicKey",publicKey,"order",order),forum(cat,publicKey,1,order,true));
                    for(int pg=(int)cat.templates.math("first_page");pg<=pages;pg=(int)cat.templates.math("page_loop_next","page",pg))write(forums,cat.templates.output("forum","publicKey",publicKey,"page",pg,"order",order),forum(cat,publicKey,pg,order,false));
                }
                Statement st=c.createStatement();ResultSet r=st.executeQuery(cat.templates.sql("extract_thread_ids"));
                try{while(r.next()){long tid=r.getLong(1);int threadPageSize=cat.templates.integer("thread.page_size");int tp=isVb5(f)?1:(int)cat.templates.math("last_page","total",postIds(c,tid,cat.templates).size(),"page_size",threadPageSize);for(int pg=(int)cat.templates.math("first_page");pg<=tp;pg=(int)cat.templates.math("page_loop_next","page",pg))write(threads,cat.templates.output("thread","threadid",tid,"page",pg),thread(cat,publicKey,tid,pg));}}finally{r.close();st.close();}
                st=c.createStatement();r=st.executeQuery(cat.templates.sql("extract_post_ids"));
                try{while(r.next()){long pid=r.getLong(1);write(posts,cat.templates.output("post","postid",pid),post(cat,publicKey,pid));}}finally{r.close();st.close();}
            }finally{c.close();}
            System.out.println("[OK] generated JS from SQLite: "+publicKey+" <- "+f.key);
        }
    }

    private static byte[] index(PlainSqliteCatalog cat,boolean vb)throws Exception{
        File tree=vb?cat.vbTree:cat.mnTree;
        JSONArray forums=new JSONArray();
        boolean installedFamily=false;
        for(PlainSqliteCatalog.FormDb f:cat.forms.values()){
            if(f.data!=null && (vb?"vb".equals(f.family):"mn".equals(f.family))){installedFamily=true;break;}
        }
        if(tree!=null && installedFamily){
            Set<String> available=new HashSet<String>();
            Connection c=cat.open(tree);try{
                List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
                Map<String,Map<String,Object>> byId=new HashMap<String,Map<String,Object>>();
                Statement s=c.createStatement();ResultSet r=null;
                try{r=s.executeQuery(cat.templates.sql("index_forums"));}
                catch(SQLException oldSchema){r=s.executeQuery("SELECT * FROM forums ORDER BY display_order, forumid");}
                try{while(r.next()){Map<String,Object>x=PlainSqliteCatalog.row(r);rows.add(x);byId.put(PlainSqliteCatalog.str(x,"forumid","id"),x);}}finally{r.close();s.close();}

                // Mark a forum clickable only when the SAME public route can really
                // resolve to a data SQLite. For MN this intentionally aliases SN too.
                for(Map<String,Object> x:rows){
                    String id=PlainSqliteCatalog.str(x,"forumid","id");
                    PlainSqliteCatalog.FormDb fd=cat.form((vb?"vb":"mn")+id);
                    if(fd!=null && fd.data!=null)available.add(id);
                }

                // Export every forum row present in the SQLite metadata tree.
                // Installed content is tracked separately by has_sqlite; the
                // app config controls which forums are shown to the user.
                Set<String> keep=new HashSet<String>(byId.keySet());

                Map<String,long[]> counters=loadCounters(cat,vb);
                Map<String,List<String>> children=new HashMap<String,List<String>>();
                for(Map<String,Object>x:rows){
                    String id=PlainSqliteCatalog.str(x,"forumid","id");if(!keep.contains(id))continue;
                    String parent=PlainSqliteCatalog.str(x,"parent_forumid","parentid");
                    if(parent.length()==0)parent="-1";
                    List<String> ch=children.get(parent);if(ch==null){ch=new ArrayList<String>();children.put(parent,ch);}ch.add(id);
                }

                for(Map<String,Object>x:rows){
                    String id=PlainSqliteCatalog.str(x,"forumid","id");if(!keep.contains(id))continue;
                    String parent=PlainSqliteCatalog.str(x,"parent_forumid","parentid");
                    if(parent.length()==0 || !keep.contains(parent))parent="-1";
                    long[] ct=counters.get(id);
                    long threadCount=ct==null?0:ct[0], postCount=ct==null?0:ct[1];
                    // For an installed optional form, prefer live DB counts. This makes
                    // a newly installed form appear in the preview tree with its real
                    // counts immediately, without requiring a browser/page refresh.
                    PlainSqliteCatalog.FormDb liveFd=cat.form((vb?"vb":"mn")+id);
                    if(liveFd!=null&&liveFd.data!=null){
                        Connection dc=cat.open(liveFd.data);try{
                            Statement ds=dc.createStatement();ResultSet dr=ds.executeQuery("SELECT COUNT(*) FROM threads");try{if(dr.next())threadCount=dr.getLong(1);}finally{dr.close();ds.close();}
                            ds=dc.createStatement();dr=ds.executeQuery("SELECT COUNT(*) FROM posts");try{if(dr.next())postCount=dr.getLong(1);}finally{dr.close();ds.close();}
                        }catch(Exception ignored){}finally{dc.close();}
                    }
                    JSONObject o=new JSONObject();
                    o.put("forumid",toInt(id));
                    o.put("styleid",0);
                    String title=PlainSqliteCatalog.str(x,"name","title");
                    o.put("title",title);o.put("title_clean",title);
                    o.put("description",JSONObject.NULL);o.put("description_clean",JSONObject.NULL);
                    o.put("options",0);o.put("showprivate",0);
                    o.put("displayorder",PlainSqliteCatalog.integer(x,"display_order"));
                    // The viewer UI uses threadcount/replycount.
                    if(threadCount==0)threadCount=PlainSqliteCatalog.lng(x,"threads","threadcount");
                    if(postCount==0)postCount=PlainSqliteCatalog.lng(x,"posts","postcount","replycount");
                    o.put("threadcount",threadCount);
                    o.put("replycount",postCount);
                    o.put("lastpost",PlainSqliteCatalog.lng(x,"latest_edit_unix"));
                    o.put("lastposter","");o.put("lastposterid",0);o.put("lastpostid",0);
                    o.put("lastthread",PlainSqliteCatalog.str(x,"latest_edit_title"));
                    o.put("lastthreadid",PlainSqliteCatalog.lng(x,"latest_edit_threadid"));
                    o.put("lasticonid",0);o.put("lastprefixid","");o.put("daysprune",0);
                    o.put("newpostemail",JSONObject.NULL);o.put("newthreademail",JSONObject.NULL);
                    o.put("parentid",toInt(parent));
                    o.put("parentlist",id+",-1");
                    o.put("password","");o.put("link",PlainSqliteCatalog.str(x,"url"));
                    o.put("childlist",childList(id,children));
                    o.put("defaultsortfield","lastpost");o.put("defaultsortorder","desc");
                    o.put("imageprefix",JSONObject.NULL);o.put("visible",0);
                    // Explicitly tell app.js whether this node itself has a DB. Parents remain visible but not clickable.
                    o.put("has_sqlite",available.contains(id)?1:0);
                    forums.put(o);
                }
            }finally{c.close();}
        }
        // Installed databases must remain discoverable even if tree metadata is missing/stale.
        Set<String> represented=new HashSet<String>();
        for(int i=0;i<forums.length();i++)represented.add(String.valueOf(forums.getJSONObject(i).optInt("forumid")));
        for(PlainSqliteCatalog.FormDb f:cat.forms.values()){
            if(f.data==null || vb!= "vb".equals(f.family) || represented.contains(f.forumId))continue;
            JSONObject o=new JSONObject();o.put("forumid",toInt(f.forumId));o.put("parentid",-1);
            o.put("displayorder",0);o.put("title",cat.forumTitle(f));o.put("title_clean",cat.forumTitle(f));
            o.put("has_sqlite",1);o.put("threadcount",0);o.put("replycount",0);o.put("childlist",f.forumId);
            forums.put(o);represented.add(f.forumId);
        }
        return cat.templates.js(vb?"index_vb":"index_mn","json",forums.toString()).getBytes(StandardCharsets.UTF_8);
    }

    private static int toInt(String s){try{return Integer.parseInt(s);}catch(Exception e){return -1;}}
    private static String parentList(String id,Map<String,Map<String,Object>> byId){
        List<String> out=new ArrayList<String>();String cur=id;Set<String> guard=new HashSet<String>();
        while(cur!=null&&cur.length()>0&&!guard.contains(cur)){guard.add(cur);out.add(cur);Map<String,Object>x=byId.get(cur);if(x==null)break;cur=PlainSqliteCatalog.str(x,"parent_forumid","parentid");}
        out.add("-1");return join(out,",");
    }
    private static String childList(String id,Map<String,List<String>> children){
        List<String> out=new ArrayList<String>();collectChildren(id,children,out,new HashSet<String>());out.add("-1");return join(out,",");
    }
    private static void collectChildren(String id,Map<String,List<String>> children,List<String> out,Set<String> seen){
        if(seen.contains(id))return;seen.add(id);out.add(id);List<String> ch=children.get(id);if(ch!=null)for(String x:ch)collectChildren(x,children,out,seen);
    }
    private static String join(List<String>a,String sep){StringBuilder b=new StringBuilder();for(int i=0;i<a.size();i++){if(i>0)b.append(sep);b.append(a.get(i));}return b.toString();}

    private static Map<String,long[]> loadCounters(PlainSqliteCatalog cat,boolean vb)throws Exception{
        Map<String,long[]> out=new HashMap<String,long[]>();List<File> files=new ArrayList<File>();if(vb){if(cat.countVb!=null)files.add(cat.countVb);}else{if(cat.countMn!=null)files.add(cat.countMn);if(cat.countSn!=null)files.add(cat.countSn);}
        for(File f:files){Connection c=cat.open(f);try{Statement s=c.createStatement();ResultSet r=s.executeQuery(cat.templates.sql("forum_counters"));try{while(r.next()){String id=r.getString(1).replaceFirst("(?i)^(mn|sn|vb)","");try{id=String.valueOf(Integer.parseInt(id));}catch(Exception ignore){}long[] a=out.get(id);if(a==null){a=new long[3];out.put(id,a);}a[0]=cat.templates.math("counter_sum","left",a[0],"right",r.getLong(2));a[1]=cat.templates.math("counter_sum","left",a[1],"right",r.getLong(3));a[2]=cat.templates.math("counter_sum","left",a[2],"right",r.getLong(4));}}finally{r.close();s.close();}}finally{c.close();}}
        return out;
    }

    private static byte[] forum(PlainSqliteCatalog cat,String formKey,int page,String order,boolean sticky)throws Exception{
        page=(int)cat.templates.math("normalize_page","page",page);
        PlainSqliteCatalog.FormDb f=cat.form(formKey);if(f==null||f.data==null)return null;
        Connection c=cat.open(f.data);try{
            boolean asc=cat.templates.config("order.asc_token").equalsIgnoreCase(order);
            boolean vb5=isVb5(f);
            List<Map<String,Object>> rows=vb5&&!sticky?vb5YearRows(c,cat.templates):threadRows(c,sticky,asc,page,cat.templates);
            List<Long> tids=new ArrayList<Long>();for(Map<String,Object>x:rows)tids.add(PlainSqliteCatalog.lng(x,"threadid"));
            Map<Long,Map<String,Object>> exThreads=cat.extraThreads(f,tids);
            Map<Long,Map<String,Object>> users=cat.users(f);
            Set<Long> imam=cat.imamPostIds(f);
            Map<Long,List<Long>> allPostIds=postIdsForThreads(c,tids,cat.templates);
            JSONArray arr=new JSONArray();
            for(Map<String,Object>x:rows){
                long tid=PlainSqliteCatalog.lng(x,"threadid");Map<String,Object> ex=exThreads.get(tid);
                long uid=ex==null?PlainSqliteCatalog.lng(x,"userid","user_id"):PlainSqliteCatalog.lng(ex,"userid","user_id");
                Map<String,Object> u=users.get(uid);
                JSONObject o=new JSONObject();copyThread(o,x,f,uid,u,cat.templates);
                if(vb5){o.put("username","System");o.put("postusername","System");}
                List<Long> threadPostIds=allPostIds.get(tid);if(threadPostIds==null)threadPostIds=Collections.emptyList();
                o.put("featuredposts",featured(c,threadPostIds,imam,f.extra!=null,cat.templates));
                o.put("postcounts",postCountObject(threadPostIds,cat.templates));arr.put(o);
            }
            JSONObject root=new JSONObject();root.put("forumid",Integer.parseInt(f.forumId));root.put("forumtype","vb".equals(f.family)?"vb":"mn");root.put("forumtitle",cat.forumTitle(f));root.put("page",vb5?1:page);int total=countNormalThreads(c,cat.templates);int forumPageSize=cat.templates.integer("forum.page_size");root.put("pages",vb5?1:(int)cat.templates.math("last_page","total",total,"page_size",forumPageSize));root.put("order",order+"_lastpost");if(sticky){root.put("count",arr.length());root.put("sticky",arr);return cat.templates.js("forum_sticky","json",root.toString()).getBytes(StandardCharsets.UTF_8);}
            if(vb5){
                root.put("layout","vb5_year_groups");
                root.put("pagination",false);
                root.put("groups",vb5Groups(arr));
            }
            root.put("threads",arr);return cat.templates.js("forum_threads","json",root.toString()).getBytes(StandardCharsets.UTF_8);
        }finally{c.close();}
    }

    private static void copyThread(JSONObject o,Map<String,Object>x,PlainSqliteCatalog.FormDb f,long uid,Map<String,Object>u,SqlTemplateConfig templates)throws Exception{
        long rowTime=rowTime(x);
        o.put("threadid",PlainSqliteCatalog.lng(x,"threadid"));o.put("title",PlainSqliteCatalog.displayText(x,"title","subject","threadtitle"));o.put("firstpostid",PlainSqliteCatalog.lng(x,"firstpostid"));o.put("lastpostid",PlainSqliteCatalog.lng(x,"lastpostid"));o.put("lastpost",PlainSqliteCatalog.lng(x,"lastpost")!=0?PlainSqliteCatalog.lng(x,"lastpost"):rowTime);o.put("forumid",PlainSqliteCatalog.lng(x,"forumid")!=0?PlainSqliteCatalog.lng(x,"forumid"):Integer.parseInt(f.forumId));o.put("replycount",PlainSqliteCatalog.lng(x,"replycount","replies","posts"));o.put("dateline",PlainSqliteCatalog.lng(x,"dateline","published"));o.put("sticky",isSticky(x,templates)?1:0);o.put("datetime",PlainSqliteCatalog.lng(x,"datetime","lastupdate","edited","published"));o.put("userid",uid);o.put("username",u==null?PlainSqliteCatalog.str(x,"username","user_name","name"):PlainSqliteCatalog.str(u,"username","user_name","name"));o.put("nickname",u==null?PlainSqliteCatalog.displayText(x,"nickname","nick_name","usertitle"):PlainSqliteCatalog.displayText(u,"nickname","nick_name","usertitle"));
    }

    private static byte[] thread(PlainSqliteCatalog cat,String formKey,long tid,int page)throws Exception{
        page=(int)cat.templates.math("normalize_page","page",page);
        PlainSqliteCatalog.FormDb f=cat.form(formKey);if(f==null||f.data==null)return null;Connection c=cat.open(f.data);try{
            PreparedStatement q=c.prepareStatement(cat.templates.sql("thread_one"));q.setLong(1,tid);ResultSet r=q.executeQuery();if(!r.next()){r.close();q.close();return null;}Map<String,Object> tr=PlainSqliteCatalog.row(r);r.close();q.close();List<Long> all=postIds(c,tid,cat.templates);boolean vb5=isVb5(f);if(vb5&&"no_year".equals(vb5YearGroup(tr))){long summary=PlainSqliteCatalog.lng(tr,"firstpostid");if(summary>0)all=new ArrayList<Long>(Collections.singletonList(summary));}int threadPageSize=cat.templates.integer("thread.page_size");int pages=vb5?1:(int)cat.templates.math("last_page","total",all.size(),"page_size",threadPageSize),from=vb5?0:(int)cat.templates.math("slice_from","total",all.size(),"page",page,"page_size",threadPageSize),to=vb5?all.size():(int)cat.templates.math("slice_to","total",all.size(),"from",from,"page_size",threadPageSize);
            int upDesc=vb5?1:forumPageForThread(c,tid,false,cat.templates),upAsc=vb5?1:forumPageForThread(c,tid,true,cat.templates);
            JSONObject root=new JSONObject();root.put("thread_data",new JSONArray());root.put("thread_id",tid);root.put("thread_page",vb5?1:page);root.put("thread_pages",pages);if(vb5){root.put("pagination",false);root.put("layout","vb5_year_content");root.put("year_group",vb5YearGroup(tr));root.put("year_label",vb5YearLabel(tr));}root.put("forum_type","vb".equals(f.family)?"vb":"mn");root.put("forum_id",Integer.parseInt(f.forumId));root.put("thread_up_page_desc",upDesc);root.put("thread_up_page_asc",upAsc);String publicFamily="vb".equals(f.family)?"vb":"mn";root.put("thread_up_link_desc",cat.templates.output("thread_up","family",publicFamily,"forumid",f.forumId,"page",upDesc,"threadid",tid));root.put("thread_up_link_asc",cat.templates.output("thread_up","family",publicFamily,"forumid",f.forumId,"page",upAsc,"threadid",tid));root.put("forum_title",cat.forumTitle(f));root.put("thread_title",PlainSqliteCatalog.str(tr,"title","subject","threadtitle"));root.put("postcounts",postCountObject(all,cat.templates));root.put("featuredposts",featured(c,all,cat.imamPostIds(f),f.extra!=null,cat.templates));
            StringBuilder loads=new StringBuilder();String sep=cat.templates.js("loader_separator");for(int i=from;i<to;i++){if(loads.length()>0)loads.append(sep);loads.append(cat.templates.js("thread_post_loader","postid",all.get(i)));}if(loads.length()>0)loads.append(sep);loads.append(cat.templates.js("thread_asset_loader"));
            String js=cat.templates.js("thread","root",root.toString(),"loads",loads.toString());return js.getBytes(StandardCharsets.UTF_8);
        }finally{c.close();}
    }

    private static byte[] post(PlainSqliteCatalog cat,String formKey,long pid)throws Exception{
        PlainSqliteCatalog.FormDb f=cat.form(formKey);if(f==null||f.data==null)return null;Connection c=cat.open(f.data);try{
            PreparedStatement q=c.prepareStatement(cat.templates.sql("post_one"));q.setLong(1,pid);ResultSet r=q.executeQuery();if(!r.next()){r.close();q.close();return null;}Map<String,Object>x=PlainSqliteCatalog.row(r);r.close();q.close();long tid=PlainSqliteCatalog.lng(x,"threadid");List<Long> ids=postIds(c,tid,cat.templates);int idx=ids.indexOf(pid),pc=idx<0?1:(int)cat.templates.math("position_from_index","index",idx);
            Map<String,Object> ex=cat.extraPost(f,pid);long uid=ex==null?PlainSqliteCatalog.lng(x,"userid","user_id"):PlainSqliteCatalog.lng(ex,"userid","user_id");Map<Long,Map<String,Object>> users=cat.users(f);Map<String,Object> u=users.get(uid);
            String threadTitle="";q=c.prepareStatement(cat.templates.sql("thread_title"));q.setLong(1,tid);r=q.executeQuery();if(r.next())threadTitle=r.getString(1);r.close();q.close();
            JSONObject o=new JSONObject();o.put("postid",pid);o.put("threadid",tid);o.put("postcount",pc);o.put("title",PlainSqliteCatalog.displayText(x,"title","posttitle","subject"));o.put("higri",ex==null?"":PlainSqliteCatalog.str(ex,"hijri_date"));o.put("meladi",ex==null?"":PlainSqliteCatalog.str(ex,"miladi_date"));o.put("time",PlainSqliteCatalog.str(ex==null?x:ex,"post_time"));o.put("time_zone",PlainSqliteCatalog.str(ex==null?x:ex,"time_zone"));o.put("hijri_date",PlainSqliteCatalog.str(ex==null?x:ex,"hijri_date"));o.put("miladi_date",PlainSqliteCatalog.str(ex==null?x:ex,"miladi_date"));o.put("userid",uid);o.put("username",u==null?PlainSqliteCatalog.str(x,"username","user_name","name"):PlainSqliteCatalog.str(u,"username","user_name","name"));o.put("nickname",u==null?PlainSqliteCatalog.displayText(x,"nickname","nick_name","usertitle"):PlainSqliteCatalog.displayText(u,"nickname","nick_name","usertitle"));o.put("usertitle",u==null?PlainSqliteCatalog.displayText(x,"usertitle","nickname","nick_name"):PlainSqliteCatalog.displayText(u,"nickname","nick_name","usertitle"));o.put("dateline",PlainSqliteCatalog.lng(x,"dateline","published"));o.put("datetime",PlainSqliteCatalog.lng(x,"datetime","lastupdate","edited","published"));o.put("lastedit",PlainSqliteCatalog.lng(x,"lastedit","edited"));o.put("langs",PlainSqliteCatalog.str(x,"langs"));o.put("video",PlainSqliteCatalog.str(x,"youtube","video"));o.put("audio",PlainSqliteCatalog.str(x,"sound","audio"));o.put("source",PlainSqliteCatalog.str(x,"source","url"));String rawText=PlainSqliteCatalog.str(x,"pagetext","content","message","body");if(isVb5(f))rawText=yearArchiveLinks(rawText);String renderedText=cat.templates.config("content.bbcode_to_html").equalsIgnoreCase("true")?VBulletinBbCode.toHtml(rawText):rawText;if("vb".equalsIgnoreCase(f.family)&&"2".equals(f.forumId))renderedText=thematicArchiveLinks(renderedText);o.put("long_text",renderedText);int postPageSize=cat.templates.integer("thread.page_size");o.put("post_page",isVb5(f)?1:cat.templates.math("page_from_position","position",pc,"page_size",postPageSize));o.put("posts_per_page",postPageSize);o.put("up_link",cat.templates.output("post_up","threadid",tid,"postid",pid));o.put("thread_title",PlainSqliteCatalog.cleanDisplayText(threadTitle));o.put("forum_title",cat.forumTitle(f));o.put("forum_id",Integer.parseInt(f.forumId));o.put("forum_type","vb".equals(f.family)?"vb":"mn");o.put("postcounts",postCountObject(ids,cat.templates));o.put("featuredposts",featured(c,ids,cat.imamPostIds(f),f.extra!=null,cat.templates));return cat.templates.js("post","json",o.toString()).getBytes(StandardCharsets.UTF_8);
        }finally{c.close();}
    }


    private static String thematicArchiveLinks(String text){
        org.jsoup.nodes.Document doc=org.jsoup.Jsoup.parseBodyFragment(text);
        for(org.jsoup.nodes.Element a:doc.select("a[href]")){
            String href=a.attr("href"),lower=href.toLowerCase(Locale.ROOT);
            if(lower.matches("^https?://.*")&&!lower.matches("^https?://(?:www\\.)?(?:mahdialumma\\.com|nasser-alyamani\\.org|localhost(?::\\d+)?)/.*"))continue;
            Matcher forum=Pattern.compile("forum-?(\\d+)/",Pattern.CASE_INSENSITIVE).matcher(href);
            String fid=forum.find()?forum.group(1):"33",post="",thread="";
            Matcher m=Pattern.compile("(?:[#&?]post|[?&]p=)(\\d+)",Pattern.CASE_INSENSITIVE).matcher(href);
            if(m.find())post=m.group(1);
            if(post.length()==0){m=Pattern.compile("(?:^|/)po-(\\d+)\\.html",Pattern.CASE_INSENSITIVE).matcher(href);if(m.find())post=m.group(1);}
            m=Pattern.compile("(?:[?&]t=|showthread\\.php\\?(?=\\d)|(?:^|/)t-?)(\\d+)",Pattern.CASE_INSENSITIVE).matcher(href);if(m.find())thread=m.group(1);
            if(post.length()==0&&thread.length()==0)continue;
            a.attr("href","viewer.html?fo="+fid+"&ftype=mn&"+(post.length()>0?"po="+post:"th="+thread));a.removeAttr("target");
        }
        doc.outputSettings().prettyPrint(false);return doc.body().html();
    }

    private static String yearArchiveLinks(String text){
        org.jsoup.nodes.Document doc=org.jsoup.Jsoup.parseBodyFragment(text);
        for(org.jsoup.nodes.Element a:doc.select("a.myLink")){
            Matcher m=Pattern.compile("forum-(\\d+)/t-(\\d+)\\.html#post(\\d+)").matcher(a.id());
            if(m.find())a.attr("href","viewer.html?fo="+m.group(1)+"&ftype=mn&po="+m.group(3));
        }
        doc.outputSettings().prettyPrint(false);return doc.body().html();
    }

    private static boolean isVb5(PlainSqliteCatalog.FormDb f){
        return f!=null && "vb".equalsIgnoreCase(f.family) && "5".equals(f.forumId);
    }

    /** VB5 is a year index, not a normal paginated thread forum. */
    private static List<Map<String,Object>> vb5YearRows(Connection c,SqlTemplateConfig templates)throws Exception{
        Statement s=c.createStatement();ResultSet r=s.executeQuery(templates.sql("vb5_threads"));
        List<Map<String,Object>> miladi=new ArrayList<Map<String,Object>>(), hijri=new ArrayList<Map<String,Object>>(), noYear=new ArrayList<Map<String,Object>>();
        try{while(r.next()){Map<String,Object>x=PlainSqliteCatalog.row(r);String g=vb5YearGroup(x);if("miladi".equals(g))miladi.add(x);else if("hijri".equals(g))hijri.add(x);else noYear.add(x);}}finally{r.close();s.close();}
        Comparator<Map<String,Object>> desc=new Comparator<Map<String,Object>>(){public int compare(Map<String,Object>a,Map<String,Object>b){long x=PlainSqliteCatalog.lng(a,"threadid"),y=PlainSqliteCatalog.lng(b,"threadid");return x==y?0:(x<y?1:-1);}};
        Collections.sort(miladi,desc);Collections.sort(hijri,desc);Collections.sort(noYear,desc);
        List<Map<String,Object>> out=new ArrayList<Map<String,Object>>(miladi.size()+hijri.size()+noYear.size());out.addAll(hijri);out.addAll(miladi);out.addAll(noYear);return out;
    }

    private static String vb5YearGroup(Map<String,Object>x){
        String kind=PlainSqliteCatalog.str(x,"type").toLowerCase(Locale.ROOT);if("hijri".equals(kind))return "hijri";if("miladi".equals(kind))return "miladi";String u=PlainSqliteCatalog.str(x,"url").toLowerCase(Locale.ROOT),t=PlainSqliteCatalog.str(x,"title").toLowerCase(Locale.ROOT);
        if(u.indexOf("myear-")>=0 || t.startsWith("year 20"))return "miladi";
        if(u.indexOf("hyear-")>=0 || t.startsWith("hijri year "))return "hijri";
        return "no_year";
    }

    private static String vb5YearLabel(Map<String,Object>x){
        String g=vb5YearGroup(x);if("no_year".equals(g))return "NO_YEAR";
        long id=PlainSqliteCatalog.lng(x,"year","threadid");return id>0?String.valueOf(id):PlainSqliteCatalog.str(x,"title");
    }

    private static JSONObject vb5Groups(JSONArray all)throws Exception{
        JSONArray miladi=new JSONArray(),hijri=new JSONArray(),noYear=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject o=all.getJSONObject(i);String title=o.optString("title","").toLowerCase(Locale.ROOT);long id=o.optLong("threadid",0);JSONObject y=new JSONObject();y.put("threadid",id);y.put("title",o.optString("title",""));y.put("year",title.indexOf("no_year")>=0?"NO_YEAR":String.valueOf(id));y.put("url","thread_"+id+".html");if(title.startsWith("hijri year "))hijri.put(y);else if(title.indexOf("no_year")>=0)noYear.put(y);else miladi.put(y);}
        JSONObject g=new JSONObject();g.put("miladi",miladi);g.put("hijri",hijri);g.put("no_year",noYear);return g;
    }

    private static JSONArray imamPostIds(Map<String,Object> exThread,Map<Long,Map<String,Object>> exPosts){JSONArray a=new JSONArray();if(exThread==null)return a;String raw=PlainSqliteCatalog.str(exThread,"postids");Matcher m=Pattern.compile("\\d+").matcher(raw);while(m.find()){long id=Long.parseLong(m.group());Map<String,Object>x=exPosts.get(id);if(x!=null&&PlainSqliteCatalog.lng(x,"userid")==4){JSONObject o=new JSONObject();o.put("postid",String.valueOf(id));a.put(o);}}return a;}
    private static JSONObject postCountObject(List<Long> ids,SqlTemplateConfig templates){JSONObject o=new JSONObject();for(int i=0;i<ids.size();i++)o.put(String.valueOf(ids.get(i)),templates.math("position_from_index","index",i));return o;}
    /** Featured/special-post bar: ONLY posts authored by userid=4. */
    private static JSONArray featured(Connection c,List<Long> ids,Set<Long> imamIds,boolean extraAuthoritative,SqlTemplateConfig templates)throws Exception{
        JSONArray a=new JSONArray();
        String uidCol=null;PreparedStatement uidQuery=null;
        if(!extraAuthoritative){
            Set<String> postCols=PlainSqliteCatalog.columns(c,"posts",templates);
            for(String k:templates.list("columns.post_userid")){if(postCols.contains(k)){uidCol=k;break;}}
            if(uidCol!=null)uidQuery=c.prepareStatement(templates.sql("posts_uid_one","uid_column",uidCol));
        }
        try{
            for(int i=0;i<ids.size();i++){
                long pid=ids.get(i);boolean isImam=imamIds!=null&&imamIds.contains(pid);
                if(!isImam&&!extraAuthoritative&&uidQuery!=null){uidQuery.setLong(1,pid);ResultSet r=uidQuery.executeQuery();try{isImam=r.next()&&r.getLong(1)==templates.integer("imam.userid");}finally{r.close();}}
                if(!isImam)continue;JSONObject x=new JSONObject();x.put("postid",String.valueOf(pid));x.put("page",templates.math("zero_item_page","index",i,"page_size",templates.integer("thread.page_size")));x.put("postcount",templates.math("position_from_index","index",i));a.put(x);
            }
        }finally{if(uidQuery!=null)uidQuery.close();}
        return a;
    }

    private static Map<Long,List<Long>> postIdsForThreads(Connection c,List<Long> tids,SqlTemplateConfig templates)throws Exception{
        Map<Long,List<Long>> out=new LinkedHashMap<Long,List<Long>>();if(tids==null||tids.isEmpty())return out;
        for(Long tid:tids)out.put(tid,new ArrayList<Long>());
        Set<String> cols=PlainSqliteCatalog.columns(c,"posts",templates);String ord=cols.contains(templates.config("columns.post_order"))?templates.expr("post_order_with_time"):templates.expr("post_order_fallback");
        int batchSize=templates.integer("sqlite.batch_size");
        for(int from=0;from<tids.size();from=(int)templates.math("batch_next","total",tids.size(),"from",from,"batch_size",batchSize)){
            int to=(int)templates.math("batch_end","total",tids.size(),"from",from,"batch_size",batchSize);StringBuilder ph=new StringBuilder();
            for(int i=from;i<to;i++){if(i>from)ph.append(',');ph.append('?');}
            String sql=templates.sql("posts_for_threads","placeholders",ph.toString(),"order",ord);
            PreparedStatement q=c.prepareStatement(sql);for(int i=from;i<to;i++)q.setLong((int)templates.math("bind_index","index",i,"from",from),tids.get(i));ResultSet r=q.executeQuery();
            try{while(r.next()){long tid=r.getLong(1);List<Long>a=out.get(tid);if(a==null){a=new ArrayList<Long>();out.put(tid,a);}a.add(r.getLong(2));}}finally{r.close();q.close();}
        }return out;
    }
    private static List<Long> postIds(Connection c,long tid,SqlTemplateConfig templates)throws Exception{
        Set<String> cols=PlainSqliteCatalog.columns(c,"posts",templates);
        String order=cols.contains(templates.config("columns.post_order"))?templates.expr("post_order_with_time"):templates.expr("post_order_fallback");
        PreparedStatement q=c.prepareStatement(templates.sql("post_ids_by_thread","order_column",order));q.setLong(1,tid);ResultSet r=q.executeQuery();List<Long>a=new ArrayList<Long>();try{while(r.next())a.add(r.getLong(1));}finally{r.close();q.close();}return a;
    }
    private static String stickyExpr(Connection c,SqlTemplateConfig templates)throws Exception{
        Set<String> cols=PlainSqliteCatalog.columns(c,"threads",templates);
        for(String k:templates.list("columns.sticky")){
            if(cols.contains(k))return templates.expr("sticky_field","column",k);
        }
        return templates.expr("sticky_fallback");
    }
    private static String threadTimeExpr(Connection c,SqlTemplateConfig templates)throws Exception{
        Set<String> cols=PlainSqliteCatalog.columns(c,"threads",templates);
        List<String> parts=new ArrayList<String>();
        for(String k:templates.list("columns.thread_time"))if(cols.contains(k))parts.add(templates.expr("thread_time_part","column",k));
        if(parts.isEmpty())return templates.expr("thread_time_fallback");
        return templates.expr("thread_time_wrap","parts",join(parts,","));
    }
    private static int countNormalThreads(Connection c,SqlTemplateConfig templates)throws Exception{
        String sticky=stickyExpr(c,templates);
        Statement s=c.createStatement();ResultSet r=s.executeQuery(templates.sql("normal_thread_count","sticky_expr",sticky));
        try{return r.next()?r.getInt(1):0;}finally{r.close();s.close();}
    }
    private static List<Map<String,Object>> threadRows(Connection c,boolean wantSticky,final boolean asc,int page,SqlTemplateConfig templates)throws Exception{
        String sticky=stickyExpr(c,templates),time=threadTimeExpr(c,templates),dir=asc?templates.config("order.asc_sql"):templates.config("order.desc_sql");
        int pageSize=templates.integer("forum.page_size"); int limit=wantSticky?templates.integer("sticky.limit"):pageSize,offset=wantSticky?0:(int)templates.math("offset_from_page","page",page,"page_size",pageSize);
        String sql=templates.sql("forum_threads_page","sticky_expr",sticky,"sticky_value",wantSticky?1:0,"time_expr",time,"direction",dir);
        PreparedStatement q=c.prepareStatement(sql);q.setInt(1,limit);q.setInt(2,offset);ResultSet r=q.executeQuery();
        List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();try{while(r.next())out.add(PlainSqliteCatalog.row(r));}finally{r.close();q.close();}return out;
    }
    private static int forumPageForThread(Connection c,long tid,final boolean asc,SqlTemplateConfig templates)throws Exception{
        String sticky=stickyExpr(c,templates),time=threadTimeExpr(c,templates);
        PreparedStatement q=c.prepareStatement(templates.sql("thread_position","sticky_expr",sticky,"time_expr",time));q.setLong(1,tid);ResultSet r=q.executeQuery();
        if(!r.next()){r.close();q.close();return 1;}int st=r.getInt(1);long tm=r.getLong(2);r.close();q.close();if(st!=0)return 1;
        String cmp=asc?templates.expr("compare_asc","time_expr",time):templates.expr("compare_desc","time_expr",time);
        String sql=templates.sql("thread_page_count_before","sticky_expr",sticky,"time_expr",time,"compare",cmp);
        q=c.prepareStatement(sql);q.setLong(1,tm);q.setLong(2,tm);q.setLong(3,tid);r=q.executeQuery();int before=r.next()?r.getInt(1):0;r.close();q.close();return (int)templates.math("target_page","before",before,"page_size",templates.integer("forum.page_size"));
    }
    private static long rowTime(Map<String,Object>x){return PlainSqliteCatalog.lng(x,"lastpost","lastupdate","edited","published","lastedit","dateline","updated","timestamp","datetime");}
    private static boolean isSticky(Map<String,Object>x,SqlTemplateConfig templates){String v=PlainSqliteCatalog.str(x,templates.list("columns.sticky")).trim().toLowerCase(Locale.ROOT);for(String yes:templates.list("sticky.true_values"))if(yes.equalsIgnoreCase(v))return true;return false;}
    private static int count(Connection c,String sql)throws Exception{Statement s=c.createStatement();ResultSet r=s.executeQuery(sql);try{return r.next()?r.getInt(1):0;}finally{r.close();s.close();}}
    private static void write(File dir,String name,byte[] data)throws Exception{if(data==null)return;if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create "+dir);Files.write(new File(dir,name).toPath(),data);}
}
