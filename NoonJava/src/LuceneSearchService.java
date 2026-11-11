import java.io.*;
import java.nio.file.Paths;
import java.util.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.BitDocIdSet;
import org.json.*;

/** Search service for the desktop Lucene indexes. JS search remains available separately. */
public final class LuceneSearchService {
    private final File luceneRoot;
    private final File ntRoot;
    public LuceneSearchService(File root) throws Exception { this(root,null); }
    public LuceneSearchService(File root,File ntRoot) throws Exception { this.luceneRoot=root.getCanonicalFile();this.ntRoot=ntRoot; }

    private final java.util.concurrent.ConcurrentMap<String,Thread> activeSearches=new java.util.concurrent.ConcurrentHashMap<String,Thread>();
    private final Set<String> cancelledSearches=new LinkedHashSet<String>();
    public synchronized void cancelSearch(String token){if(token==null||token.length()==0)return;cancelledSearches.add(token);while(cancelledSearches.size()>512){Iterator<String> i=cancelledSearches.iterator();i.next();i.remove();}Thread thread=activeSearches.get(token);if(thread!=null)thread.interrupt();}
    public String searchCancellable(String token,String raw,String kind,String mode,String sort,int page,int size,String forms)throws Exception{
        if(token==null||token.length()==0)return search(raw,kind,mode,sort,page,size,forms);
        Thread current=Thread.currentThread();boolean interrupted=current.isInterrupted();
        activeSearches.put(token,current);
        try{synchronized(this){if(cancelledSearches.contains(token))throw new IOException("Search stopped");}checkSearchCancelled();return search(raw,kind,mode,sort,page,size,forms);}
        catch(Exception e){if(current.isInterrupted()||e instanceof SearchStopped||"Search stopped".equals(e.getMessage()))return "{\"ok\":false,\"cancelled\":true,\"error\":\"Search stopped\"}";throw e;}
        finally{activeSearches.remove(token,current);synchronized(this){cancelledSearches.remove(token);}if(!interrupted)Thread.interrupted();}
    }
    private static void checkSearchCancelled(){if(Thread.currentThread().isInterrupted())throw new SearchStopped();}
    private static final class SearchStopped extends RuntimeException{SearchStopped(){super("Search stopped");}}
    private static TopDocs searchHits(IndexSearcher searcher,Query query,int limit)throws IOException{
        final TopScoreDocCollector top=TopScoreDocCollector.create(limit);
        searcher.search(query,new FilterCollector(top){public LeafCollector getLeafCollector(LeafReaderContext context)throws IOException{checkSearchCancelled();return new FilterLeafCollector(super.getLeafCollector(context)){public void collect(int doc)throws IOException{checkSearchCancelled();super.collect(doc);}};}});
        return top.topDocs();
    }

