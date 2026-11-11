import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Background Lucene 5.3 build/delete controller used by the searcher's indexing bottom sheet. */
public final class LuceneIndexManager implements AutoCloseable {
    private boolean closing;
    private volatile boolean checkingDatabases;
    private volatile boolean databasesChecked;
    private volatile boolean noIndexesToSync;
    private final File ntRoot;
    private final File luceneRoot;
    private final Object lock = new Object();
    private volatile boolean busy;
    private volatile String action = "idle";
    private volatile int percent;
    private volatile String message = "Ready";
    private volatile String error = "";
    private volatile String activeForms = "";
    private volatile boolean cancelRequested;
    private volatile boolean stopped;
    private volatile Thread buildThread;

    public LuceneIndexManager(File ntRoot, File luceneRoot) throws Exception {
        this.ntRoot = ntRoot.getCanonicalFile();
        this.luceneRoot = luceneRoot.getCanonicalFile();
    }

    public File root() { return luceneRoot; }

    public String startBuild(String csv) {
        final List<String> forms;
        try {
            forms = resolveForms(includePartialRanges(csv));
            for (String form : forms) {
                String section = sectionForForm(form);
                if (section.matches("vb\\d+") && !"vb4".equals(section))
                    throw new IllegalArgumentException("Only vb4 is allowed for Lucene indexing");
            }
        }
        catch (Exception e) { return result(false, e.getMessage()); }
        synchronized (lock) {
            if (closing) return result(false,"Server is stopping");
            if (busy) return result(false, "Lucene operation already running");
            busy = true; action = "build"; percent = 0; error = ""; message = "Starting Lucene build"; activeForms=joinForms(forms); cancelRequested=false; stopped=false;
        Thread t = new Thread(new Runnable(){ public void run(){ runBuild(forms,true); } }, "noon-lucene-build");
        t.setDaemon(true); buildThread=t; t.start();
        return result(true, "Build started");
        }
    }

    public String startDelete(String csv) {
        final List<String> forms;
        try { forms = resolveForms(csv); }
        catch (Exception e) { return result(false, e.getMessage()); }
        synchronized (lock) {
            if (closing) return result(false,"Server is stopping");
            if (busy) return result(false, "Lucene operation already running");
            busy = true; action = "delete"; percent = 0; error = ""; message = "Deleting Lucene indexes"; activeForms=joinForms(forms); cancelRequested=false; stopped=false;
        Thread t = new Thread(new Runnable(){ public void run(){ runDelete(forms); } }, "noon-lucene-delete");
        t.setDaemon(true); buildThread=t; t.start();
        return result(true, "Delete started");
        }
    }

    /** Match Android: refresh only forums the user had already indexed. */
    public void synchronizeExistingOnLaunch(){
        synchronized(lock){
            if(closing||busy)return;
            busy=true;action="build";percent=0;error="";message="Checking application databases";cancelRequested=false;stopped=false;checkingDatabases=true;databasesChecked=false;noIndexesToSync=false;
            buildThread=new Thread(new Runnable(){public void run(){
                try{
                    // Authenticity is enforced by SignedBinIntegrity, without rewriting
                    // the signed SQLite bytes or running a startup SQLite health scan.
                    SignedBinIntegrity.checkRuntime();
                    databasesChecked=true;checkingDatabases=false;message="Checking installed Lucene indexes";
                    checkCancelled();
                    java.util.List<String> forms=new java.util.ArrayList<String>();
                    boolean anyIndexes=false;
                    PlainSqliteCatalog catalog=new PlainSqliteCatalog(ntRoot);
                    for(PlainSqliteCatalog.FormDb form:catalog.forms.values()){
                        String code=form.family+form.forumId;
                        if("vb".equals(form.family)&&!"vb4".equals(code))continue;
                        if(indexState(code).optInt("indexed_count")>0)anyIndexes=true;
                        // Partial bundles stay partial until the user completes them.
                        if(isLocallyIndexed(code))forms.add(code);
                    }
                    activeForms=joinForms(forms);
                    if(forms.isEmpty()){noIndexesToSync=!anyIndexes;message="Ready";busy=false;action="idle";buildThread=null;return;}
                    runBuild(forms,false);
                }catch(Throwable e){checkingDatabases=false;stopped=cancelRequested;error=stopped?"":msg(e);message=stopped?"Lucene build stopped":"Lucene synchronization failed";buildThread=null;busy=false;action="idle";cancelRequested=false;}
            }},"noon-lucene-install-sync");
            buildThread.setDaemon(true);buildThread.start();
        }
    }

