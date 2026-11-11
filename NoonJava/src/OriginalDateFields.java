import java.util.*;
import java.text.SimpleDateFormat;
import org.json.JSONObject;
import org.apache.lucene.document.*;

/** Original-date metadata only: never substitute publication or edit timestamps. */
final class OriginalDateFields {
    static final String[] KEYS = {"date_original","original_date","original_unix","date_original_unix","original_timestamp","hijri_date","higri","hijri_date_unix","hijri_unix","hijri_stamp","hijri_timestamp","higri_unix","miladi_date","meladi","miladi_date_unix","miladi_unix","miladi_stamp","miladi_timestamp","meladi_unix","gregorian_date","gregorian_unix","no_year"};
    static String first(JSONObject o,String... keys){for(String k:keys){String s=o.optString(k,"").trim();if(!s.isEmpty()&&!"null".equalsIgnoreCase(s))return s;}return "";}
    static long unix(String s){try{long v=Long.parseLong(s);return v>100000000000L?v/1000:v;}catch(Exception e){return 0;}}

    static long gregorian(String value){
        String s=value.replace("ـ","").replaceAll("\\s*-\\s*","-").replaceAll("(?i)\\s*(?:AD|م)\\s*$","").trim();
        for(int i=0;i<10;i++){s=s.replace((char)('٠'+i),(char)('0'+i));s=s.replace((char)('۰'+i),(char)('0'+i));}
        for(String fmt:new String[]{"yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy-MM-dd","dd-MM-yyyy HH:mm:ss","dd-MM-yyyy HH:mm","dd-MM-yyyy","dd/MM/yyyy","yyyy/MM/dd"})try{
            SimpleDateFormat f=new SimpleDateFormat(fmt,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));java.text.ParsePosition p=new java.text.ParsePosition(0);Date d=f.parse(s,p);
            if(d!=null&&p.getIndex()==s.length())return d.getTime()/1000;
        }catch(Exception ignore){}return 0;
    }
    static void add(Document d,JSONObject o){
        String h=first(o,"hijri_date","higri"),m=first(o,"miladi_date","meladi","gregorian_date"),original=first(o,"date_original","original_date");
        long hu=unix(first(o,"hijri_date_unix","hijri_unix","hijri_stamp","hijri_timestamp","higri_unix")),mu=unix(first(o,"miladi_date_unix","miladi_unix","miladi_stamp","miladi_timestamp","meladi_unix","gregorian_unix"));
        long key=unix(first(o,"date_original_unix","original_unix","original_timestamp"));
        if(key==0)key=mu!=0?mu:hu;
        // Text conversion is strictly a fallback after all original Unix fields.
        if(key==0)key=gregorian(m);if(key==0)key=gregorian(original);
        if(m.isEmpty()&&key!=0){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));m=f.format(new Date(key*1000));}
        if(original.isEmpty())original=m.isEmpty()?h:m;
        d.add(new StoredField("date_original",original));d.add(new StoredField("hijri_date",h));d.add(new StoredField("miladi_date",m));
        d.add(new StoredField("hijri_date_unix",hu));d.add(new StoredField("miladi_date_unix",mu));
        d.add(new StoredField("hijri_unix",hu));d.add(new StoredField("miladi_unix",mu));
        d.add(new StoredField("date_original_unix",key));d.add(new NumericDocValuesField("date_original_sort",key));
        d.add(new StringField("date_no_year",key==0?"true":"false",Field.Store.YES));
    }
    static String display(String h,String m,String fallback){h=h==null?"":h;m=m==null?"":m;return !h.isEmpty()&&!m.isEmpty()?h+" | "+m:!h.isEmpty()?h:!m.isEmpty()?m:fallback==null?"":fallback;}
    static long key(Document d){return unix(d.get("date_original_unix"));}
}