    public String search(String rawQuery,String kind,String mode,String sort,int page,int pageSize,String forms) throws Exception {
        Thread thread=Thread.currentThread(); ClassLoader previous=thread.getContextClassLoader();
        thread.setContextClassLoader(LuceneSearchService.class.getClassLoader());
        try { return searchWithAppLoader(rawQuery,kind,mode,sort,page,pageSize,forms); }
        finally { thread.setContextClassLoader(previous); }
    }
    private String searchWithAppLoader(String rawQuery,String kind,String mode,String sort,int page,int pageSize,String forms) throws Exception {
        rawQuery=rawQuery==null?"":rawQuery;
        kind=kind==null?"full_index":kind.trim().toLowerCase(Locale.ROOT);
        mode=mode==null?"phrase":mode.trim().toLowerCase(Locale.ROOT);
        sort=sort==null?"occurrences":sort.trim().toLowerCase(Locale.ROOT);
        final boolean chronological=sort.equals("date_newest")||sort.equals("date_oldest");
        final boolean byOccurrences="occurrences".equals(sort);
        // The web wildcard cache requests all matches using size=-1.
        // Positive sizes also include multi-page batches; do not truncate them to 100.
        final boolean allResults=pageSize==-1;
        page=allResults?1:Math.max(1,page); pageSize=allResults?1:Math.max(1,pageSize);
        List<FormRef> selected=parseForms(forms);
        if(selected.isEmpty())selected=discoverAllForms(kind);

        String normalized=normalizeQuery(rawQuery,kind);
        JSONArray hitsJson=new JSONArray();
        JSONObject out=new JSONObject();
        out.put("ok",true); out.put("engine","lucene"); out.put("normalized_query",normalized);
        if("root_index".equals(kind)){
            // Expose the Lucene stems; browser highlighting applies the same rules.
            try(Analyzer rootAnalyzer=analyzerFor(kind)){
                List<String> stemmed=analyzeTerms(rootAnalyzer,fieldFor(kind),normalized);
                StringBuilder text=new StringBuilder();
                for(String term:stemmed){if(text.length()>0)text.append(' ');text.append(term);}
                out.put("stemmed_query",text.toString());
            }
        }
        out.put("page",page); out.put("pageSize",pageSize);
        if(normalized.length()==0){out.put("total",0);out.put("totalPages",1);out.put("hits",hitsJson);return out.toString();}

        int want=allResults?Integer.MAX_VALUE:(int)Math.min(Integer.MAX_VALUE,(long)page*pageSize);
        long total=0;
        List<Hit> merged=new ArrayList<Hit>();
        for(FormRef f:selected){checkSearchCancelled();
            File dir=indexDirFor(f,kind); if(!LuceneIndexSource.exists(dir)) continue;
            Map<String,JSONObject> sourceDates=Collections.emptyMap();
            if(chronological&&ntRoot!=null){PlainSqliteCatalog.FormDb source=new PlainSqliteCatalog(ntRoot).form(f.family+f.id);if(source!=null)sourceDates=SqliteLuceneIndexer.originalDates(source);}
            Directory fs=LuceneIndexSource.open(dir); DirectoryReader reader=null; Analyzer analyzer=null;
            try{
                reader=DirectoryReader.open(fs);
                IndexSearcher s=new IndexSearcher(reader);
                String field=fieldFor(kind);
                analyzer=analyzerFor(kind);
                List<String> terms;
                Query q;
                if ("partial_index".equals(kind)) {
                    q=buildPositionalNgramQuery(normalized,mode);
                } else {
                    terms=analyzeTerms(analyzer,field,normalized);
                    if(terms.isEmpty()) continue;
                    q="wildcard_index".equals(kind)?buildWildcardQuery(reader,field,terms,mode):buildQuery(field,terms,mode,kind);
                }
                Map<Integer,Integer> frequencies=byOccurrences?occurrenceCounts(s,q):Collections.<Integer,Integer>emptyMap();
                int collectWant=allResults||byOccurrences||sort.startsWith("date_")?Math.max(1,reader.maxDoc()):want;
                List<TopDocs> tiers=new ArrayList<TopDocs>();
                boolean splitWhole="partial_index".equals(kind)&&!chronological;
                if(splitWhole){
                    Set<String> exactIds=wholeWordPostIds(f,rawQuery,mode);
                    Filter exactFilter=postIdFilter(exactIds);
                    Query exactOnly=new FilteredQuery(q,exactFilter);
                    BooleanQuery.Builder remainder=new BooleanQuery.Builder();
                    remainder.add(q,BooleanClause.Occur.MUST);
                    remainder.add(new ConstantScoreQuery(exactFilter),BooleanClause.Occur.MUST_NOT);
                    tiers.add(searchHits(s,exactOnly,collectWant));
                    tiers.add(searchHits(s,remainder.build(),collectWant));
                }else tiers.add(searchHits(s,q,collectWant));
                for(int tier=0;tier<tiers.size();tier++){
                TopDocs td=tiers.get(tier);
                total+=td.totalHits;
                for(ScoreDoc sd:td.scoreDocs){
                    Document d=s.doc(sd.doc);
                    Hit h=new Hit(); h.docId=sd.doc; h.wholeWord=splitWhole&&tier==0; h.occurrences=frequencies.containsKey(sd.doc)?frequencies.get(sd.doc):0; h.score=sd.score; h.sortKey=sortKey(sd); h.postid=d.get("postid"); h.threadid=d.get("threadid");
                    h.forumid=d.get("forumid"); if(h.forumid==null||h.forumid.length()==0)h.forumid=f.id;
                    h.forumType=d.get("forum_type"); if(h.forumType==null||h.forumType.length()==0)h.forumType=f.family;
                    h.formid=f.id; h.formFamily=f.family;
                    h.forumTitle=d.get("forumid_title"); h.title=d.get("title"); h.postTitle=d.get("post_title"); h.date=d.get("date_original");h.hijriDate=d.get("hijri_date");h.miladiDate=d.get("miladi_date");
                    h.sortKey=OriginalDateFields.key(d);
                    if(chronological&&h.sortKey==0&&sourceDates.containsKey(h.postid)){
                        Document dates=new Document();OriginalDateFields.add(dates,sourceDates.get(h.postid));
                        h.sortKey=OriginalDateFields.key(dates);h.date=dates.get("date_original");h.hijriDate=dates.get("hijri_date");h.miladiDate=dates.get("miladi_date");
                    }
                    h.hijriUnix=OriginalDateFields.unix(d.get("hijri_unix"));h.miladiUnix=OriginalDateFields.unix(d.get("miladi_unix"));
                    merged.add(h);
                }
                }
            }finally{try{if(analyzer!=null)analyzer.close();}catch(Exception ignore){} try{if(reader!=null)reader.close();}catch(Exception ignore){} try{fs.close();}catch(Exception ignore){}}
        }
        final boolean dateNew="date_newest".equals(sort),dateOld="date_oldest".equals(sort);
        final boolean chronologicalPartial="partial_index".equals(kind)&&(dateNew||dateOld);
        Collections.sort(merged,new Comparator<Hit>(){public int compare(Hit a,Hit b){
            if(!chronologicalPartial&&a.wholeWord!=b.wholeWord)return a.wholeWord?-1:1;
            if(byOccurrences){int n=Integer.compare(b.occurrences,a.occurrences);if(n!=0)return n;}
            if(dateNew||dateOld){if((a.sortKey==0)!=(b.sortKey==0))return a.sortKey==0?1:-1;int c=Long.compare(a.sortKey,b.sortKey);if(dateNew)c=-c; if(c!=0)return c;}
            int c=Float.compare(b.score,a.score); if(c!=0)return c;
            // Relevance collection breaks ties by Lucene document ID. Keep that
            // order when merging so later requests preserve earlier page prefixes.
            if(!byOccurrences&&!chronological){
                c=a.formFamily.compareTo(b.formFamily);if(c!=0)return c;
                c=a.formid.compareTo(b.formid);if(c!=0)return c;
                return Integer.compare(a.docId,b.docId);
            }
            return compareNumericDesc(a.postid,b.postid);
        }});
        int from=allResults?0:(int)Math.min(merged.size(),(long)(page-1)*pageSize);
        int to=allResults?merged.size():(int)Math.min(merged.size(),(long)from+pageSize);
        for(int i=from;i<to;i++){
            Hit h=merged.get(i); JSONObject j=new JSONObject(); String docKey="lucene:"+h.forumType+h.forumid+":"+(h.postid==null?String.valueOf(i):h.postid); j.put("doc",docKey);j.put("postid",h.postid==null?"":h.postid);j.put("threadid",h.threadid==null?"":h.threadid);
            j.put("formid",h.formid);j.put("form_family",h.formFamily);j.put("forumid",h.forumid==null?"":h.forumid);j.put("forum_type",h.forumType==null?"mn":h.forumType);j.put("title",h.title==null?"":h.title);j.put("forum_title",h.forumTitle==null?"":h.forumTitle);j.put("post_title",h.postTitle==null?"":h.postTitle);j.put("date",OriginalDateFields.display(h.hijriDate,h.miladiDate,h.date));j.put("hijri_date",h.hijriDate==null?"":h.hijriDate);j.put("miladi_date",h.miladiDate==null?"":h.miladiDate);j.put("date_original_unix",h.sortKey);j.put("hijri_unix",h.hijriUnix);j.put("miladi_unix",h.miladiUnix);j.put("no_year",h.sortKey==0);j.put("score",h.score);j.put("occurrences",h.occurrences);j.put("whole_word_match",h.wholeWord); hitsJson.put(j);
        }
        int pages=allResults?1:Math.max(1,(int)Math.ceil(total/(double)pageSize));
        if(allResults)out.put("pageSize",Math.max(1,merged.size()));
        out.put("total",total); out.put("totalPages",pages); out.put("hits",hitsJson); return out.toString();
    }

