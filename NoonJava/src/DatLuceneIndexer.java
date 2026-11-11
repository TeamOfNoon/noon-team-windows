import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.json.JSONObject;

/**
 * Console importer for the NPWX1 protected forum store produced by the C++ builder.
 * It reads content.dat + chunks/content_XXXXX.dat directly, decrypts/decompresses one
 * chunk at a time and creates the existing Noon Lucene 5.3 normal/ngram/arstem indexes.
 */
public final class DatLuceneIndexer {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static interface Progress {
        void onProgress(int processed, int total, int indexed, String message);
    }

    private DatLuceneIndexer() {}

    private static final class Entry {
        String path;
        int chunkId;
        long offset;
        long length;
    }

    private static final class ChunkDesc {
        String relativePath;
        long rawLength;
        long storedLength;
        byte[] props = new byte[5];
        byte[] nonce = new byte[12];
        byte[] tag = new byte[16];
    }

    static final class Writers {
        final String section;
        final String forumId;
        final Directory normalDir;
        final Directory ngramDir;
        final Directory arstemDir;
        final Analyzer normalAnalyzer;
        final Analyzer ngramAnalyzer;
        final Analyzer arstemAnalyzer;
        final IndexWriter normal;
        final IndexWriter ngram;
        final IndexWriter arstem;
        int count;
        final Map<String,String> oldHashes = new java.util.HashMap<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        boolean canSkip;
        static final String HASH_VERSION = "noon-arabic-english-stem-v8-20261017";


        Writers(String section, String forumId, File luceneRoot) throws IOException {
            Thread.currentThread().setContextClassLoader(DatLuceneIndexer.class.getClassLoader());
            this.section = section;
            this.forumId = forumId;
            File n = new File(luceneRoot, section + "_normal_index");
            File g = new File(luceneRoot, section + "_ngram_index");
            File a = new File(luceneRoot, section + "_arstem_index");

            n.getParentFile().mkdirs();

            normalDir = FSDirectory.open(Paths.get(n.getAbsolutePath()));
            ngramDir = FSDirectory.open(Paths.get(g.getAbsolutePath()));
            arstemDir = FSDirectory.open(Paths.get(a.getAbsolutePath()));
            Map<String,String> nh = hashes(normalDir), gh = hashes(ngramDir), ah = hashes(arstemDir);
            canSkip = nh.equals(gh) && nh.equals(ah);
            oldHashes.putAll(nh);
            for (String id : gh.keySet()) if (!oldHashes.containsKey(id)) oldHashes.put(id, "");
            for (String id : ah.keySet()) if (!oldHashes.containsKey(id)) oldHashes.put(id, "");
            normalAnalyzer = SearchQuerySupport.analyzer(false);
            ngramAnalyzer = createPositionalNgramAnalyzer();
            arstemAnalyzer = SearchQuerySupport.analyzer(true);
            normal = new IndexWriter(normalDir, new IndexWriterConfig(normalAnalyzer));
            ngram = new IndexWriter(ngramDir, new IndexWriterConfig(ngramAnalyzer));
            arstem = new IndexWriter(arstemDir, new IndexWriterConfig(arstemAnalyzer));
        }

        private static Map<String,String> hashes(Directory directory) throws IOException {
            Map<String,String> result = new java.util.HashMap<>();
            if (!org.apache.lucene.index.DirectoryReader.indexExists(directory)) return result;
            try (org.apache.lucene.index.DirectoryReader reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
                org.apache.lucene.util.Bits live = org.apache.lucene.index.MultiFields.getLiveDocs(reader);
                for (int i=0;i<reader.maxDoc();i++) {
                    if (live != null && !live.get(i)) continue;
                    Document doc = reader.document(i, new java.util.HashSet<String>(java.util.Arrays.asList("postid", "source_hash")));
                    String id = doc.get("postid"), hash = doc.get("source_hash");
                    if (id == null) throw new IOException("Index document has no post ID; rebuild this index");
                    if (result.containsKey(id)) result.put(id, "");
                    else result.put(id, hash == null ? "" : hash);
                }
            }
            return result;
        }
        static boolean sourceMatches(File root, String section, String stamp) throws IOException {
            for (String suffix : new String[]{"_normal_index", "_ngram_index", "_arstem_index"}) {
                File folder = new File(root, section + suffix);
                if (!folder.isDirectory()) return false;
                try (Directory dir = FSDirectory.open(Paths.get(folder.getAbsolutePath()))) {
                    if (!org.apache.lucene.index.DirectoryReader.indexExists(dir)) return false;
                    java.util.List<org.apache.lucene.index.IndexCommit> commits = org.apache.lucene.index.DirectoryReader.listCommits(dir);
                    if (commits.isEmpty() || !stamp.equals(commits.get(commits.size()-1).getUserData().get("source_database_hash"))) return false;
                }
            }
            return true;
        }
        void setSourceStamp(String stamp) {
            Map<String,String> metadata = java.util.Collections.singletonMap("source_database_hash", stamp);
            normal.setCommitData(metadata); ngram.setCommitData(metadata); arstem.setCommitData(metadata);
        }

