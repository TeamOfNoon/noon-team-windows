import java.io.*;
import org.jsoup.Jsoup;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
final class SearchTextSupport {
static String unescapeHtml(String s){return org.jsoup.parser.Parser.unescapeEntities(s,false);}
static String decodeURL(String s){return s==null?"":s;}
static String modifyText(String s){return s;}
static String stripTags(String s){return searchPlainText(s);}
    public static String arabicStem(String text) throws IOException {
        // Preserve the existing stemming passes without the default token-length cutoff.
        Analyzer analyzer = SearchQuerySupport.analyzer(true);

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
            if(node instanceof org.jsoup.nodes.TextNode){text.append(((org.jsoup.nodes.TextNode)node).getWholeText());return;}
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
            return SearchTextNormalizer.normalize(searchPlainText(html),isFullStrip);
        }
}