    public boolean checkingDatabases(){return checkingDatabases;}
    public boolean databasesChecked(){return databasesChecked;}
    public boolean noIndexesToSync(){return noIndexesToSync;}
    public String stopBuild() {
        synchronized(lock){
            if(!busy || !"build".equals(action)) return result(false,"No Lucene build is running");
            cancelRequested=true;
            message="Stopping Lucene build";
            Thread t=buildThread;
            if(t!=null) t.interrupt();
            return result(true,"Stop requested");
        }
    }

    private void checkCancelled() throws IOException {
        if(cancelRequested) throw new IOException("__LUCENE_STOPPED__");
    }

    private void runBuild(final List<String> forms,final boolean force) {
        try {
            if (!luceneRoot.exists() && !luceneRoot.mkdirs()) throw new IOException("Cannot create Lucene folder: " + luceneRoot);
            final int count = forms.size();
            for (int i=0;i<count;i++) {
                checkCancelled();
                final int formIndex=i;
                final String form=forms.get(i);
                final PlainSqliteCatalog.FormDb db=findForumDb(form);
                if(db==null || db.data==null || !db.data.isFile()) throw new IOException("Form SQLite not found under bin/NT: " + form);
                // Remove old incorrect mn<ID> aliases created by earlier SN builds.
                cleanupLegacySnIndexes(form);
                message="Building " + form;
                SqliteLuceneIndexer.run(db, luceneRoot, new PlainSqliteCatalog(ntRoot).forumTitle(db), new SqliteLuceneIndexer.Progress(){
                    public void onProgress(int processed,int total,int indexed,String detail){
                        if(cancelRequested) throw new RuntimeException("__LUCENE_STOPPED__");
                        double within=total<=0?0.0:((double)processed/(double)total);
                        int p=(int)Math.floor(100.0*((formIndex+within)/(double)count));
                        if(p<0)p=0;if(p>99)p=99;
                        percent=p;
                        message="Building " + form + " — " + detail;
                    }
                },force);
                checkCancelled();
                percent=(int)Math.floor(100.0*(i+1)/(double)count);
            }
            percent=100; message="Lucene indexes built"; error=""; stopped=false;
        } catch(Throwable e) {
            String em=msg(e);
            if(cancelRequested || em.indexOf("__LUCENE_STOPPED__")>=0){
                // Writers roll back uncommitted changes; retain the previous indexes.
                stopped=true; error=""; percent=0; message="Lucene build stopped";
            } else {
                error=em; message="Lucene build failed"; stopped=false;
            }
        } finally { buildThread=null; cancelRequested=false; busy=false; action="idle"; }
    }

    private void runDelete(List<String> forms) {
        try {
            int total=forms.size()*3, done=0;
            for(String form:forms){
                String section=sectionForForm(form);
                String[] suffix={"_normal_index","_ngram_index","_arstem_index"};
                for(int i=0;i<suffix.length;i++){
                    message="Deleting " + section + suffix[i];
                    File directory=new File(luceneRoot,section+suffix[i]);
                    deleteTree(directory);
                    LuceneIndexSource.restoreBundled(directory);
                    done++; percent=total==0?100:(int)Math.floor(100.0*done/(double)total);
                }
                cleanupLegacySnIndexes(form);
            }
            percent=100; message="Lucene indexes deleted"; error="";
        } catch(Throwable e) {
            error=msg(e); message="Lucene delete failed";
        } finally { buildThread=null; busy=false; action="idle"; }
    }