        boolean unchanged(String id, String hash) {
            seen.add(id);
            return canSkip && hash.equals(oldHashes.get(id));
        }
        void removeDeleted() throws IOException {
            for (String id : oldHashes.keySet()) if (!seen.contains(id)) {
                org.apache.lucene.index.Term term = new org.apache.lucene.index.Term("postid", id);
                normal.deleteDocuments(term); ngram.deleteDocuments(term); arstem.deleteDocuments(term);
            }
        }

        void close() throws IOException {
            IOException first = null;
            try { normal.commit(); normal.close(); } catch (IOException e) { first = e; }
            try { ngram.commit(); ngram.close(); } catch (IOException e) { if (first == null) first = e; }
            try { arstem.commit(); arstem.close(); } catch (IOException e) { if (first == null) first = e; }
            try { normalDir.close(); } catch (IOException e) { if (first == null) first = e; }
            try { ngramDir.close(); } catch (IOException e) { if (first == null) first = e; }
            try { arstemDir.close(); } catch (IOException e) { if (first == null) first = e; }
            normalAnalyzer.close(); ngramAnalyzer.close(); arstemAnalyzer.close();
            if (first != null) throw first;
        }

        /** Abort a cancelled build without committing its partial documents. */
        void abort() {
            try { normal.rollback(); } catch (Throwable ignore) {}
            try { ngram.rollback(); } catch (Throwable ignore) {}
            try { arstem.rollback(); } catch (Throwable ignore) {}
            try { normalDir.close(); } catch (Throwable ignore) {}
            try { ngramDir.close(); } catch (Throwable ignore) {}
            try { arstemDir.close(); } catch (Throwable ignore) {}
            try { normalAnalyzer.close(); } catch (Throwable ignore) {}
            try { ngramAnalyzer.close(); } catch (Throwable ignore) {}
            try { arstemAnalyzer.close(); } catch (Throwable ignore) {}
        }
    }

    static void indexPost(Writers w, JSONObject obj, String postid, String threadid, String forumId, String forumType) throws Exception {
        String title = value(obj, "title", "post_title");
        String username = value(obj, "username", "user_name");
        String userid = value(obj, "userid", "user_id");
        String threadTitle = value(obj, "thread_title", "threadtitle");
        String body = value(obj, "long_text", "pagetext", "content", "body", "text");
        String forumTitle = value(obj, "forum_title", "forumid_title", "forum_name");
        if (forumTitle.length() == 0) forumTitle = w.section;

        title = SearchTextSupport.unescapeHtml(title);
        String displayTitle;
        if (title == null || title.trim().length() == 0 || "none".equalsIgnoreCase(title)) {
            displayTitle = username + ": * " + threadTitle + " *";
            title = "";
        } else {
            displayTitle = username + ": * " + title + " *";
        }

        // Search the SQLite title field and post content together.
        String processedText = normalizedIndexText(title,body);
        // Root analyzer applies Arabic and English stemming exactly once.
        String stripPre = processedText;

        int suffix = "vb".equals(forumType) ? 2 : 1;
        long sortValue = parseLong(postid, 0L) + parseLong(forumId + suffix, 0L);

        FieldType ft = new FieldType();
        ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        ft.setStoreTermVectors(true);
        ft.setStoreTermVectorPositions(true);
        ft.setTokenized(true);
        ft.setStored(false);
        ft.freeze();

        Document normal = baseDoc(sortValue, postid, threadid, forumId, forumTitle, userid, displayTitle);
        OriginalDateFields.add(normal,obj);
        normal.add(new org.apache.lucene.document.StoredField("post_title", title));
        normal.add(new org.apache.lucene.document.StoredField("source_hash", obj.getString("source_hash")));
        normal.add(new Field("word", processedText, ft));
        w.normal.updateDocument(new org.apache.lucene.index.Term("postid", postid), normal);

        Document ng = baseDoc(sortValue, postid, threadid, forumId, forumTitle, userid, displayTitle);
        OriginalDateFields.add(ng,obj);
        ng.add(new org.apache.lucene.document.StoredField("post_title", title));
        ng.add(new org.apache.lucene.document.StoredField("source_hash", obj.getString("source_hash")));
        ng.add(new Field("ngram_word", processedText, ft));
        w.ngram.updateDocument(new org.apache.lucene.index.Term("postid", postid), ng);

        Document ar = baseDoc(sortValue, postid, threadid, forumId, forumTitle, userid, displayTitle);
        OriginalDateFields.add(ar,obj);
        ar.add(new org.apache.lucene.document.StoredField("post_title", title));
        ar.add(new org.apache.lucene.document.StoredField("source_hash", obj.getString("source_hash")));
        ar.add(new Field("arstem_word", stripPre, ft));
        w.arstem.updateDocument(new org.apache.lucene.index.Term("postid", postid), ar);
    }