    /** Count actual indexed positions rather than BM25 scores. Boolean modes
        sum the frequencies of distinct requested terms; phrase modes count phrases. */
    private static Map<Integer,Integer> occurrenceCounts(IndexSearcher searcher,Query query)throws IOException{
        Map<Integer,Integer> counts=new HashMap<Integer,Integer>();
        if(query instanceof BooleanQuery){Set<Query> unique=new HashSet<Query>();for(BooleanClause clause:(BooleanQuery)query){if(clause.getOccur()==BooleanClause.Occur.MUST_NOT)continue;if(unique.add(clause.getQuery()))for(Map.Entry<Integer,Integer> e:occurrenceCounts(searcher,clause.getQuery()).entrySet())counts.put(e.getKey(),(counts.containsKey(e.getKey())?counts.get(e.getKey()):0)+e.getValue());}return counts;}
        Weight weight=searcher.createNormalizedWeight(query,true);
        for(LeafReaderContext leaf:searcher.getIndexReader().leaves()){
            Scorer scorer=weight.scorer(leaf);if(scorer==null)continue;
            TwoPhaseIterator phase=scorer.asTwoPhaseIterator();DocIdSetIterator iterator=phase==null?scorer:TwoPhaseIterator.asDocIdSetIterator(phase);
            for(int doc=iterator.nextDoc();doc!=DocIdSetIterator.NO_MORE_DOCS;doc=iterator.nextDoc()){checkSearchCancelled();counts.put(leaf.docBase+doc,scorer.freq());}
        }return counts;
    }
    private static long dateKey(String value){if(value==null)return 0;String digits=value.replaceAll("[^0-9]","");try{return Long.parseLong(digits.length()>14?digits.substring(0,14):digits);}catch(Exception e){return 0;}}

