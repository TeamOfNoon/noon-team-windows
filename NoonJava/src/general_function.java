import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



import org.apache.commons.lang.StringEscapeUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.CharArraySet;
import org.json.JSONArray;
import org.json.JSONException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;






public class general_function {
	static String joinJSONArray(JSONArray array) {
	    StringBuilder sb = new StringBuilder();
	    for (int i = 0; i < array.length(); i++) {
	        if (i > 0) {
	            sb.append(",");
	        }
	        sb.append(array.optString(i));
	    }
	    return sb.toString();
	}
	  public static boolean[] evalIndexStatus(String js) {
	        boolean idx1 = js.contains("index1_isindexed = true");
	        boolean idx2 = js.contains("index2_isindexed = true");
	        boolean idx3 = js.contains("index3_isindexed = true");
	        boolean idx4 = js.contains("index4_isindexed = true");

	        boolean done = idx1 || idx2 || idx3 || idx4; // is_index_done logic
	        boolean part = js.contains("is_index_part = true");

	        return new boolean[]{idx1, idx2, idx3, idx4, done, part};
	    }

	    public static boolean evaluateIsIndexDone(String jsContent) {
	        boolean index1 = extractBool(jsContent, "index1_isindexed");
	        boolean index2 = extractBool(jsContent, "index2_isindexed");
	        boolean index3 = extractBool(jsContent, "index3_isindexed");
	        boolean index4 = extractBool(jsContent, "index4_isindexed");

	        // Simulate JS logic
	        if (!index1 && !index2 && !index3 && !index4) {
	            return false; // is_index_done = false;
	        }
	        return true; // is_index_done remains true
	    }

	    private static boolean extractBool(String js, String varName) {
	        Pattern pattern = Pattern.compile("var\\s+" + Pattern.quote(varName) + "\\s*=\\s*(true|false)\\s*;", Pattern.CASE_INSENSITIVE);
	        Matcher matcher = pattern.matcher(js);
	        if (matcher.find()) {
	            return Boolean.parseBoolean(matcher.group(1));
	        }
	        return false; // Default to false if not found
	    }
	
    public static String arabicStem(String text) throws IOException {
        CharArraySet emptyStopWordsSet = new CharArraySet(0, true);

        // Create an ArabicAnalyzer with the empty stop word set
        Analyzer analyzer = new ArabicAnalyzer(emptyStopWordsSet);

        try {
            // First round of stemming
            String firstPassStemmed = applyStemming(text, analyzer);

            // Second round of stemming
            String secondPassStemmed = applyStemming(firstPassStemmed, analyzer);

            return secondPassStemmed;
        } finally {
            analyzer.close();
        }
    }

    private static String applyStemming(String text, Analyzer analyzer) throws IOException {
        StringReader reader = new StringReader(text);
        org.apache.lucene.analysis.TokenStream tokenStream = null;

        try {
            tokenStream = analyzer.tokenStream("arstem_word", reader);
            CharTermAttribute termAttr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();

            StringBuilder sb = new StringBuilder();
            while (tokenStream.incrementToken()) {
                if (sb.length() > 0) {
                    sb.append(" "); // Add a space between words
                }
                sb.append(termAttr.toString());
            }
            tokenStream.end();

            return sb.toString().trim();
        } finally {
            if (tokenStream != null) {
                tokenStream.close();
            }
            reader.close();
        }
    }




    public String convertTimestampToDate(String timestamp) {
        // Parse the timestamp to a long
        long ts = Long.parseLong(timestamp);

        // If the timestamp is in seconds, convert to milliseconds
        if (timestamp.length() == 10) {
            ts *= 1000;
        }

        // Create a SimpleDateFormat with the desired pattern
        SimpleDateFormat sdf = new SimpleDateFormat("MMMM d yyyy, h:mm:ss a");

        // Convert the timestamp to a Date object
        Date date = new Date(ts);

        // Format the date as a string and return
        return sdf.format(date);
    }

  