    /** A manual build always completes installed, allowed partially indexed ranges. */
    private String includePartialRanges(String csv)throws Exception{
        StringBuilder chosen=new StringBuilder(csv==null?"":csv);
        Set<String> selected=new LinkedHashSet<String>();
        if(csv!=null)for(String item:csv.split(","))selected.add(sectionForForm(item));
        org.json.JSONObject config=UpdateConfig.loadObject(ntRoot),defaults=config.optJSONObject("forum_defaults"),overrides=config.optJSONObject("forum_overrides");
        boolean visibleByDefault=defaults==null||defaults.optBoolean("show_in_index",true);
        PlainSqliteCatalog catalog=new PlainSqliteCatalog(ntRoot);
        for(PlainSqliteCatalog.FormDb form:catalog.forms.values()){
            String code=form.family+form.forumId;
            if("vb".equals(form.family)&&!"4".equals(form.forumId))continue;
            org.json.JSONObject override=overrides==null?null:overrides.optJSONObject(code);
            if(!(override==null?visibleByDefault:override.optBoolean("show_in_index",visibleByDefault)))continue;
            if(!indexState(code).optBoolean("partially_indexed")||!selected.add(code))continue;
            if(chosen.length()>0)chosen.append(',');chosen.append(code);
        }
        return chosen.toString();
    }

    private List<String> resolveForms(String csv) throws Exception {
        Set<String> requested=new LinkedHashSet<String>();
        if(csv!=null)for(String raw:csv.split(",")){String v=safeForm(raw);if(v.length()>0)requested.add(v);}
        if(requested.isEmpty())throw new IllegalArgumentException("No forms selected");
        List<String> out=new ArrayList<String>();
        for(String s:requested){
            PlainSqliteCatalog.FormDb db=findForumDb(s);
            if(db==null || db.data==null || !db.data.isFile())throw new IOException("Form SQLite not found under bin/NT: "+s);
            out.add(s);
        }
        return out;
    }

    /**
     * Indexing discovery intentionally scans the WHOLE bin/NT tree.
     * It does not assume databases live only in NT/sqlites.
     */
    private PlainSqliteCatalog.FormDb findForumDb(String requested) throws Exception {
        String section=sectionForForm(requested);
        PlainSqliteCatalog cat=new PlainSqliteCatalog(ntRoot);
        return cat.form(section);
    }

    private static void scanSqliteFiles(File dir,List<File> out){
        if(dir==null||!dir.isDirectory())return;
        File[] a=dir.listFiles(); if(a==null)return;
        java.util.Arrays.sort(a,new java.util.Comparator<File>(){public int compare(File x,File y){return x.getName().compareToIgnoreCase(y.getName());}});
        for(File f:a){
            if(f.isDirectory()){scanSqliteFiles(f,out);continue;}
            String n=f.getName().toLowerCase(Locale.ROOT);
            if(n.endsWith(".sqlite")||n.endsWith(".sqlite3")||n.endsWith(".db"))out.add(f);
        }
    }

    private static boolean isPostDatabase(File f){
        java.sql.Connection c=null;
        try{
            Class.forName("org.sqlite.JDBC");
            c=SignedBinIntegrity.readOnlySqlite(f);
            java.sql.PreparedStatement p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND lower(name)='posts' LIMIT 1");
            java.sql.ResultSet r=p.executeQuery(); boolean ok=r.next(); r.close(); p.close();
            return ok;
        }catch(Exception e){return false;}finally{try{if(c!=null)c.close();}catch(Exception ignore){}}
    }

    private static File findMatchingExtra(File data,String family,String fid,List<File> all){
        String parent=data.getParentFile()==null?"":data.getParentFile().getAbsolutePath();
        File best=null; int score=Integer.MIN_VALUE;
        for(File f:all){
            String n=f.getName().toLowerCase(Locale.ROOT);
            if(!n.contains("extra"))continue;
            String p=f.getAbsolutePath().replace('\\','/').toLowerCase(Locale.ROOT);
            if(!(p.contains(family+fid)||p.contains("sn"+fid)||p.contains("mn"+fid)))continue;
            int s=0; if(f.getParentFile()!=null&&f.getParentFile().getAbsolutePath().equals(parent))s+=100;
            if(n.contains("_"+family+fid+"_"))s+=30;
            if(s>score){score=s;best=f;}
        }
        return best;
    }