    /** One content preparation path for Lucene and offline JS/BIN exports. */
    static String normalizedIndexText(String title,String body) {
        title=title==null?"":title;
        if("none".equalsIgnoreCase(title.trim()))title="";
        return SearchTextSupport.strip(SearchTextSupport.decodeURL(title)+" "+
                SearchTextSupport.decodeURL(SearchTextSupport.modifyText(body)));
    }

    private static Document baseDoc(long sortValue, String postid, String threadid, String forumId,
                                    String forumTitle, String userid, String displayTitle) throws Exception {
        Document d = new Document();
        d.add(new NumericDocValuesField("postid_sort", sortValue));
        d.add(new StringField("postid", postid, Field.Store.YES));
        d.add(new StringField("threadid", threadid, Field.Store.YES));
        d.add(new StringField("forumid", forumId, Field.Store.YES));
        d.add(new org.apache.lucene.document.StoredField("forumid_title", SearchTextSupport.stripTags(SearchTextSupport.decodeURL(forumTitle))));
        d.add(new StringField("userid", userid, Field.Store.YES));
        d.add(new StringField("title", SearchTextSupport.decodeURL(displayTitle), Field.Store.YES));
        return d;
    }

    private static byte[] decryptChunk(int chunkId, ChunkDesc d, byte[] cipherText) throws Exception {
        ByteArrayOutputStream aadOut = new ByteArrayOutputStream();
        aadOut.write("NPWX1-CHUNK".getBytes(Charset.forName("US-ASCII")));
        putU32(aadOut, chunkId); putU64(aadOut, d.rawLength); putU64(aadOut, d.storedLength); aadOut.write(d.props);
        byte[] aad = aadOut.toByteArray();
        byte[] k = key();

        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"));
        byte[] zero = new byte[16];
        byte[] h = aes.doFinal(zero);
        byte[] j0 = new byte[16];
        System.arraycopy(d.nonce, 0, j0, 0, 12);
        j0[15] = 1;

        byte[] expectedTag = xor16(aes.doFinal(j0), ghash(h, aad, cipherText));
        if (!constantTimeEquals(expectedTag, d.tag)) throw new IOException("AES-GCM authentication failed for chunk " + chunkId);

        byte[] plain = new byte[cipherText.length];
        byte[] ctr = (byte[])j0.clone();
        int off = 0;
        while (off < cipherText.length) {
            inc32(ctr);
            byte[] stream = aes.doFinal(ctr);
            int n = Math.min(16, cipherText.length - off);
            for (int i = 0; i < n; i++) plain[off + i] = (byte)(cipherText[off + i] ^ stream[i]);
            off += n;
        }
        return plain;
    }

    private static byte[] ghash(byte[] h, byte[] aad, byte[] cipher) {
        byte[] y = new byte[16];
        ghashData(y, h, aad);
        ghashData(y, h, cipher);
        byte[] lengths = new byte[16];
        putBe64(lengths, 0, ((long)aad.length) * 8L);
        putBe64(lengths, 8, ((long)cipher.length) * 8L);
        xorInto(y, lengths);
        return gfMultiply(y, h);
    }

    private static void ghashData(byte[] y, byte[] h, byte[] data) {
        int off = 0;
        while (off < data.length) {
            byte[] block = new byte[16];
            int n = Math.min(16, data.length - off);
            System.arraycopy(data, off, block, 0, n);
            xorInto(y, block);
            byte[] z = gfMultiply(y, h);
            System.arraycopy(z, 0, y, 0, 16);
            off += n;
        }
    }