    static Map<String, String> extractDefaultValues(String jsContent) {
        Map<String, String> values = new HashMap<String, String>();

        // Updated regex pattern to handle multi-line declarations
        Pattern pattern = Pattern.compile("var\\s+(index\\d)_isindexed\\s*=\\s*(true|false)\\s*(;|\\n|\\r|\\r\\n)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(jsContent);

        while (matcher.find()) {
            String index = matcher.group(1);
            String value = matcher.group(2);
            values.put(index, value);
        }
        return values;
    }


    static String getOrDefault(Map<String, String> map, String key, String defaultValue) {
        String value = map.get(key);
        return (value != null) ? value : defaultValue;
    }






    public static void deleteIndexFiles(File indexDir) {
        if (indexDir.exists()) {
            File[] files = indexDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    if (!files[i].isDirectory()) {
                        files[i].delete();
                    }
                }
            }
        }
    }


    public static void deleteFilesAndFolders(File folder) {
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        // Recursively delete contents of the directory
                        deleteFilesAndFolders(file);
                    }
                    // Delete the file or empty directory
                    if (file.delete()) {
                        System.out.println("Deleted: " + file.getAbsolutePath());
                    } else {
                        System.out.println("Failed to delete: " + file.getAbsolutePath());
                    }
                }
            }
        } else {
            System.out.println("The specified folder does not exist or is not a directory.");
        }
    }



    public static boolean deleteFolder(File folder) {
        if (folder.isDirectory()) {
            // List all the contents in the folder
            File[] files = folder.listFiles();
            if (files != null && files.length > 0) {
                for (File file : files) {
                    // Recursively delete each file/folder
                    if (!deleteFolder(file)) {
                        return false;
                    }
                }
            }
        }
        // Delete the folder or file
        return folder.delete();
    }






    public static String decodeURL(String Content) throws UnsupportedEncodingException {
     
    	
    	    Content = StringEscapeUtils.unescapeHtml(Content);
    	    Content = unescapeHtml(Content);
    	  //  Content = URLDecoder.decode(Content, "UTF-8");
    	    
    	    return Content;
    	
	
    	
    }
    
    public static String unescapeHtml(String input) {
        if (input == null) {
            return null; // Handle null input
        }

        // Replace common HTML entities and their Unicode equivalents
        String result = input
                // HTML entities
                .replace("&quot;", "\"") // Double quotation mark
                .replace("&amp;", "&")   // Ampersand
                .replace("&lt;", "<")    // Less-than sign
                .replace("&gt;", ">")    // Greater-than sign
                .replace("&nbsp;", " ")  // Non-breaking space
                .replace("&#039;", "'")  // Apostrophe

                .replace("\u0026", "&")  // Unicode for &
                .replace("\u003C", "<")  // Unicode for <
                .replace("\u003E", ">")  // Unicode for >
                .replace("\u00A0", " ")  // Unicode for non-breaking space
                //.replace("\u0022", "\"")  // Unicode for " (double quotation mark)
                .replace("\u0027", "'"); // Unicode for '

        return result;
    }
   

    
    

    public static String modifyText(String text) throws UnsupportedEncodingException {
        // Parse the HTML
        Document doc = Jsoup.parse(text);

        // Set the output settings to prevent unescaping HTML entities
        //doc.outputSettings().escapeMode(Entities.EscapeMode.base);

        // Perform your modifications using Jsoup
        String modifiedText = text;
        modifiedText = removeFontStyle(modifiedText, doc);
        modifiedText = convertIframesToLinks(modifiedText, doc);
        modifiedText =  removeQuoteByCaptions(doc);
        modifiedText = removeAttachmentsAndComments(doc);
        modifiedText =  modifyLinks(doc);
        modifiedText = modifyimgHtml(doc);










        modifiedText = general_function.decodeURL(modifiedText);




        modifiedText = srcLink(modifiedText);
        modifiedText = replaceMovepicTags(modifiedText);


        modifiedText = replaceDomains(modifiedText);

        return modifiedText;
    }


    public static String stripTags(String input, String... allowedTags) {
        if (input == null) return "";

        // Build regex pattern for allowed tags
        StringBuilder allowed = new StringBuilder();
        for (String tag : allowedTags) {
            if (tag != null && !tag.isEmpty()) {
                allowed.append("|/?").append(tag);
            }
        }

        String allowedPattern = allowed.length() > 0 ? "(?i)</?(?!" + allowed.substring(1) + ")\\b[^>]*>" : "(?i)<[^>]+>";
        
        // Remove all tags except those allowed
        return input.replaceAll(allowedPattern, "");
    }


    public static String convertToRiyadhTime(String timestampStr) {
        try {
            long timestamp = Long.parseLong(timestampStr) * 1000L; // seconds to milliseconds
            Date date = new Date(timestamp);

            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy - hh:mm a");
            sdf.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));

            return sdf.format(date);
        } catch (NumberFormatException e) {
            return "Invalid timestamp";
        }
    }




    public static String replaceMovepicTags(String output) {
        // Define the regular expression pattern
        String regex = "\\[movepic=\"(.*?)\"\\](.*?)\\[\\/movepic\\]";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(output);

        // Create a StringBuffer to hold the result
        StringBuffer result = new StringBuffer();

        // Replace the matches
        while (matcher.find()) {
            String p1 = matcher.group(1);
            String p2 = matcher.group(2);

            p1 = p1.replaceAll("http://localhost/app/","../../");
            String replacement = "<div class=\"alpom2 link_off\"><font size=\"5\"><span><span><a href=\"" + p1 + "\" title=\"uni2\" class=\"img_alpom\"><img class=\"lozad\" data-src=\"" + p1 + "\" alt=\"\" style=\"width:100%; max-width:200px\" id=\"id_2\" src=\"\"></a><br></span> <span class=\"selected enableselect\">" + p1 + "</span><br><span class=\"linkstyle\"><a class=\"alpom2_go\" href=\"" + p1 + "\">الذهاب الى الرابط</a></span><br><span class=\"linkstyle alpom2_copy\">نسخ الرابط</span><br><span class=\"selected2 bbcode_img2\">[movepic=\"" + p1 + "\"]" + p2 + "[/movepic]</span></span></font><hr></div>";
            matcher.appendReplacement(result, replacement);
        }

        // Append the remainder of the string
        matcher.appendTail(result);

        return result.toString();
    }


    public static String removeFontStyle(String html, Document doc) {
        // Parse the HTML
        // Document doc = Jsoup.parse(html);

        // Select elements with style attribute containing 'font-family'
        Elements elements = doc.select("span[style*=font-family]");

        // Remove the 'style' attribute from the selected elements
        for (Element element : elements) {
            element.removeAttr("style");
        }

        // Return the modified HTML as a string
        return doc.html();
    }



    public static String convertIframesToLinks(String html,Document doc) {
        // Parse the HTML
        // Document doc = Jsoup.parse(html);

        // Select all iframe elements
        Elements iframes = doc.select("iframe");

        // Loop through each iframe and replace it with an <a> tag
        for (Element iframe : iframes) {
            String src = iframe.attr("src");

            // Create a new <a> element with the src as both href and text
            Element link = doc.createElement("a");
            link.attr("href", src);
            link.text("Watch Video<br><br>");

            // Replace the iframe with the newly created <a> element
            iframe.replaceWith(link);
        }

        // Return the modified HTML as a string
        return doc.html();
    }





    public static String modifyimgHtml(Document doc) {




        Elements imgElements = doc.select("img");
        for (Element img : imgElements) {
            String src = img.attr("src");
            // Check if the src attribute starts with "images/smilies/"
            if (src.startsWith("images/")) {
                // Modify the src attribute
                img.attr("src", "../../clientscript/" + src);
            }
            
           
        }


        // Perform additional replacements
        String output = doc.html();



        // Pattern 1: Replace <a> with embedded <img> with a specific HTML structure
        output = output.replaceAll(
                "<a href=\"([^\"]+)\"[^>]*><img src=\"([^\"]+)\"[^>]*></a>",
                "<div class=\"move_pic\" align=\"center\">" +
                        "<font size=\"5\">" +
                        "<span class=\"link_pic\">" +
                        "<span class=\"NOTselected\">" +
                        "<a class=\"link_pic2\" style=\"\" href=\"$2\" title=\"\">۞رابط صورة۞</a>" +
                        "</span>" +
                        "<br>" +
                        "<span class=\"selected\">$2</span>" +
                        "</span>" +
                        "<br>" +
                        "</font>" +
                        "</div>"
        );

        // Pattern 2: Replace <img> tags with a specific HTML structure
        output = output.replaceAll(
                "<img\\s+src=\"((?:(?!\\.\\./\\.\\./clientscript/).)+?)\"\\s*[^>]*?>",
                "<div class=\"move_pic\" align=\"center\">" +
                        "<font size=\"5\">" +
                        "<span class=\"link_pic\">" +
                        "<span class=\"NOTselected\">" +
                        "<a class=\"link_pic2\" style=\"\" href=\"$1\" title=\"\">۞رابط صورة۞</a>" +
                        "</span>" +
                        "<br>" +
                        "<span class=\"selected\">$1</span>" +
                        "</span>" +
                        "<br>" +
                        "</font>" +
                        "</div>"
        );

        return output;
    }




    public static String srcLink(String output) {
        // General pattern to match [src_link="<URL>"][/src_link]
        Pattern pattern = Pattern.compile("\\[src_link=\"(https?://[^\"]*?showthread\\.php\\?p=(\\d+))\"\\]\\[/src_link\\]", Pattern.MULTILINE);

        Matcher matcher = pattern.matcher(output);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String fullUrl = matcher.group(1);
            String p1 = matcher.group(2);

            String replacement = "    <div class=\"move\" style=\"text-align:center;\">\n" +
                    "        <font class=\"\" size=\"5\">\n" +
                    "            <span class=\"link_org\">\n" +
                    "                <a class=\"link_org2\" id=\"p=" + p1 + "\" href=\"" + fullUrl + "\" target=\"_blank\">\n" +
                    "                    <span class=\"post_src NOTselected\"></span>\n" +
                    "                </a>\n" +
                    "                <br>\n" +
                    "                <span class=\"selected\"></span>\n" +
                    "            </span>\n" +
                    "        </font>\n" +
                    "    </div>";

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }


    public static List<String[]> parseLangs(String jsonString) throws JSONException {
        List<String[]> langs = new ArrayList<String[]>();
        JSONArray jsonArray = new JSONArray(jsonString);

        for (int i = 0; i < jsonArray.length(); i++) {
            JSONArray lang_post = jsonArray.getJSONArray(i);
            String lang = lang_post.getString(0);
            String postid = lang_post.getString(1);
            langs.add(new String[] { lang, postid });
        }

        return langs;
    }






    public static String removeQuoteByCaptions(Document doc) {
        // Select all <figcaption> elements with the class "quote-by"
        Elements elements = doc.select("figcaption.quote-by");

        // Remove each selected element from the document
        for (Element element : elements) {
            element.remove();
        }

        return doc.html();
    }



    public static String removeAttachmentsAndComments(Document doc) {
        // Remove <div class="attachments">
        Elements attachments = doc.select("div.attachments");
        for (Element attachment : attachments) {
            attachment.remove();
        }

        return doc.html();
    }


    public static String replaceDomains(String input) {
        // Replace all matching domains with '*www*.mahdialumma*.com'
        return input.replaceAll("www\\.nasser-alyamani\\.org|nasser-alyamani\\.org|www\\.mahdialumma\\.com|www\\.mahdialumma\\.org|mahdialumma\\.com",
                "\\*www\\*\\.mahdialumma\\*\\.com");
    }






    public static String modifyLinks(Document doc) {
        // Parse the input HTML string into a Jsoup Document


        // Replace <a class="showpost_postid" href="showthread.php?p=...">text</a>
        Elements postLinks = doc.select("a.showpost_postid");
        for (Element link : postLinks) {
            String href = link.attr("href").replace("showthread.php?p=", "https://www.mahdialumma.com/showthread.php?p=");
            String text = link.text();
            Element newLink = new Element(org.jsoup.parser.Tag.valueOf("a"), "").attr("href", href).text(text);
            newLink.addClass("showpost_postid");
            link.replaceWith(newLink);
        }

        // Replace <a class="showpost_threadurl" href="showthread.php?t=123">text</a>
        Elements threadLinks = doc.select("a.showpost_threadurl");
        for (Element link : threadLinks) {
            String href = link.attr("href").replace("showthread.php?t=", "https://www.mahdialumma.com/showthread.php?t=");
            String text = link.text();
            Element newLink = new Element(org.jsoup.parser.Tag.valueOf("a"), "").attr("href", href).text(text);
            newLink.addClass("showpost_threadurl");
            link.replaceWith(newLink);
        }

        // Replace <a href="report.php?p=123">text</a>
        Elements reportLinks = doc.select("a[href^=report.php?p=]");
        for (Element link : reportLinks) {
            String text = link.text();
            Element newLink = new Element(org.jsoup.parser.Tag.valueOf("a"), "").attr("href", "").text(text);
            link.replaceWith(newLink);
        }

        // Replace <a href="showthread.php?s=123&p=123#post123">text</a>
        Elements threadLinksWithSession = doc.select("a[href^=showthread.php?s=]");
        for (Element link : threadLinksWithSession) {
            String href = link.attr("href").replaceAll("showthread\\.php\\?s=.*&p=(\\d+)#post\\d+", "https://www.mahdialumma.com/showthread.php?p=$1");
            String text = link.text();
            Element newLink = new Element(org.jsoup.parser.Tag.valueOf("a"), "").attr("href", href).text(text);
            link.replaceWith(newLink);
        }

        // Replace <a href="showthread.php...">text</a>
        Elements generalThreadLinks = doc.select("a[href^=showthread.php]");
        for (Element link : generalThreadLinks) {
            String href = link.attr("href").replace("showthread.php", "https://www.mahdialumma.com/showthread.php");
            String text = link.text();
            Element newLink = new Element(org.jsoup.parser.Tag.valueOf("a"), "").attr("href", href).text(text);
            link.replaceWith(newLink);
        }


        // Extract the <a> tag
        // Convert all <a> tags to a single line
        Elements anchorTags = doc.select("a");
        for (Element anchorTag : anchorTags) {
            String singleLineAnchorTag = anchorTag.attr("title", "").outerHtml().replace("\n", "").replace("\r", "");
            //System.out.println(singleLineAnchorTag);
        }




        // Return the modified HTML as a String
        return doc.html();
    }








        public static String strip(String html) {
            return stripHtml(html, true);
        }

        public static String strip_pre(String html) {
            return stripHtml(html, false);
        }

        private static boolean isCombiningMark(char c) {
            return Character.getType(c) == Character.NON_SPACING_MARK;
        }

        private static boolean isWord(char c) {
            // These match the C++ IsWord(), although Normalize removes them earlier.
            if (c == '\u200C' || c == '\u200F') {
                return true;
            }

            return Character.isLetterOrDigit(c);
        }

        private static char normalizeChar(char c, boolean isFullStrip) {
            // Arabic-Indic digits ٠-٩.
            if (c >= '\u0660' && c <= '\u0669') {
                return (char) ('0' + (c - '\u0660'));
            }

            // Persian digit ۲.
            if (c == '\u06F2') {
                return '2';
            }

            // C++ converts ASCII uppercase only.
            if (c >= 'A' && c <= 'Z') {
                return (char) (c + 32);
            }

            if (isFullStrip) {
                switch (c) {
                    case '\u0629': // ة
                        return '\u0647'; // ه

                    case '\u0672': // ٲ
                    case '\u0622': // آ
                    case '\u0625': // إ
                    case '\u0673': // ٳ
                    case '\u0623': // أ
                    case '\u0671': // ٱ
                        return '\u0627'; // ا

                    case '\u0626': // ئ
                        return '\u0649'; // ى

                    case '\u0624': // ؤ
                        return '\u0648'; // و

                    default:
                        break;
                }
            }

            return c;
        }

        private static boolean isRemoved(char c) {
            return isCombiningMark(c)
                    || c == '\u200B' // Zero-width space
                    || c == '\u06E5'
                    || c == '\u08F0'
                    || c == '\u0640'; // Tatweel ـ
        }

        /** Preserve words crossing inline tags; only block markup separates text. */
        static String searchPlainText(String html) {
            if(html==null||html.length()==0)return "";
            StringBuilder text=new StringBuilder();
            appendSearchText(Jsoup.parse(html).body(),text);
            return text.toString();
        }
        private static void appendSearchText(org.jsoup.nodes.Node node,StringBuilder text) {
            if(node instanceof org.jsoup.nodes.TextNode){String visible=((org.jsoup.nodes.TextNode)node).getWholeText();for(int i=0;i<4;i++){String decoded=org.jsoup.parser.Parser.unescapeEntities(visible,false);if(decoded.equals(visible))break;visible=decoded;}text.append(visible);return;}
            if(!(node instanceof org.jsoup.nodes.Element))return;
            org.jsoup.nodes.Element element=(org.jsoup.nodes.Element)node;
            String tag=element.normalName();
            if("script".equals(tag)||"style".equals(tag)||"template".equals(tag))return;
            boolean block=element.isBlock()||"br".equals(tag)||"hr".equals(tag);
            if(block)searchTextBoundary(text);
            for(org.jsoup.nodes.Node child:node.childNodes())appendSearchText(child,text);
            if(block)searchTextBoundary(text);
        }
        private static void searchTextBoundary(StringBuilder text){if(text.length()>0&&!Character.isWhitespace(text.charAt(text.length()-1)))text.append(' ');}

        private static String stripHtml(String html, boolean isFullStrip) {
            if (html == null || html.length() == 0) {
                return "";
            }

            // Equivalent to the C++ StripHtml() result.
            String text = searchPlainText(html);
            // Legacy search folds Arabic display glyphs and lam-alef ligatures.
            // Do this before removing marks/folding alef so both stored posts
            // and incoming queries produce the same ordinary Arabic tokens.
            StringBuilder arabicText = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char glyph = text.charAt(i);
                if ((glyph >= '\uFB50' && glyph <= '\uFDFF')
                        || (glyph >= '\uFE70' && glyph <= '\uFEFF')) {
                    arabicText.append(java.text.Normalizer.normalize(
                            String.valueOf(glyph), java.text.Normalizer.Form.NFKC));
                } else {
                    arabicText.append(glyph);
                }
            }
            text = arabicText.toString();

            StringBuilder output = new StringBuilder(text.length());

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);

                // Remove double and single quotes.
                if (c == '"' || c == '\'') {
                    continue;
                }

                /*
                 * Replace these sequences before removing combining marks:
                 *
                 * ٮٰ -> ا
                 * ـٰ -> ا
                 */
                if (i + 1 < text.length()
                        && (c == '\u066E' || c == '\u0640')
                        && text.charAt(i + 1) == '\u0670') {

                    output.append('\u0627');
                    i++;
                    continue;
                }

                // Jsoup decodes the HTML entities into these characters.
                if (c == '\u200F'     // RLM
                        || c == '\u200E' // LRM
                        || c == '\u200C') { // ZWNJ
                    continue;
                }

                if (isRemoved(c)) {
                    continue;
                }

                c = normalizeChar(c, isFullStrip);

                if (isWord(c)) {
                    output.append(c);
                } else {
                    output.append(' ');
                }
            }

            // Match the C++ whitespace compaction.
            StringBuilder compact = new StringBuilder(output.length());
            boolean previousWasSpace = true;

            for (int i = 0; i < output.length(); i++) {
                char c = output.charAt(i);
                boolean isSpace = Character.isWhitespace(c);

                if (isSpace) {
                    if (!previousWasSpace) {
                        compact.append(' ');
                        previousWasSpace = true;
                    }
                } else {
                    compact.append(c);
                    previousWasSpace = false;
                }
            }

            if (compact.length() > 0
                    && compact.charAt(compact.length() - 1) == ' ') {
                compact.deleteCharAt(compact.length() - 1);
            }

            return compact.toString();
        }


}
