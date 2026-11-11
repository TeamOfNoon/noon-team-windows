import java.io.File;
import java.util.Locale;
import org.json.*;
/** Local installed-forum discovery shared with the donor search implementation. */
final class ForumPackageManager {
public static String appType(File nt)throws Exception{
        String t=UpdateConfig.loadString(nt,"app_type","MN").trim().toUpperCase(Locale.ROOT);
        if(!"MN".equals(t)&&!"SN".equals(t))throw new SecurityException("app_type missing/invalid in config.js");
        return t;
    }
public static JSONArray searchForms(File nt,File luceneRoot)throws Exception{
        PlainSqliteCatalog catalog=new PlainSqliteCatalog(nt);
        LuceneIndexManager indexes=new LuceneIndexManager(nt,luceneRoot);
        JSONArray installedForms=new JSONArray();
        for(PlainSqliteCatalog.FormDb f:catalog.forms.values()){
            if(f.data==null)continue;
            JSONObject form=new JSONObject();
            form.put("id",f.key);form.put("formid",f.key);
            form.put("family",f.family);form.put("forumid",Integer.parseInt(f.forumId));
            form.put("title",catalog.forumTitle(f));
            form.put("installed",true);form.put("has_sqlite",true);
            JSONObject state=indexes.indexState(f.key);
            for(String key:new String[]{"indexed","partially_indexed","indexed_count","index_kinds"})form.put(key,state.get(key));
            installedForms.put(form);
        }
        return installedForms;
    }
}