    /** Use the existing normal index to identify true whole-word matches.
        Post IDs join separate indexes without assuming identical Lucene doc IDs. */
    private Set<String> wholeWordPostIds(FormRef form,String rawQuery,String mode) throws Exception {
        final Set<String> ids=new HashSet<String>();
        File dir=indexDirFor(form,"full_index");if(!LuceneIndexSource.exists(dir))return ids;
        try(Directory fs=LuceneIndexSource.open(dir);DirectoryReader reader=DirectoryReader.open(fs);
            Analyzer analyzer=analyzerFor("full_index")){
            List<String> terms=analyzeTerms(analyzer,"word",normalizeQuery(rawQuery,"full_index"));
            if(terms.isEmpty())return ids;
            new IndexSearcher(reader).search(buildQuery("word",terms,mode,"full_index"),new SimpleCollector(){
                private LeafReader leaf;
                protected void doSetNextReader(LeafReaderContext context){leaf=context.reader();}
                public void collect(int doc) throws IOException {String id=leaf.document(doc).get("postid");if(id!=null)ids.add(id);}
                public boolean needsScores(){return false;}
            });
        }
        return ids;
    }
    private static Filter postIdFilter(final Set<String> ids){
        return new Filter(){public String toString(String field){return "WholeWordPostIds("+ids.size()+")";} public DocIdSet getDocIdSet(LeafReaderContext context,Bits acceptDocs) throws IOException {
            LeafReader leaf=context.reader();FixedBitSet matches=new FixedBitSet(leaf.maxDoc());
            Terms terms=leaf.terms("postid");if(terms==null||ids.isEmpty())return new BitDocIdSet(matches);
            TermsEnum iterator=terms.iterator();PostingsEnum postings=null;
            for(String id:ids)if(iterator.seekExact(new BytesRef(id))){
                postings=iterator.postings(postings,PostingsEnum.NONE);
                for(int doc=postings.nextDoc();doc!=DocIdSetIterator.NO_MORE_DOCS;doc=postings.nextDoc())
                    if(acceptDocs==null||acceptDocs.get(doc))matches.set(doc);
            }
            return new BitDocIdSet(matches);
        }};
    }

