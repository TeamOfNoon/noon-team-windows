import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Adds the live search mode and detected forum/index state to the app config script. */
public final class SearchIndexConfig {
    private static final String START = "/* NOON_SEARCH_CONFIG_START */";
    private static final String END = "/* NOON_SEARCH_CONFIG_END */";
    private SearchIndexConfig() { }

    /** Only the export command writes config bytes; HTTP serving stays in memory. */
    public static void writeStatic(File appRoot, File ntRoot, File luceneRoot) throws Exception {
        File config = new File(new File(new File(appRoot, "www"), "res"), "config.js");
        if (!config.isFile()) throw new IOException("App config.js not found: " + config);
        byte[] snapshot = withState(Files.readAllBytes(config.toPath()),
                UpdateConfig.loadSearchIndexType(ntRoot), ntRoot, luceneRoot, false);
        Files.write(config.toPath(), snapshot);
        JSONArray state=ForumPackageManager.searchForms(ntRoot,luceneRoot);
        Files.write(new File(config.getParentFile(),"forum-state.js").toPath(),("/* Generated forum availability/index state. */\nwindow.NT_FORUM_STATE=window.NT_FORUM_STATE||"+state.toString(2)+";\n").getBytes(StandardCharsets.UTF_8));
        File stopWords=new File(appRoot,"www/res/SW.js");
        if(!stopWords.exists())Files.write(stopWords.toPath(),SearchSnippetPreview.defaultStopWords().getBytes(StandardCharsets.UTF_8));
        File logic=new File(appRoot,"www/res/searcher/search_logic.js");
        if(logic.isFile())Files.write(logic.toPath(),SearchSnippetPreview.normalizeSearchLogic(new String(Files.readAllBytes(logic.toPath()),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        File viewer = new File(appRoot, "www/res/viewer/app.js");
        if(viewer.isFile())Files.write(viewer.toPath(), SearchSnippetPreview.applyViewer(new String(withViewerAvailability(Files.readAllBytes(viewer.toPath())),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        File domains = new File(appRoot, "www/res/viewer/domain-text.js");
        if(domains.isFile())Files.write(domains.toPath(), withDomainTextAvailability(Files.readAllBytes(domains.toPath())));
        File searchCss=new File(appRoot,"www/res/searcher/css/base.css");
        if(searchCss.isFile())Files.write(searchCss.toPath(),SearchSnippetPreview.searchResultCss(new String(Files.readAllBytes(searchCss.toPath()),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        File searcher = new File(appRoot, "www/res/searcher/app.js");
        if(searcher.isFile())Files.write(searcher.toPath(), SearchSnippetPreview.apply(new String(withSearchAvailability(Files.readAllBytes(searcher.toPath())),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
    }

    /** Apply the same archive exclusion to runtime and standalone exported apps. */
    public static byte[] withDomainTextAvailability(byte[] original) {
        String code=new String(original,StandardCharsets.UTF_8);
        if(code.contains("/* NOON_DOMAIN_ARCHIVE_GUARD */"))return original;
        String guard="/* NOON_DOMAIN_ARCHIVE_GUARD */\n(function(){var q=String(window.location.search||'');"+
            "if(/[?&]ftype=vb(?:&|$)/i.test(q)&&/[?&]fo=(?:2|5)(?:&|$)/.test(q))return;\n";
        return (guard+code+"\n})();").getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] withRuntimeState(byte[] original, File ntRoot, File luceneRoot, String type) throws Exception {
        return withState(original, type, ntRoot, luceneRoot, true);
    }

    private static byte[] withState(byte[] original, String requestedType, File ntRoot, File luceneRoot, boolean runtime) throws Exception {
        String type = normalize(requestedType);
        if ("lucene".equals(type) && luceneRoot == null) type = "json_js";
        String appType = ForumPackageManager.appType(ntRoot);
        JSONArray forms = ForumPackageManager.searchForms(ntRoot, luceneRoot);
        JSONArray allowed=new JSONArray();
        for(int i=0;i<forms.length();i++)allowed.put(forms.getJSONObject(i).getString("formid"));
        String text = SearchSnippetPreview.applyConfig(original == null ? "" : new String(original, StandardCharsets.UTF_8));
        String stopPath=UpdateConfig.loadObject(ntRoot).optString("stop_word_path", "");
        if(!stopPath.trim().isEmpty())text=replaceAppProperty(text,"stop_word_path",JSONObject.quote(stopPath));
        int start = text.indexOf(START), end = start < 0 ? -1 : text.indexOf(END, start + START.length());
        if (start >= 0 && end >= 0) text = text.substring(0, start) + text.substring(end + END.length());
        JSONObject desktop=UpdateConfig.loadObject(ntRoot);

        if(!runtime){
            String readable=readableConfig(text, type, appType, forms, allowed);
            for(String key:desktop.keySet()){
                if(key.equals("search_index_type")||key.equals("app_type")||key.equals("search_forms")||key.equals("search_ranges")||key.equals("viewer_forms"))continue;
                readable=replaceAppProperty(readable,key,JSONObject.valueToString(desktop.get(key)));
            }
            return readable.getBytes(StandardCharsets.UTF_8);
        }
        JSONObject defaults = runtime ? UpdateConfig.loadObject(ntRoot) : new JSONObject();
        String block = "\n" + START + "\n" +
                ";(function(w){w.NT_CONFIG=w.NT_CONFIG||{};" +
                "var d=" + defaults.toString() + ";for(var k in d){if(!Object.prototype.hasOwnProperty.call(d,k))continue;" +
                "w.NT_CONFIG[k]=d[k];}" +
                "w.NT_CONFIG.search_index_type=\"" + type + "\";" +
                "w.NT_FORUM_STATE=" + forms.toString() + ";" +
                "w.NT_CONFIG.app_type=\"" + appType + "\";" +
                "w.search_index_type=\"" + type + "\";})(window);\n" + END + "\n";
        return (text + block).getBytes(StandardCharsets.UTF_8);
    }

    /** Update only top-level generated settings, retaining UI choices and comments. */
    private static String readableConfig(String text, String type, String appType,
            JSONArray forms, JSONArray allowed) throws IOException {
        java.util.regex.Matcher assignment=java.util.regex.Pattern.compile("(?:w|window)\\.NT_CONFIG\\s*=\\s*\\{").matcher(text);
        if(!assignment.find())throw new IOException("Cannot find NT_CONFIG object in exported config.js");
        int open=assignment.end()-1;
        java.util.List<Integer> separators=new java.util.ArrayList<Integer>();
        int close=scanObject(text,open,separators);
        java.util.Map<String,String> values=new java.util.LinkedHashMap<String,String>();
        values.put("search_index_type",JSONObject.quote(type));

        values.put("app_type",JSONObject.quote(appType));
        StringBuilder body=new StringBuilder();int from=open+1;
        separators.add(close);
        java.util.regex.Pattern property=java.util.regex.Pattern.compile(
                "(?s)^((?:\\s|/\\*.*?\\*/|//[^\\n]*\\n)*)([A-Za-z_$][A-Za-z0-9_$]*|\"[^\"]+\"|'[^']+')\\s*:\\s*");
        for(int to:separators){
            String segment=text.substring(from,to);
            java.util.regex.Matcher m=property.matcher(segment);
            if(m.find()){
                String key=m.group(2).replace("\"", "").replace("'", "");
                if(key.equals("forums")||key.equals("search_forms")||key.equals("viewer_forms")||key.equals("search_ranges")||key.equals("hidden_search_forms")){from=to+1;continue;}
                if(values.containsKey(key)){
                    int end=segment.length();while(end>m.end()&&Character.isWhitespace(segment.charAt(end-1)))end--;
                    segment=segment.substring(0,m.end())+values.remove(key)+segment.substring(end);
                }
            }
            body.append(segment);if(to!=close)body.append(',');from=to+1;
        }
        StringBuilder added=new StringBuilder();
        for(java.util.Map.Entry<String,String> e:values.entrySet())
            added.append("\n    ").append(e.getKey()).append(": ").append(e.getValue()).append(',');
        return text.substring(0,open+1)+added+body+text.substring(close);
    }

    private static JSONObject mergeForumSettings(JSONObject base,JSONObject web){
        JSONObject merged=base==null?new JSONObject():new JSONObject(base.toString());
        if(web!=null)for(String key:web.keySet()){
            Object value=web.get(key);
            if(value instanceof JSONObject && merged.optJSONObject(key)!=null)
                value=mergeForumSettings(merged.optJSONObject(key),(JSONObject)value);
            merged.put(key,value);
        }
        return merged;
    }

    private static String indentJson(String json) { return json.replace("\n", "\n    "); }

    /** Locate object boundaries and top-level commas without interpreting JavaScript. */
    private static int scanObject(String text,int open,java.util.List<Integer> commas)throws IOException {
        int depth=1;char quote=0;boolean line=false,block=false,escape=false;
        for(int i=open+1;i<text.length();i++){
            char c=text.charAt(i),next=i+1<text.length()?text.charAt(i+1):0;
            if(line){if(c=='\n'||c=='\r')line=false;continue;}
            if(block){if(c=='*'&&next=='/'){block=false;i++;}continue;}
            if(quote!=0){if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quote=0;continue;}
            if(c=='/'&&next=='/'){line=true;i++;continue;}
            if(c=='/'&&next=='*'){block=true;i++;continue;}
            if(c=='\"'||c=='\''||c=='`'){quote=c;continue;}
            if(c=='{'||c=='['||c=='(')depth++;
            else if(c=='}'||c==']'||c==')'){depth--;if(depth==0)return i;}
            else if(c==','&&depth==1)commas.add(i);
        }
        throw new IOException("Unclosed NT_CONFIG object in exported config.js");
    }

    /** Apply the config allow-list to the existing viewer tree without changing its layout. */
    public static byte[] withViewerAvailability(byte[] original) {
        String text=new String(original,StandardCharsets.UTF_8);
        String old="hasSqlite=String(row.has_sqlite)==='1'||row.has_sqlite===true;";
        String updated="hasSqlite=(String(row.has_sqlite)==='1'||row.has_sqlite===true)&&noonForumAllowed(family,currentId);";
        if(text.indexOf(old)<0)return PartialIndexSupport.applyViewer(text).getBytes(StandardCharsets.UTF_8);
        String helper="function noonForumAllowed(family,id){var a=(window.NT_CONFIG||{}).viewer_forms;if(!a||typeof a.length!=='number')return true;var c=String(family)+id,i;for(i=0;i<a.length;i++){if(String(a[i]).toLowerCase()===c.toLowerCase())return true;}return false;}\n";
        return PartialIndexSupport.applyViewer(helper+text.replace(old,updated)).getBytes(StandardCharsets.UTF_8);
    }

    /** Hide empty search groups instead of showing a disabled needs-indexing row. */
    public static byte[] withSearchAvailability(byte[] original) {
        String text=new String(original,StandardCharsets.UTF_8);
        String helper="function noonSearchConfigVisible(code){var c=window.NT_CONFIG||{},d=c.forum_defaults||{},o=(c.forum_overrides||{})[String(code).toLowerCase()]||{};return Object.prototype.hasOwnProperty.call(o,'show_in_search')?o.show_in_search!==false:d.show_in_search!==false;}\n";
        if(text.indexOf("function noonSearchConfigVisible(")<0)text=helper+text;
        String loop="for(i=0;i<defs.length;i++)h+=";
        String filtered="defs=defs.filter(function(d){return rangeSummary[d[0]]&&rangeSummary[d[0]].total>0;});";
        if(text.indexOf(filtered)<0)text=text.replace(loop,filtered+loop);
        text=text.replace("if(code==='vb4')return'vb';", "if(/^vb\\d+$/.test(code))return'vb';");
        // Upgrade packaged search pages to preserve choices and select newly indexed forums.
        int detailsStart=text.indexOf(" function renderDetails(forms){");
        int detailsEnd=detailsStart<0?-1:text.indexOf("\n if(show)",detailsStart);
        if(detailsStart>=0&&detailsEnd>detailsStart)text=text.substring(0,detailsStart)+" function renderDetails(forms){\n  var h='',i,f,code,k,rows=[],preserved={},previousEnabled={},newlyIndexed=false,j;\n  if(scopeStatusRendered){for(j=0;j<scopeChecks.length;j++){var previousCode=String(scopeChecks[j].value||'');preserved[previousCode]=!!scopeChecks[j].checked;previousEnabled[previousCode]=!scopeChecks[j].disabled;}}\n  available=[];\n  /* forms is already in exact JS-tree preorder. Never regroup/re-sort it. */\n  for(i=0;i<(forms||[]).length;i++){\n   f=forms[i]||{};code=codeOf(f);k=kindOf(code);if(!code||!noonSearchConfigVisible(code)||!noonSearchFormVisible(code)||!noonFormAllowed(\"search_forms\",code)||!noonFormAllowed(\"search_ranges\",code))continue;\n   rows.push({f:f,code:code,kind:k,indexed:!!f.indexed,treeDepth:+f.treeDepth||0,treeOrder:+f.treeOrder||i});\n  }\n  rangeSummary={main:{total:0,installed:0,indexed:0},foreign:{total:0,installed:0,indexed:0},sn:{total:0,installed:0,indexed:0},vb:{total:0,installed:0,indexed:0}};\n  for(i=0;i<rows.length;i++){var rs=rangeSummary[rows[i].kind];if(!rs)continue;rs.total++;rs.installed++;if(rows[i].indexed)rs.indexed++;}\n  var lastKind='',rangeTitles=appType==='SN'?{main:'قسم موسوعة نون الإمام',foreign:'الأقسام الأجنبية لمنتديات البشرى الإسلامية',sn:'اقسام شاملة نون لمنتديات البشرى الاسلامية',vb:'أقسام التطبيق المخصصة'}:{main:'قسم موسوعة الإمام',foreign:'الأقسام الأجنبية لمنتديات البشرى الإسلامية',vb:'أقسام التطبيق المخصصة'};\n  for(i=0;i<rows.length;i++){\n   f=rows[i].f;code=rows[i].code;k=rows[i].kind;if(rows[i].indexed)available.push(code);\n   /* Keep tree order. A range heading is emitted only when the tree reaches a\n      different logical range; it never changes the forum ordering. */\n   if(k&&k!==lastKind){h+='<div class=\"custom-range-header\" data-range-kind=\"'+k+'\">'+escScope(rangeTitles[k]||k)+'</div>';lastKind=k;}\n   var stateText=rows[i].indexed?'':'يحتاج فهرسة';\n   h+='<label class=\"search-scope-option custom-detail-option'+(rows[i].indexed?'':' needs-indexing')+'\" data-form-kind=\"'+k+'\" style=\"padding-right:'+escScope(12+(rows[i].treeDepth||0)*22)+'px\"><input type=\"checkbox\" name=\"searchScope\" value=\"'+escScope(code)+'\"'+(rows[i].indexed?'':' disabled=\"disabled\"')+'><span class=\"custom-detail-text\"><span>'+escScope(defaultTitle(code,f))+' — '+escScope(code)+'</span>'+(stateText?'<small class=\"needs-indexing-text\">'+stateText+'</small>':'')+'</span></label>';\n  }\n  list.innerHTML=h||'<div class=\"search-section-notice\">لا توجد أقسام متاحة في شجرة JS.</div>';\n  var saved=String(searchStorageGet(SEARCH_OPTION_KEYS.scopes)||'').split(','),map={},enabled=0;\n  for(j=0;j<saved.length;j++)if(saved[j])map[saved[j]]=1;\n  for(j=0;j<scopeChecks.length;j++){\n   var scopeCode=String(scopeChecks[j].value||'').toLowerCase();\n   if(scopeStatusRendered){var becameIndexed=!scopeChecks[j].disabled&&previousEnabled[scopeChecks[j].value]===false;scopeChecks[j].checked=!scopeChecks[j].disabled&&(becameIndexed||!!preserved[scopeChecks[j].value]);if(becameIndexed)newlyIndexed=true;}\n   else scopeChecks[j].checked=!scopeChecks[j].disabled&&(searchScopeWasStored?!!map[scopeChecks[j].value]:(scopeCode==='mn33'||scopeCode==='vb4'));\n   if(!scopeChecks[j].disabled)enabled++;\n  }\n  scopeStatusRendered=true;bindDetails();renderMain();\n  /* An idle Lucene search is ready only when searchable indexes exist. */\n  if(String((window.NT_CONFIG||{}).search_index_type||'').toLowerCase()==='lucene'&&status){\n   var idleText=String(status.textContent||status.innerText||'').replace(/^\\s+|\\s+$/g,'');\n   if(!idleText||idleText==='البحث جاهز'||idleText==='Search ready'){\n    var readyLabel='البحث جاهز';\n    if(window.NT_PAGE_LANG&&window.NT_PAGE_LANG.text)readyLabel=window.NT_PAGE_LANG.text[readyLabel]||readyLabel;\n    status.textContent=available.length?readyLabel:'';\n   }\n  }\n  syncParentFromChildren('main');syncParentFromChildren('foreign');syncParentFromChildren('sn');syncParentFromChildren('vb');\n  if(newlyIndexed)saveSearchOptionsToStorage();\n }"+text.substring(detailsEnd);
        if(text.indexOf("/* NOON_DESKTOP_SCOPE_REFRESH */")<0)text+="\n"+"/* NOON_DESKTOP_SCOPE_REFRESH */\n(function(){\n var busy=false,pending=false;\n function refresh(){if(typeof window.refreshSearchScopeIndexState==='function')window.refreshSearchScopeIndexState();}\n window.addEventListener('focus',refresh,false);\n window.addEventListener('pageshow',refresh,false);\n window.addEventListener('message',function(ev){var d=ev.data||{};if(d.type==='search-tab-active'&&d.active!==false)refresh();},false);\n document.addEventListener('visibilitychange',function(){if(!document.hidden)refresh();},false);\n setInterval(function(){\n  if(pending||document.hidden||String((window.NT_CONFIG||{}).search_index_type||'').toLowerCase()!=='lucene')return;\n  pending=true;var xhr=new XMLHttpRequest();xhr.open('GET','/__lucene/status?ts='+Date.now(),true);xhr.timeout=5000;\n  xhr.onload=function(){pending=false;if(xhr.status!==200)return;try{var state=JSON.parse(xhr.responseText);if(state.busy)busy=true;else if(busy){busy=false;refresh();}}catch(ignore){}};\n  xhr.onerror=xhr.ontimeout=function(){pending=false;};xhr.send(null);\n },1500);\n})();\n";
        return PartialIndexSupport.apply(text).getBytes(StandardCharsets.UTF_8);
    }

    /** An explicit index_js/index_bin command selects the generated transport. */
    public static void writeCompactState(File www,boolean js,java.util.Set<String> indexedForms)throws Exception{
        File file=new File(www,"res/config.js");
        AppIndexConfig parsed=new AppIndexConfig(www);
        JSONArray forms=parsed.config.optJSONArray("search_forms"),allowed=parsed.config.optJSONArray("viewer_forms");
        if(forms==null)forms=new JSONArray();if(allowed==null)allowed=new JSONArray();
        String text=new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
        // Change only the engine, preserving all lists, UI settings and comments.
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("([\"']?search_index_type[\"']?\\s*:\\s*)[\"'][^\"']*[\"']").matcher(text);
        String value=JSONObject.quote(js?"js":"bin");
        if(m.find())text=text.substring(0,m.start())+m.group(1)+value+text.substring(m.end());
        else{java.util.regex.Matcher assignment=java.util.regex.Pattern.compile("(?:w|window)\\.NT_CONFIG\\s*=\\s*\\{").matcher(text);if(!assignment.find())throw new IOException("Cannot locate NT_CONFIG");text=text.substring(0,assignment.end())+"\n    search_index_type: "+value+","+text.substring(assignment.end());}
        for(String key:new String[]{"forums","search_forms","search_ranges"}){
            JSONArray list=parsed.config.optJSONArray(key);if(list==null)continue;
            for(int i=0;i<list.length();i++){
                JSONObject row=list.optJSONObject(i);if(row==null)continue;
                String code=row.optString("formid",row.optString("id",""));
                if(indexedForms.contains(code.toLowerCase(java.util.Locale.ROOT)))row.put("indexed",true);
            }
            text=replaceAppProperty(text,key,indentJson(list.toString(2)));
        }
        File stateFile=new File(www,"res/forum-state.js");
        if(stateFile.isFile()){String script=new String(Files.readAllBytes(stateFile.toPath()),StandardCharsets.UTF_8);int first=script.indexOf('['),last=script.lastIndexOf(']');if(first>=0&&last>first){JSONArray state=new JSONArray(script.substring(first,last+1));for(int i=0;i<state.length();i++){JSONObject row=state.optJSONObject(i);if(row!=null&&indexedForms.contains(row.optString("formid",row.optString("id","")).toLowerCase(java.util.Locale.ROOT)))row.put("indexed",true);}Files.write(stateFile.toPath(),(script.substring(0,first)+state.toString(2)+script.substring(last+1)).getBytes(StandardCharsets.UTF_8));}}
        Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
    static String replaceAppProperty(String text,String key,String value)throws IOException{
        java.util.regex.Matcher assignment=java.util.regex.Pattern.compile("(?:w|window)\\.NT_CONFIG\\s*=\\s*\\{").matcher(text);
        if(!assignment.find())throw new IOException("Cannot locate NT_CONFIG");
        int open=assignment.end()-1;java.util.List<Integer> commas=new java.util.ArrayList<Integer>();int close=scanObject(text,open,commas);commas.add(close);int from=open+1;
        java.util.regex.Pattern property=java.util.regex.Pattern.compile("(?s)^(?:\\s|/\\*.*?\\*/|//[^\\n]*\\n)*[\"']?"+key+"[\"']?\\s*:\\s*");
        for(int to:commas){String segment=text.substring(from,to);java.util.regex.Matcher m=property.matcher(segment);if(m.find())return text.substring(0,from+m.end())+value+text.substring(to);from=to+1;}
        return text.substring(0,open+1)+"\n    "+key+": "+value+","+text.substring(open+1);
    }

    private static String normalize(String value) {
        String type = value == null ? "json_js" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("lucen".equals(type) || "lucy".equals(type)) type = "lucene";
        if ("js".equals(type) || "json".equals(type)) type = "json_js";
        if ("bin".equals(type) || "binary".equals(type)) type = "json_bin";
        return "lucene".equals(type) || "json_bin".equals(type) ? type : "json_js";
    }
}
