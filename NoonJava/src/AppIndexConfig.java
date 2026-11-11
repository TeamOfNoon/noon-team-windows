import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Reads the web app settings without executing its JavaScript. */
final class AppIndexConfig {
    final JSONObject config;
    AppIndexConfig(File www)throws Exception{
        File f=new File(www,"res/config.js");
        if(!f.isFile())throw new IOException("Missing app config: "+f);
        config=parse(new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8));
    }
    static JSONObject parse(String source)throws IOException{
        String text=stripComments(source).trim();
        int assign=text.indexOf(".NT_CONFIG");int start=text.startsWith("{")?0:assign<0?-1:text.indexOf('{',assign),depth=0,end=-1;char quote=0;boolean escape=false;
        for(int i=start;i>=0&&i<text.length();i++){
            char c=text.charAt(i);
            if(quote!=0){if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quote=0;continue;}
            if(c=='\"'||c=='\''){quote=c;continue;}
            if(c=='{')depth++;else if(c=='}'&&--depth==0){end=i;break;}
        }
        if(start<0||end<0)throw new IOException("Cannot parse NT_CONFIG object");
        return new JSONObject(text.substring(start,end+1));
    }
    static String stripComments(String s){
        StringBuilder out=new StringBuilder();char quote=0;boolean escape=false;
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i),n=i+1<s.length()?s.charAt(i+1):0;
            if(quote!=0){out.append(c);if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quote=0;continue;}
            if(c=='\"'||c=='\''){quote=c;out.append(c);continue;}
            if(c=='/'&&n=='/'){while(i<s.length()&&s.charAt(i)!='\n')i++;out.append('\n');continue;}
            if(c=='/'&&n=='*'){i+=2;while(i+1<s.length()&&!(s.charAt(i)=='*'&&s.charAt(i+1)=='/'))i++;i++;out.append(' ');continue;}
            out.append(c);
        }return out.toString();
    }
    boolean allows(String code)throws IOException{
        if(config.has("forum_defaults")||config.has("forum_overrides")){JSONObject d=config.optJSONObject("forum_defaults"),all=config.optJSONObject("forum_overrides"),o=all==null?null:all.optJSONObject(code.toLowerCase(Locale.ROOT));boolean show=d==null||d.optBoolean("show_in_index",true);return o==null?show:o.optBoolean("show_in_index",show);}
        JSONArray forums=config.optJSONArray("forums");
        if(forums!=null){for(int i=0;i<forums.length();i++){JSONObject f=forums.optJSONObject(i);if(f!=null&&code.equalsIgnoreCase(f.optString("id",f.optString("formid",""))))return f.optBoolean("show_in_index",true)&&f.optBoolean("installed",true)&&f.optBoolean("has_sqlite",true);}return false;}
        JSONArray hidden=config.optJSONArray("hidden_search_forms");
        if(hidden!=null)for(int i=0;i<hidden.length();i++)if(code.equalsIgnoreCase(hidden.optString(i)))return false;
        for(String key:new String[]{"viewer_forms","search_forms","search_ranges"}){
            if(!config.has(key))continue;
            JSONArray a=config.optJSONArray(key);if(a==null)throw new IOException(key+" must be an array");
            boolean found=false;
            for(int i=0;i<a.length();i++){
                Object v=a.opt(i);JSONObject o=v instanceof JSONObject?(JSONObject)v:null;
                String id=o==null?String.valueOf(v):o.optString("formid",o.optString("id",""));
                if(!code.equalsIgnoreCase(id))continue;
                found=o==null||(o.optBoolean("show",true)&&o.optBoolean("installed",true)&&o.optBoolean("has_sqlite",true)&&o.optBoolean("enabled",true));
                if(found)break;
            }
            if(!found)return false;
        }return true;
    }
    boolean kind(String key){
        JSONObject ui=config.optJSONObject("ui"),selector=ui==null?null:ui.optJSONObject("searchTypeSelector");
        JSONObject opts=selector==null?null:selector.optJSONObject("options");
        return opts==null||opts.optBoolean(key,true);
    }
}