    private static long sortKey(ScoreDoc sd){ if(sd instanceof FieldDoc){Object[] f=((FieldDoc)sd).fields;if(f!=null&&f.length>0&&f[0] instanceof Number)return ((Number)f[0]).longValue();} return 0L; }
    private static int compareNumericDesc(String a,String b){try{return -Long.compare(Long.parseLong(a),Long.parseLong(b));}catch(Exception e){return String.valueOf(b).compareTo(String.valueOf(a));}}
    private static String fieldFor(String kind){return "partial_index".equals(kind)?"ngram_word":("root_index".equals(kind)?"arstem_word":"word");}
    private static Analyzer analyzerFor(String kind){if("root_index".equals(kind))return SearchQuerySupport.analyzer(true);if("partial_index".equals(kind))return DatLuceneIndexer.createPositionalNgramAnalyzer();return SearchQuerySupport.analyzer(false);}
    private static String normalizeQuery(String q,String kind) throws Exception {return SearchTextSupport.strip(q);}
    private static List<String> analyzeTerms(Analyzer a,String field,String text)throws Exception{List<String> out=new ArrayList<String>();TokenStream ts=a.tokenStream(field,text);CharTermAttribute t=ts.addAttribute(CharTermAttribute.class);ts.reset();while(ts.incrementToken()){checkSearchCancelled();String x=t.toString();if(x.length()==0||"__WS__".equals(x)||x.startsWith("E|"))continue;out.add(x);}ts.end();ts.close();return out;}
    /**
     * Query behavior ported from the last legacy desktop Lucene implementation.
     * Match type (phrase/all/any) stays independent from index type.
     * In particular, phrase + partial_index is an exact ordered partial match
     * against the positional ngram index.
     */
    private static Query buildQuery(String field,List<String> terms,String mode,String kind){
        String m=mode==null?"phrase":mode.toLowerCase(Locale.ROOT);
        boolean exact="phrase".equals(m)||"exact".equals(m);
        if(exact){
            SpanQuery[] spans=new SpanQuery[terms.size()];
            for(int i=0;i<terms.size();i++)spans[i]=new SpanTermQuery(new Term(field,terms.get(i)));
            return spans.length>1?new SpanNearQuery(spans,0,true):spans[0];
        }
        BooleanClause.Occur occur="any".equals(m)?BooleanClause.Occur.SHOULD:BooleanClause.Occur.MUST;
        // Only set-based modes discard repeats; ordered phrases retain every token.
        return SearchQuerySupport.terms(field,new ArrayList<String>(new LinkedHashSet<String>(terms)),occur);
    }

    /** Expand *word* against the existing normal-index dictionary; preserve positions for phrases. */
    private static Query buildWildcardQuery(IndexReader reader,String field,List<String> terms,String mode)throws IOException{
        boolean phrase=mode==null||"phrase".equalsIgnoreCase(mode)||"exact".equalsIgnoreCase(mode);
        List<SpanQuery> spans=new ArrayList<SpanQuery>();
        List<String> requested=phrase?terms:new ArrayList<String>(new LinkedHashSet<String>(terms));
        Terms dictionary=MultiFields.getTerms(reader,field);
        for(String term:requested){
            List<SpanQuery> alternatives=new ArrayList<SpanQuery>();
            if(dictionary!=null){
                org.apache.lucene.util.automaton.CompiledAutomaton automaton=new org.apache.lucene.util.automaton.CompiledAutomaton(WildcardQuery.toAutomaton(new Term(field,"*"+term+"*")));
                TermsEnum matches=automaton.getTermsEnum(dictionary);BytesRef word;
                while((word=matches.next())!=null){checkSearchCancelled();alternatives.add(new SpanTermQuery(new Term(field,BytesRef.deepCopyOf(word))));}
            }
            spans.add(alternatives.isEmpty()?new SpanTermQuery(new Term(field,"__NO_WILDCARD_MATCH__")):alternatives.size()==1?alternatives.get(0):new org.apache.lucene.search.spans.SpanOrQuery(alternatives.toArray(new SpanQuery[alternatives.size()])));
        }
        if(phrase)return spans.size()==1?spans.get(0):new SpanNearQuery(spans.toArray(new SpanQuery[spans.size()]),0,true);
        List<Query> queries=new ArrayList<Query>(spans);
        return SearchQuerySupport.queries(queries,"any".equalsIgnoreCase(mode)?BooleanClause.Occur.SHOULD:BooleanClause.Occur.MUST);
    }