    private static byte[] gfMultiply(byte[] x, byte[] y) {
        byte[] z = new byte[16];
        byte[] v = (byte[])y.clone();
        for (int i = 0; i < 128; i++) {
            int bit = (x[i >>> 3] >>> (7 - (i & 7))) & 1;
            if (bit != 0) xorInto(z, v);
            boolean lsb = (v[15] & 1) != 0;
            int carry = 0;
            for (int j = 0; j < 16; j++) {
                int cur = v[j] & 0xFF;
                int nextCarry = cur & 1;
                v[j] = (byte)((cur >>> 1) | (carry << 7));
                carry = nextCarry;
            }
            if (lsb) v[0] ^= (byte)0xE1;
        }
        return z;
    }

    private static byte[] xor16(byte[] a, byte[] b) { byte[] r = new byte[16]; for (int i=0;i<16;i++) r[i]=(byte)(a[i]^b[i]); return r; }
    private static void xorInto(byte[] a, byte[] b) { for (int i=0;i<16;i++) a[i]^=b[i]; }
    private static boolean constantTimeEquals(byte[] a, byte[] b) { if (a.length != b.length) return false; int d=0; for(int i=0;i<a.length;i++) d|=(a[i]^b[i]); return d==0; }
    private static void inc32(byte[] c) { for (int i=15;i>=12;i--) { c[i]++; if (c[i] != 0) break; } }
    private static void putBe64(byte[] out, int off, long v) { for(int i=7;i>=0;i--){out[off+i]=(byte)(v&255);v>>>=8;} }

    private static byte[] key() {
        int[] a={0x9C,0xE0,0x48,0x7F,0x34,0x18,0xE0,0xE3,0x95,0x73,0xE6,0xB5,0x5B,0xA2,0x22,0x29};
        int[] b={0x03,0x60,0x83,0x7A,0x9E,0xA9,0x59,0x54,0xF7,0x09,0xF7,0x61,0x66,0xB6,0x4C,0x18};
        byte[] k=new byte[32]; for(int i=0;i<16;i++)k[i]=(byte)(a[i]^0xA7); for(int i=0;i<16;i++)k[i+16]=(byte)(b[i]^0x5C); return k;
    }

    private static JSONObject parseObject(String text) {
        try {
            int first=text.indexOf('{'), last=text.lastIndexOf('}');
            if(first<0||last<first)return null;
            return new JSONObject(text.substring(first,last+1));
        } catch(Exception e){ return null; }
    }
    private static boolean hasAny(JSONObject o,String[] keys){ for(int i=0;i<keys.length;i++) if(o.has(keys[i])) return true; return false; }
    private static String value(JSONObject o,String...keys){
        for(int i=0;i<keys.length;i++) try { if(o.has(keys[i]) && !o.isNull(keys[i])) { Object v=o.get(keys[i]); return v==null?"":String.valueOf(v); } } catch(Exception ignore){}
        return "";
    }
    private static String inferForumType(String path){ String p=path.toLowerCase(); return p.indexOf("vb")>=0?"vb":"mn"; }
    private static String inferForumId(String path){ java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:mn|vb)[_\\-/]?(\\d+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(path);return m.find()?m.group(1):""; }
    private static long parseLong(String s,long def){ try{return Long.parseLong(s.replaceAll("[^0-9-]",""));}catch(Exception e){return def;} }