    private static String safeForm(String raw){
        String v=raw==null?"":raw.trim();
        // Forum Manager package ids use locale:form (for example fa:mn54),
        // while the indexing sheet uses locale_form (fa_mn54). Accept both
        // and normalize to the indexer's canonical underscore form.
        if(!v.matches("[A-Za-z0-9:_-]{1,64}"))return "";
        v=v.replace(':','_');
        return v.matches("[A-Za-z0-9_-]{1,64}")?v:"";
    }

    private static String sectionForForm(String form){
        String f=form==null?"":form.toLowerCase(Locale.ROOT).replace(':','_');
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|_)(vb|mn|sn)(\\d+)$").matcher(f);
        if(m.find()){
            return m.group(1)+m.group(2);
        }
        m=java.util.regex.Pattern.compile("(vb|mn|sn)(\\d+)").matcher(f);
        if(m.find())return m.group(1)+m.group(2);
        return f;
    }

    private void cleanupLegacySnIndexes(String form) throws IOException {
        String section=sectionForForm(form);
        if(!section.startsWith("sn"))return;
        String legacy="mn"+section.substring(2);
        String[] suffix={"_normal_index","_ngram_index","_arstem_index"};
        for(int i=0;i<suffix.length;i++)deleteIndex(new File(luceneRoot,legacy+suffix[i]));
    }

    private static void deleteIndex(File directory)throws IOException{
        deleteTree(directory);LuceneIndexSource.hideBundled(directory);
    }
    private boolean isLocallyIndexed(String form){
        String section=sectionForForm(form);
        for(String suffix:new String[]{"_normal_index","_ngram_index","_arstem_index"})
            if(!LuceneIndexSource.local(new File(luceneRoot,section+suffix)))return false;
        return true;
    }

    private static void deleteTree(File f)throws IOException{
        if(!f.exists())return;
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        if(!f.delete())throw new IOException("Cannot delete: "+f.getAbsolutePath());
    }

    /** True only when all three search indexes for this form exist and are valid Lucene indexes. */
    /** Remove all Lucene indexes for one form immediately. Used when an optional forum is removed. */
    public void deleteFormIndexes(String form) throws IOException {
        String section=sectionForForm(form);
        String[] suffix={"_normal_index","_ngram_index","_arstem_index"};

        // Removing a forum must also remove it from search.  If a Lucene build is
        // currently running, stop it first so an IndexWriter cannot recreate the
        // forum directories after we delete them.
        Thread running=null;
        synchronized(lock){
            if(busy && "build".equals(action)){
                cancelRequested=true;
                running=buildThread;
                if(running!=null)running.interrupt();
            } else if(busy) {
                throw new IOException("Lucene operation already running");
            }
        }
        if(running!=null && running!=Thread.currentThread()){
            try{running.join(10000L);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted while stopping Lucene build",e);}
            if(running.isAlive())throw new IOException("Timed out stopping Lucene build");
        }

        synchronized(lock){
            for(int i=0;i<suffix.length;i++)deleteIndex(new File(luceneRoot,section+suffix[i]));
            // Also clean legacy mn<ID> folders left by the old SN naming bug.
            if(section.startsWith("sn")){
                String legacy="mn"+section.substring(2);
                for(int i=0;i<suffix.length;i++)deleteIndex(new File(luceneRoot,legacy+suffix[i]));
            }
        }
    }

    /** Available search types are independent of a range being fully indexed. */
    public org.json.JSONObject indexState(String form){
        String section=sectionForForm(form);
        boolean normal=LuceneIndexSource.exists(new File(luceneRoot,section+"_normal_index"));
        boolean ngram=LuceneIndexSource.exists(new File(luceneRoot,section+"_ngram_index"));
        boolean stem=LuceneIndexSource.exists(new File(luceneRoot,section+"_arstem_index"));
        int count=(normal?1:0)+(ngram?1:0)+(stem?1:0);
        org.json.JSONObject kinds=new org.json.JSONObject().put("full_index",normal).put("wildcard_index",normal).put("partial_index",ngram).put("root_index",stem);
        return new org.json.JSONObject().put("indexed",count==3).put("partially_indexed",count>0&&count<3).put("indexed_count",count).put("index_kinds",kinds);
    }

    public boolean isIndexed(String form){
        String section=sectionForForm(form);
        String[] suffix={"_normal_index","_ngram_index","_arstem_index"};
        for(int i=0;i<suffix.length;i++){
            File d=new File(luceneRoot,section+suffix[i]);
            if(!LuceneIndexSource.exists(d))return false;
        }
        return true;
    }

    /**
     * Search UI discovery endpoint.
     * Available forms come ONLY from NT/sqlites/<form>_sqlite/<form>.sqlite,
     * exactly like the Viewer.  "indexed" is calculated from the real
     * available writable or EXE-embedded indexes (all three types for full status).
     */
    public String formsJson(){
        try{
            PlainSqliteCatalog cat=new PlainSqliteCatalog(ntRoot);
            StringBuilder b=new StringBuilder();
            b.append("{\"ok\":true,\"forms\":[");
            boolean first=true;
            for(PlainSqliteCatalog.FormDb f:cat.forms.values()){
                if(f==null || f.data==null || !f.data.isFile())continue;
                String code=f.key==null?"":f.key.toLowerCase(Locale.ROOT);
                if(code.length()==0)continue;
                if(!first)b.append(',');
                first=false;
                b.append("{\"formid\":\"").append(json(code)).append("\"");
                b.append(",\"family\":\"").append(json(f.family)).append("\"");
                b.append(",\"forumid\":").append(Integer.parseInt(f.forumId));
                b.append(",\"title\":\"").append(json(cat.forumTitle(f))).append("\"");
                b.append(",\"installed\":true");
                org.json.JSONObject state=indexState(code);
                for(String key:new String[]{"indexed","partially_indexed","indexed_count","index_kinds"})
                    b.append(',').append(org.json.JSONObject.quote(key)).append(':').append(state.get(key).toString());
                b.append('}');
            }
            b.append("],\"lucydir\":\"").append(json(luceneRoot.getPath())).append("\"}");
            return b.toString();
        }catch(Exception e){
            return "{\"ok\":false,\"forms\":[],\"error\":\""+json(msg(e))+"\",\"lucydir\":\""+json(luceneRoot.getPath())+"\"}";
        }
    }

    public String statusJson(){return statusJson(true);}
    public String snapshotJson(){return statusJson(false);}
    private String statusJson(boolean consume){
        synchronized(lock){
            String out = "{\"ok\":true,\"busy\":"+(busy?"true":"false")+",\"action\":\""+json(action)+"\",\"percent\":"+percent+",\"message\":\""+json(message)+"\",\"error\":\""+json(error)+"\",\"forms\":\""+json(activeForms)+"\",\"stopped\":"+(stopped?"true":"false")+",\"lucydir\":\""+json(luceneRoot.getPath())+"\"}";
            if(consume && !busy && percent>=100 && (error==null || error.length()==0)){
                percent=0; action="idle"; message="Ready"; error=""; activeForms=""; stopped=false;
            }
            return out;
        }
    }

    private static String joinForms(List<String> forms){
        StringBuilder b=new StringBuilder();
        if(forms!=null)for(String f:forms){if(b.length()>0)b.append(',');b.append(f==null?"":f);}
        return b.toString();
    }

    private String result(boolean ok,String text){return "{\"ok\":"+(ok?"true":"false")+",\"message\":\""+json(text)+"\",\"lucydir\":\""+json(luceneRoot.getPath())+"\"}";}
    private static String msg(Throwable e){String s=e.getMessage();return s==null||s.trim().length()==0?e.toString():s;}
    private static String json(String s){if(s==null)return"";return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    public void close() throws InterruptedException {
        Thread worker;
        synchronized(lock){closing=true;cancelRequested=true;worker=buildThread;if(worker!=null)worker.interrupt();}
        // A GraalVM isolate must not be unloaded while its index writer still runs.
        if(worker!=null&&worker!=Thread.currentThread())worker.join();
    }
}