    private static String normalizeForPositionalNgram(String input){
        return SearchTextNormalizer.normalize(input,true).replace('ی','ى');
    }

    /** Exact legacy positional-ngram semantics (old LuceneHandler partn path). */
    private static Query buildPositionalNgramQuery(String searchQuery,String searchType){
        String value=normalizeForPositionalNgram(searchQuery).trim();
        if(value.length()==0)return new MatchNoDocsQuery();
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(value);
        List<String> terms=new ArrayList<String>();
        List<Integer> positions=new ArrayList<Integer>();
        while(matcher.find()){terms.add(matcher.group());positions.add(Integer.valueOf(matcher.start()));}
        if(terms.isEmpty())return new MatchNoDocsQuery();
        String m=searchType==null?"phrase":searchType.toLowerCase(Locale.ROOT);
        if("phrase".equals(m)||"exact".equals(m)){
            if(terms.size()==1)return new TermQuery(new Term("ngram_word",terms.get(0)));
            // Fast exact cross-word boundary key preserved from the old desktop.
            if(terms.size()==2&&terms.get(0).length()==1&&terms.get(1).length()==1)
                return new TermQuery(new Term("ngram_word","E|"+terms.get(0)+terms.get(1)));
            PhraseQuery.Builder phrase=new PhraseQuery.Builder();
            phrase.setSlop(0);
            int first=positions.get(0).intValue();
            for(int i=0;i<terms.size();i++){
                if(i>0){
                    int previousEnd=positions.get(i-1).intValue()+terms.get(i-1).length();
                    int currentStart=positions.get(i).intValue();
                    for(int pos=previousEnd;pos<currentStart;pos++){
                        if(Character.isWhitespace(value.charAt(pos)))
                            phrase.add(new Term("ngram_word","__WS__"),pos-first);
                    }
                }
                phrase.add(new Term("ngram_word",terms.get(i)),positions.get(i).intValue()-first);
            }
            return phrase.build();
        }
        BooleanClause.Occur occur="all".equals(m)?BooleanClause.Occur.MUST:BooleanClause.Occur.SHOULD;
        return SearchQuerySupport.terms("ngram_word",terms,occur);
    }
    private File indexDirFor(FormRef f,String kind){
        // Search UI mapping: words+parts => ngram, roots => arstem, whole words => normal.
        String suffix;
        if ("partial_index".equals(kind)) suffix = "_ngram_index";
        else if ("root_index".equals(kind)) suffix = "_arstem_index";
        else suffix = "_normal_index";
        return new File(luceneRoot, f.family + f.id + suffix);
    }
    private List<FormRef> parseForms(String raw){List<FormRef> out=new ArrayList<FormRef>();if(raw==null)return out;for(String x:raw.split(",")){x=x.trim().toLowerCase(Locale.ROOT);if(x.length()==0)continue;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(mn|sn|vb)([0-9]+)").matcher(x);if(m.find())addForm(out,new FormRef(m.group(1),m.group(2)));}return out;}
    private List<FormRef> discoverAllForms(String kind)throws IOException{List<FormRef> out=new ArrayList<FormRef>();Set<String> names=LuceneIndexSource.names(luceneRoot);java.util.regex.Pattern p=java.util.regex.Pattern.compile("^(mn|sn|vb)([0-9]+)"+("root_index".equals(kind)?"_arstem_index":"partial_index".equals(kind)?"_ngram_index":"_normal_index")+"$",java.util.regex.Pattern.CASE_INSENSITIVE);for(String name:names){java.util.regex.Matcher m=p.matcher(name);if(m.matches())addForm(out,new FormRef(m.group(1).toLowerCase(Locale.ROOT),m.group(2)));}return out;}
    private static void addForm(List<FormRef> out,FormRef f){for(FormRef x:out)if(x.family.equals(f.family)&&x.id.equals(f.id))return;out.add(f);}
    private static final class FormRef{final String family,id;FormRef(String f,String i){family=f;id=i;}}
    private static final class Hit{String postid,threadid,forumid,forumType,formid,formFamily,title,postTitle,forumTitle,date,hijriDate,miladiDate;int docId,occurrences;float score;long sortKey,hijriUnix,miladiUnix;boolean wholeWord;}
}