    private static File safeResolve(File base,String rel)throws IOException{ String clean=rel.replace('\\','/'); if(clean.startsWith("/")||clean.indexOf(':')>=0||clean.indexOf("../")>=0)throw new IOException("Unsafe chunk path: "+rel); File f=new File(base,clean); String bp=base.getCanonicalPath()+File.separator, fp=f.getCanonicalPath(); if(!fp.startsWith(bp))throw new IOException("Unsafe chunk path: "+rel); return f; }
    private static byte[] readAll(File f)throws IOException{ FileInputStream in=new FileInputStream(f);try{ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.min(f.length(),16L*1024L*1024L));byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}finally{in.close();} }
    private static void deleteTree(File f)throws IOException{ if(!f.exists())return; if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(int i=0;i<a.length;i++)deleteTree(a[i]);}if(!f.delete())throw new IOException("Cannot delete old index: "+f); }
    private static void putU32(ByteArrayOutputStream o,long v){ for(int i=0;i<4;i++)o.write((int)(v>>>(8*i))&255); }
    private static void putU64(ByteArrayOutputStream o,long v){ for(int i=0;i<8;i++)o.write((int)(v>>>(8*i))&255); }

    private static String normalizeForPositionalNgram(String input){
        return SearchTextNormalizer.normalize(input,true).replace('ی','ى');
    }

    static Analyzer createPositionalNgramAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                org.apache.lucene.analysis.Tokenizer tokenizer = new org.apache.lucene.analysis.Tokenizer() {
                    final org.apache.lucene.analysis.tokenattributes.CharTermAttribute term = addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
                    final org.apache.lucene.analysis.tokenattributes.OffsetAttribute offset = addAttribute(org.apache.lucene.analysis.tokenattributes.OffsetAttribute.class);
                    final org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute position = addAttribute(org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute.class);
                    final org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute positionLength = addAttribute(org.apache.lucene.analysis.tokenattributes.PositionLengthAttribute.class);
                    String text = ""; int wordStart, wordEnd, gramStart, gramLength, previousPosition, spacePosition; boolean firstToken, spaceEmitted, boundaryEmitted; String boundaryTerm;
                    void findWord(int from) {
                        int i=from; spacePosition=-1; spaceEmitted=false; boundaryTerm=null; boundaryEmitted=false; char previousLast=0;
                        if(from>0 && Character.isLetterOrDigit(text.charAt(from-1))) previousLast=text.charAt(from-1);
                        while(i<text.length() && !Character.isLetterOrDigit(text.charAt(i))){ if(spacePosition<0 && Character.isWhitespace(text.charAt(i))) spacePosition=i; i++; }
                        wordStart=i; if(previousLast!=0 && wordStart<text.length()) boundaryTerm="E|"+previousLast+text.charAt(wordStart);
                        while(i<text.length() && Character.isLetterOrDigit(text.charAt(i))) i++; wordEnd=i; gramStart=wordStart; gramLength=1;
                    }
                    @Override public void reset() throws IOException { super.reset(); StringBuilder sb=new StringBuilder(); char[] buf=new char[4096]; int n; while((n=input.read(buf))!=-1) sb.append(buf,0,n); text=normalizeForPositionalNgram(sb.toString()); previousPosition=-1; firstToken=true; findWord(0); }
                    @Override public boolean incrementToken() {
                        while(wordStart<text.length()){
                            if(spacePosition>=0 && !spaceEmitted){ clearAttributes(); term.append("__WS__"); offset.setOffset(correctOffset(spacePosition),correctOffset(spacePosition+1)); position.setPositionIncrement(firstToken?spacePosition+1:spacePosition-previousPosition); positionLength.setPositionLength(1); previousPosition=spacePosition; firstToken=false; spaceEmitted=true; return true; }
                            if(boundaryTerm!=null && !boundaryEmitted){ clearAttributes(); term.append(boundaryTerm); int bp=wordStart-1; offset.setOffset(correctOffset(bp),correctOffset(wordStart)); position.setPositionIncrement(firstToken?bp+1:bp-previousPosition); positionLength.setPositionLength(1); previousPosition=bp; firstToken=false; boundaryEmitted=true; return true; }
                            if(gramStart>=wordEnd){ findWord(wordEnd); continue; }
                            int remaining=wordEnd-gramStart; if(gramLength>remaining){gramStart++;gramLength=1;continue;}
                            clearAttributes(); int start=gramStart,end=start+gramLength; term.append(text,start,end); offset.setOffset(correctOffset(start),correctOffset(end)); position.setPositionIncrement(firstToken?start+1:start-previousPosition); positionLength.setPositionLength(gramLength); previousPosition=start; firstToken=false; gramLength++; return true;
                        }
                        return false;
                    }
                    @Override public void end() throws IOException { super.end(); int finalOffset=correctOffset(text.length()); offset.setOffset(finalOffset,finalOffset); }
                };
                return new TokenStreamComponents(tokenizer);
            }
        };
    }

    private static final class Cursor {
        final byte[] b; int p;
        Cursor(byte[] b){this.b=b;}
        byte[] bytes(int n)throws IOException{if(n<0||p+n>b.length)throw new IOException("Truncated content.dat");byte[] r=Arrays.copyOfRange(b,p,p+n);p+=n;return r;}
        int u16()throws IOException{byte[] x=bytes(2);return(x[0]&255)|((x[1]&255)<<8);}
        int u32()throws IOException{byte[] x=bytes(4);long v=(x[0]&255L)|((x[1]&255L)<<8)|((x[2]&255L)<<16)|((x[3]&255L)<<24);if(v>Integer.MAX_VALUE)throw new IOException("Value too large");return(int)v;}
        long u64()throws IOException{byte[] x=bytes(8);long v=0;for(int i=7;i>=0;i--)v=(v<<8)|(x[i]&255L);return v;}
    }
}
