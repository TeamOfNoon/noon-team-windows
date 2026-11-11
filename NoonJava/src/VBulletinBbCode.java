import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Safe, dependency-free vBulletin-style BBCode to HTML renderer. */
final class VBulletinBbCode {
    private static final Pattern TAG = Pattern.compile("\\[(/?)([a-zA-Z_*]+)(?:=([^\\]]*))?\\]");
    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
        "b","i","u","s","strike","sub","sup","highlight","color","size","font",
        "left","center","right","indent","quote","url","email","img","thread","post",
        "list","code","php","html","noparse","h","hr","video",
        "chatright","chatleft","table","tr","td","movepic","src_link"
    ));

    private VBulletinBbCode() { }

    static String toHtml(String source) {
        if (source == null || source.length() == 0) return "";

        // Some archive rows were imported through JSON/HTML layers. Normalize
        // those storage wrappers before deciding whether the payload is BBCode
        // or already-rendered HTML. This prevents escaped tags such as
        // \u003cspan...\u003e / &lt;span...&gt; from being printed literally.
        source = normalizeStoredContent(source);

        /*
         * IMPORTANT: do not manufacture the vBulletin outer post wrapper.
         * <blockquote class="postcontent restore"> belongs to pagetext only
         * when that exact wrapper was stored in SQLite.  The Java viewer must
         * never add it around ordinary posts.
         */
        // The outer vBulletin post container is presentation markup stored in
        // pagetext. The Java viewer already provides its own post container, so
        // remove ONLY this outer wrapper. Nested/real quote blockquotes remain.
        source = stripOuterPostContentWrapper(source);

        /*
         * The archive contains TWO real storage formats:
         *   1) vBulletin BBCode (MN/VB-style rows), and
         *   2) already-rendered legacy HTML (many SN rows).
         *
         * viewer.php preserves the second form as HTML.  The old Java path ran
         * every row through the BBCode text escaper, turning <font>, <br>,
         * <div>, <a>, ... into visible text.  Detect genuine stored HTML first
         * and pass it through unchanged (apart from script removal).
         */
        if (looksLikeStoredHtml(source)) {
            return imagesAsLinks(storedHtml(source));
        }

        source = imagesAsBbCode(source);

        // Scraped vBulletin text can contain long runs of empty source lines.
        // nl2br-ing them verbatim produces the very large gaps seen in posts.
        source=source.replace("\r\n","\n").replace('\r','\n');
        source=source.replaceAll("[ \\t]+(?=\\n)","");
        source=source.replaceAll("\\n(?:[ \\t]*\\n){2,}","\n\n");
        Node root = new Node("", null, null);
        ArrayList<Node> stack = new ArrayList<Node>(); stack.add(root);
        Matcher m = TAG.matcher(source); int at = 0;
        while (m.find()) {
            Node current = stack.get(stack.size() - 1);
            if (isLiteral(current.name)) {
                String literalName=m.group(2).toLowerCase(Locale.ROOT);
                if ("/".equals(m.group(1)) && current.name.equals(literalName)) {
                    current.parts.add(source.substring(at,m.start())); stack.remove(stack.size()-1); at=m.end();
                }
                continue;
            }
            if (m.start() > at) current.parts.add(source.substring(at, m.start()));
            String slash=m.group(1), name=m.group(2).toLowerCase(Locale.ROOT), option=m.group(3);
            if (!KNOWN.contains(name)) { current.parts.add(m.group()); at=m.end(); continue; }
            if ("/".equals(slash)) {
                int found=-1; for(int i=stack.size()-1;i>0;i--)if(stack.get(i).name.equals(name)){found=i;break;}
                // vBulletin repairs broken nesting; a known closing tag with no
                // matching opener is discarded instead of leaking into output.
                if(found>=0)while(stack.size()>found)stack.remove(stack.size()-1);
            } else if ("hr".equals(name)) current.parts.add(new Node(name, option, m.group()));
            else { Node n=new Node(name,cleanOption(option),m.group());current.parts.add(n);stack.add(n); }
            at=m.end();
        }
        stack.get(stack.size()-1).parts.add(source.substring(at));
        String html=renderParts(root.parts, false);
        // Preserve authored line breaks, including breaks inside FONT/COLOR
        // wrappers adjacent to CENTER/DIV blocks. A block boundary is not a
        // substitute for the explicit blank line represented by two BRs.
        return normalizeBlockSpacing(html);
    }

    // A block element already starts a new visual line. An adjacent generated
    // BR adds a second, unintended line. Consume just one boundary BR,
    // preserving any additional BR explicitly authored inside FONT/COLOR.
    private static String normalizeBlockSpacing(String html) {
        String blockStart = "(?:div|table|ul|ol|pre|h[1-6]|blockquote)";
        String blockEnd = "(?:div|table|ul|ol|pre|h[1-6]|blockquote)";
        String br = "<br\\s*/?>";
        html = html.replaceAll("(?i)" + br + "[ \t\r\n]*(?=<" + blockStart + "\\b)", "");
        // BR following a closing block is also redundant; preserve surplus.
        html = html.replaceAll("(?i)(</" + blockEnd + "\\s*>)[ \t\r\n]*" + br, "$1");
        return html;
    }

    private static String normalizeStoredContent(String source) {
        String s=source==null?"":source;
        // Unwrap JSON string values, including content accidentally encoded more
        // than once. Never reinterpret ordinary text as JSON.
        for(int i=0;i<3;i++){
            String t=s.trim();
            if(t.length()<2 || t.charAt(0)!='"' || t.charAt(t.length()-1)!='"')break;
            try{
                Object v=new JSONTokener(t).nextValue();
                if(!(v instanceof String))break;
                String n=(String)v;if(n.equals(s))break;s=n;
            }catch(Exception e){break;}
        }
        // Decode common HTML entities only when doing so reveals actual markup.
        // This keeps normal prose entities intact while restoring archived HTML.
        String d=decodeHtmlEntities(s);
        if(HTML_TAG.matcher(d).find() || HTML_START.matcher(d).find())s=d;
        return s;
    }

    private static String decodeHtmlEntities(String s){
        if(s==null||s.indexOf('&')<0)return s;
        StringBuilder out=new StringBuilder(s.length());
        for(int i=0;i<s.length();){
            char ch=s.charAt(i);
            if(ch!='&'){out.append(ch);i++;continue;}
            int semi=s.indexOf(';',i+1);
            if(semi<0||semi-i>12){out.append(ch);i++;continue;}
            String e=s.substring(i+1,semi);String r=null;
            if("lt".equalsIgnoreCase(e))r="<";else if("gt".equalsIgnoreCase(e))r=">";
            else if("quot".equalsIgnoreCase(e))r="\\\"";else if("apos".equalsIgnoreCase(e)||"#39".equals(e))r="'";
            else if("amp".equalsIgnoreCase(e))r="&";
            else if(e.startsWith("#x")||e.startsWith("#X")){try{r=new String(Character.toChars(Integer.parseInt(e.substring(2),16)));}catch(Exception ignore){}}
            else if(e.startsWith("#")){try{r=new String(Character.toChars(Integer.parseInt(e.substring(1))));}catch(Exception ignore){}}
            if(r==null){out.append(ch);i++;}else{out.append(r);i=semi+1;}
        }
        return out.toString();
    }

    private static final Pattern OUTER_POSTCONTENT_OPEN = Pattern.compile(
        "(?is)^\\s*<blockquote\\b(?=[^>]*\\bclass\\s*=\\s*[\\\"\\'][^\\\"\\']*\\bpostcontent\\b[^\\\"\\']*[\\\"\\'])[^>]*>"
    );
    private static final Pattern OUTER_POSTCONTENT_CLOSE = Pattern.compile(
        "(?is)</blockquote\\s*>\\s*$"
    );

    private static String stripOuterPostContentWrapper(String source) {
        if (source == null || source.length() == 0) return "";
        Matcher open = OUTER_POSTCONTENT_OPEN.matcher(source);
        if (!open.find()) return source;

        // Strip the opening wrapper and ONLY the final matching presentation
        // close. Internal <blockquote> quote content is left untouched.
        String inner = source.substring(open.end());
        Matcher close = OUTER_POSTCONTENT_CLOSE.matcher(inner);
        if (close.find()) inner = inner.substring(0, close.start());
        return inner;
    }


    private static final Pattern HTML_TAG = Pattern.compile(
        "(?is)<\\/?(?:html|body|div|span|font|blockquote|p|br|a|img|table|tbody|thead|tfoot|tr|td|th|ul|ol|li|b|strong|i|em|u|s|strike|sub|sup|center|pre|code|hr|h[1-6])\\b[^>]*>"
    );
    private static final Pattern HTML_START = Pattern.compile(
        "(?is)^\\s*<(?:!doctype\\s+html|html|body|div|span|font|blockquote|p|a|img|table|ul|ol|center|pre|h[1-6])\\b"
    );

    private static boolean looksLikeStoredHtml(String source) {
        if (HTML_START.matcher(source).find()) return true;

        int html = 0;
        Matcher hm = HTML_TAG.matcher(source);
        while (hm.find() && html < 8) html++;
        if (html < 3) return false;

        int bb = 0;
        Matcher bm = TAG.matcher(source);
        while (bm.find() && bb < 16) bb++;

        // A stray <br /> inside a BBCode URL option must not flip the whole row
        // to HTML mode.  Legacy HTML rows normally contain several real tags.
        return html >= 3 && html >= Math.max(3, bb * 2);
    }

    private static String storedHtml(String source) {
        String s = source.replace("\r\n", "\n").replace('\r', '\n');
        // The viewer inserts this result with innerHTML. Never carry executable
        // script blocks from archived content into the local application page.
        s = s.replaceAll("(?is)<script\\b[^>]*>.*?</script\\s*>", "");
        s = s.replaceAll("(?is)<script\\b[^>]*/\\s*>", "");
        // Ignore legacy font sizes, preserving color and other presentation.
        s = s.replaceAll("(?is)(<font\\b[^>]*?)\\s+size\\s*=\\s*(?:\"[^\"]*\"|\'[^\']*\'|[^\\s>]+)", "$1");
        return s;
    }

    private static final Pattern IMAGE = Pattern.compile("(?is)<img\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>");
    private static final Pattern IMAGE_SOURCE = Pattern.compile("(?is)\\s(src|data-src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static String imageUrl(String tag) {
        Matcher m = IMAGE_SOURCE.matcher(tag); String src="", lazy="";
        while(m.find()) {
            String value=decodeHtmlEntities(m.group(2)!=null?m.group(2):m.group(3)!=null?m.group(3):m.group(4)).trim();
            if("src".equalsIgnoreCase(m.group(1)))src=value;else lazy=value;
        }
        return src.length()>0?src:lazy;
    }
    private static String imagesAsBbCode(String source) {
        Matcher m=IMAGE.matcher(source);StringBuffer out=new StringBuffer();
        while(m.find()){String url=imageUrl(m.group());m.appendReplacement(out,Matcher.quoteReplacement("[url]"+url+"[/url]"));}
        m.appendTail(out);return out.toString();
    }
    private static String imagesAsLinks(String source) {
        // An image-only anchor becomes one link, rather than nested anchors.
        Pattern linked=Pattern.compile("(?is)<a\\b[^>]*>\\s*(<img\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>)\\s*</a\\s*>");
        Matcher a=linked.matcher(source);StringBuffer first=new StringBuffer();
        while(a.find()){String url=imageUrl(a.group(1));a.appendReplacement(first,Matcher.quoteReplacement(link(url,esc(url))));}
        a.appendTail(first);
        Matcher m=IMAGE.matcher(first.toString());StringBuffer out=new StringBuffer();
        while(m.find()){String url=imageUrl(m.group());m.appendReplacement(out,Matcher.quoteReplacement(link(url,esc(url))));}
        m.appendTail(out);return out.toString();
    }

    private static boolean isLiteral(String n){return "code".equals(n)||"php".equals(n)||"html".equals(n)||"noparse".equals(n);}
    private static String cleanOption(String s){if(s==null)return null;s=s.trim();if(s.length()>1&&((s.charAt(0)=='\"'&&s.charAt(s.length()-1)=='\"')||(s.charAt(0)=='\''&&s.charAt(s.length()-1)=='\'')))s=s.substring(1,s.length()-1);return s;}
    private static String renderParts(List<Object> parts,boolean pre){StringBuilder b=new StringBuilder();for(Object o:parts){if(o instanceof String)b.append(pre?esc((String)o):lines((String)o));else b.append(render((Node)o));}return b.toString();}
    private static String rawParts(List<Object> parts){StringBuilder b=new StringBuilder();for(Object o:parts){if(o instanceof String)b.append((String)o);else{Node n=(Node)o;b.append(n.open==null?"":n.open).append(rawParts(n.parts));if(!"hr".equals(n.name))b.append("[/").append(n.name).append(']');}}return b.toString();}
    private static String render(Node n){
        String name=n.name, body=renderParts(n.parts,false), option=n.option==null?"":n.option;
        if("b".equals(name))return wrap("b",body); if("i".equals(name))return wrap("i",body); if("u".equals(name))return wrap("u",body);
        if("s".equals(name)||"strike".equals(name))return wrap("s",body); if("sub".equals(name))return wrap("sub",body);if("sup".equals(name))return wrap("sup",body);
        if("chatright".equals(name)||"chatleft".equals(name)) {
            String side="chatright".equals(name)?"right":"left";
            return "<div class=\"bbcode-chat bbcode-"+name+"\" style=\"text-align: "+side+"; border: 1px solid currentColor; border-radius: 8px; padding: 12px; margin: 12px 0;\">"+trimBreaks(body)+"</div>";
        }
        if("table".equals(name))return "<table class=\"bbcode-table"+("grid".equalsIgnoreCase(tableOption(option,"class"))?" grid":"")+"\" style=\"border-collapse: collapse; max-width: 100%; "+tableWidth(option)+tableAlignment(option,true)+"\"><tbody>"+trimBreaks(body)+"</tbody></table>";
        if("tr".equals(name))return "<tr>"+trimBreaks(body)+"</tr>";
        if("td".equals(name))return "<td style=\"border: 1px solid currentColor; padding: 6px; "+tableAlignment(option,false)+"\">"+trimBreaks(body)+"</td>";
        if("highlight".equals(name))return "<span class=\"highlight\">"+body+"</span>";
        if("left".equals(name)||"center".equals(name)||"right".equals(name))return "<div style=\"text-align: "+name+";\">"+body+"</div>";
        if("color".equals(name))return safeColor(option)?"<font color=\""+attr(option)+"\">"+body+"</font>":body;
        if("size".equals(name))return body;
        if("font".equals(name))return option.matches("[a-zA-Z0-9 _,-]{1,100}")?"<span style=\"font-family: "+attr(option)+"\">"+body+"</span>":body;
        if("h".equals(name))return option.matches("[1-6]")?"<h"+option+">"+body+"</h"+option+">":body;
        if("indent".equals(name)){int x=number(option,1,1,20)*30;return "<div style=\"margin-inline-start: "+x+"px;\">"+body+"</div>";}
        if("quote".equals(name)){String who=esc(option.replaceFirst("[;,]\\s*\\d+\\s*$",""));return "<div class=\"bbcode_container\"><cite>"+(who.length()==0?"":who+" said:")+"</cite>"+trimBreaks(body)+"</div>";}
        if("url".equals(name))return link(option.length()==0?plain(n):option,body); if("email".equals(name)){String x=option.length()==0?plain(n):option;return x.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")?"<a href=\"mailto:"+attr(x)+"\">"+body+"</a>":body;}
        if("src_link".equals(name)){String url=option.length()==0?plain(n).trim():option;if(!safeUrl(url))return body;return "<div class=\"bbcode-source-link\" data-image-url=\""+attr(url)+"\"><a href=\""+attr(url)+"\">"+esc(url)+"</a></div>";}
        if("movepic".equals(name)){String url=option.length()==0?plain(n).trim():option;if(!safeUrl(url))return body;return "<div class=\"bbcode-movepic\" data-image-url=\""+attr(url)+"\" data-image-label=\""+attr(plain(n))+"\"><a href=\""+attr(url)+"\">"+esc(url)+"</a><br><img src=\""+attr(url)+"\" alt=\""+attr(plain(n))+"\" style=\"max-width:220px;max-height:160px;width:auto;height:auto;\"></div>";}
        if("img".equals(name)){String x=plain(n).trim();return link(x,esc(x));}
        if("thread".equals(name)){String id=option.length()==0?plain(n).trim():option;return id.matches("\\d+")?"<a href=\"showthread.php?t="+id+"\">"+body+"</a>":body;}
        if("post".equals(name)){String id=option.length()==0?plain(n).trim():option;return id.matches("\\d+")?"<a href=\"showthread.php?p="+id+"#post"+id+"\">"+body+"</a>":body;}
        if("list".equals(name))return list(n,option); if("code".equals(name)||"php".equals(name)||"html".equals(name))return "<pre class=\"bbcode_"+name+"\"><code>"+esc(rawParts(n.parts).replaceAll("^(?:\\r?\\n)|(?:\\r?\\n)$",""))+"</code></pre>";
        if("noparse".equals(name))return lines(rawParts(n.parts)); if("hr".equals(name))return "<hr />";
        if("video".equals(name))return link(plain(n).trim(),body);
        return body;
    }
    private static String tableOption(String option,String key){
        Matcher m=Pattern.compile("(?:^|,)\\s*"+key+"\\s*:\\s*([^,]+)",Pattern.CASE_INSENSITIVE).matcher(option);
        return m.find()?m.group(1).trim():"";
    }
    private static String tableWidth(String option){
        String width=tableOption(option,"width");
        if(width.matches("[0-9]{1,4}"))return "width: "+width+"px; ";
        if(width.matches("(?:[1-9][0-9]?|100)%"))return "width: "+width+"; ";
        return "";
    }
    private static String tableAlignment(String option,boolean table){
        String align=tableOption(option,"align").toLowerCase(Locale.ROOT);
        if(!align.matches("left|right|center"))return "";
        if(!table)return "text-align: "+align+"; ";
        if("center".equals(align))return "margin-left: auto; margin-right: auto; ";
        return "margin-"+("left".equals(align)?"right":"left")+": auto; ";
    }
    private static String list(Node n,String option){String raw=rawParts(n.parts);String[] a=raw.split("(?i)\\[\\*(?:=[^\\]]*)?\\]");String tag=option.length()>0?"ol":"ul";StringBuilder b=new StringBuilder("<").append(tag);if("ol".equals(tag)){String t="decimal";if("A".equals(option))t="upper-alpha";else if("a".equals(option))t="lower-alpha";else if("I".equals(option))t="upper-roman";else if("i".equals(option))t="lower-roman";b.append(" style=\"list-style-type: ").append(t).append(";\"");}b.append('>');boolean any=false;for(String x:a)if(x.trim().length()>0){b.append("<li>").append(toHtml(x.trim())).append("</li>");any=true;}if(!any&&raw.trim().length()>0)b.append("<li>").append(toHtml(raw.trim())).append("</li>");return b.append("</").append(tag).append('>').toString();}
    private static boolean localViewerUrl(String url) {
        return url.matches("(?i)(?:\\./)?(?:viewer|main)\\.html(?:[?#][^\\s<>\"']*)?");
    }
    private static String link(String url,String body) {
        url=url.trim();
        if(url.startsWith("www."))url="http://"+url;
        if(!safeUrl(url))return body;
        if(localViewerUrl(url))return "<a href=\""+attr(url)+"\">"+body+"</a>";
        return "<a href=\""+attr(url)+"\" target=\"_blank\" rel=\"nofollow noopener\">"+body+"</a>";
    }
    private static boolean safeUrl(String s) {
        return localViewerUrl(s) || s.matches("(?i)(?:https?|ftp)://[^\\s<>\"]+");
    }
    private static boolean safeColor(String s){return s.matches("(?i)#?[a-z0-9]{1,20}");}
    private static int number(String s,int d,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(s)));}catch(Exception e){return d;}}
    private static String plain(Node n){return rawParts(n.parts).replaceAll("<[^>]+>","");}private static String wrap(String t,String s){return"<"+t+">"+s+"</"+t+">";}
    private static String trimBreaks(String s){return s.replaceFirst("^(?:<br \\/>)+","").replaceFirst("(?:<br \\/>)+$","");}
    private static String lines(String s){return esc(s).replace("\r\n","\n").replace('\r','\n').replace("\n","<br />\n");}
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String attr(String s){return esc(s).replace("'","&#39;").replace("`","&#96;");}
    private static final class Node{final String name,option,open;final List<Object> parts=new ArrayList<Object>();Node(String n,String o,String p){name=n;option=o;open=p;}}
}
