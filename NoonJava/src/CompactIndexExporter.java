import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.json.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.tokenattributes.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;

/** Offline JS/BIN exporter. Lucene is used only as a bounded-memory posting sorter. */
final class CompactIndexExporter {
 static final int FULL=1,PARTIAL=2,STEM=4;
 static String generate(PlainSqliteCatalog cat,File app,boolean js,int kinds)throws Exception{return generate(cat,app,js,kinds,null);}
 static String generate(PlainSqliteCatalog cat,File app,boolean js,int kinds,Set<String> selected)throws Exception{return generate(cat,app,js,kinds,selected,15);}
 static String generate(PlainSqliteCatalog cat,File app,boolean js,int kinds,Set<String> selected,int ranks)throws Exception{
  if(ranks<1||(ranks&~15)!=0)throw new IllegalArgumentException("Select a ranking");
  if(kinds<1||(kinds&~7)!=0)throw new IllegalArgumentException("Select Full, Partial or Stemmed");
  Path www=app.toPath().resolve("www"),base=www.resolve("whxdata_js");
  if(selected!=null){if(selected.isEmpty())throw new IllegalArgumentException("Select an available range");for(String code:selected)if(!cat.forms.containsKey(code))throw new IOException("Range not available: "+code);}
  Files.createDirectories(base);Set<String> forms=new LinkedHashSet<String>();
  for(PlainSqliteCatalog.FormDb form:cat.forms.values()){
   String code=form.family+form.forumId;if(selected!=null&&!selected.contains(code))continue;
   JSONObject config=UpdateConfig.loadObject(cat.nt),defaults=config.optJSONObject("forum_defaults"),overrides=config.optJSONObject("forum_overrides"),o=overrides==null?null:overrides.optJSONObject(code);
   boolean allowed=defaults==null||defaults.optBoolean("show_in_index",true);if(o!=null)allowed=o.optBoolean("show_in_index",allowed);
   if(!allowed||("vb".equals(form.family)&&!"4".equals(form.forumId)))continue;
   exportForm(cat,form,base.resolve(code),js,kinds);forms.add(code);
  }
  if(forms.isEmpty())throw new IOException("No installed, index-enabled SQLite forums found");
  patchWeb(www,js,kinds,forms,ranks);
  StringJoiner typeNames=new StringJoiner(", ");if((kinds&FULL)!=0)typeNames.add("Full");if((kinds&PARTIAL)!=0)typeNames.add("Partial");if((kinds&STEM)!=0)typeNames.add("Stemmed");
  return "Offline "+(js?"index_js":"index_bin")+" generated / تم إنشاء فهارس البحث: "+forms+"\nTypes / الأنواع: "+typeNames+"\napp/www/whxdata_js";
 }
 static final class Key {final String text;final int position;Key(String t,int p){text=t;position=p;}}
 static final class Keys extends TokenStream {
  final List<Key> keys;int next=0,last=-1;final CharTermAttribute term=addAttribute(CharTermAttribute.class);final PositionIncrementAttribute inc=addAttribute(PositionIncrementAttribute.class);
  Keys(List<Key> k){keys=k;keys.sort(Comparator.comparingInt(x->x.position));}
  public boolean incrementToken(){if(next==keys.size())return false;clearAttributes();Key k=keys.get(next++);term.append(k.text);inc.setPositionIncrement(k.position-last);last=k.position;return true;}
 }
 static List<Key> keys(String text,int kinds,Analyzer stem)throws Exception{
  List<Key> out=new ArrayList<Key>();int fullAt=0,rootAt=0;
  for(String word:text.split(" ")){if(word.isEmpty())continue;
   if((kinds&FULL)!=0)out.add(new Key("F|"+word,fullAt));fullAt+=word.length();
   if((kinds&STEM)!=0){try(TokenStream ts=stem.tokenStream("root",word)){CharTermAttribute term=ts.addAttribute(CharTermAttribute.class);ts.reset();while(ts.incrementToken()){String root=term.toString();out.add(new Key("R|"+root,rootAt));rootAt+=root.length();}ts.end();}}
  }
  if((kinds&PARTIAL)!=0)try(Analyzer grams=DatLuceneIndexer.createPositionalNgramAnalyzer();TokenStream ts=grams.tokenStream("ngram_word",text)){
   CharTermAttribute term=ts.addAttribute(CharTermAttribute.class);PositionIncrementAttribute inc=ts.addAttribute(PositionIncrementAttribute.class);int at=-1;ts.reset();
   while(ts.incrementToken()){at+=inc.getPositionIncrement();out.add(new Key("P|"+term.toString(),at));}ts.end();
  }
  return out;
 }
 static void exportForm(PlainSqliteCatalog cat,PlainSqliteCatalog.FormDb form,Path folder,boolean js,int kinds)throws Exception{
  Files.createDirectories(folder);Path scratch=Files.createTempDirectory("noon-compact-");
  FieldType ft=new FieldType();ft.setTokenized(true);ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);ft.freeze();
  Map<String,JSONObject> originalDates=SqliteLuceneIndexer.originalDates(form);
  JSONArray topicMap=new JSONArray();JSONObject map=new JSONObject(),dates=new JSONObject(),postids=new JSONObject(),rankDocs=new JSONObject();int docId=0,chunkId=0;String forumTitle=cat.forumTitle(form);if(forumTitle.trim().isEmpty())forumTitle=form.family+form.forumId;
  try(Directory dir=FSDirectory.open(scratch);Analyzer analyzer=SearchQuerySupport.analyzer(false);Analyzer stem=SearchQuerySupport.analyzer(true)){
   Map<String,String> threadTitles=new HashMap<String,String>();try(Connection tc=cat.open(form.data);Statement ts=tc.createStatement();ResultSet tr=ts.executeQuery("SELECT * FROM threads")){Map<String,Integer> cols=new HashMap<String,Integer>();ResultSetMetaData md=tr.getMetaData();for(int n=1;n<=md.getColumnCount();n++)cols.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),n);while(tr.next())threadTitles.put(get(tr,cols,"threadid","thread_id","id"),get(tr,cols,"title","thread_title","name"));}
   try(IndexWriter writer=new IndexWriter(dir,new IndexWriterConfig(analyzer));Connection c=cat.open(form.data);Statement st=c.createStatement();ResultSet r=st.executeQuery("SELECT * FROM posts")){
    Map<String,Integer> cols=new HashMap<String,Integer>();ResultSetMetaData md=r.getMetaData();for(int n=1;n<=md.getColumnCount();n++)cols.put(md.getColumnLabel(n).toLowerCase(Locale.ROOT),n);
    while(r.next()){
     String post=get(r,cols,"postid","post_id","id");if(post.isEmpty())continue;
     String thread=get(r,cols,"threadid","thread_id"),title=get(r,cols,"title","post_title","posttitle","subject"),raw=get(r,cols,"long_text","pagetext","content","content_html","body","text","message"),body=SqliteLuceneIndexer.cleanPostTextForIndex(raw),date=get(r,cols,"miladi_date","dateline","date","timestamp");
     title=SearchTextSupport.unescapeHtml(title);
     String text=DatLuceneIndexer.normalizedIndexText(title,body);
     List<Key> indexedKeys=keys(text,(kinds&PARTIAL)!=0?kinds|FULL:kinds,stem);
     Document doc=new Document();doc.add(new NumericDocValuesField("offline_doc",docId));doc.add(new Field("key",new Keys(indexedKeys),ft));writer.addDocument(doc);
     Document originalDate=new Document();OriginalDateFields.add(originalDate,originalDates.getOrDefault(post,new JSONObject()));long originalKey=OriginalDateFields.key(originalDate);
     date=OriginalDateFields.display(originalDate.get("hijri_date"),originalDate.get("miladi_date"),originalDate.get("date_original"));
     rankDocs.put(String.valueOf(docId),new JSONObject().put("p",post).put("n",rankingNorms(text,stem,indexedKeys)));
     String code=form.family+form.forumId,url="viewer.html?fo="+form.forumId+"&ftype="+form.family+"&po="+post;
     String username=get(r,cols,"username","user_name","author","poster"),displayTitle=title.trim().isEmpty()?threadTitles.getOrDefault(thread,""):title;if(!username.isEmpty())displayTitle=username+": * "+displayTitle+" *";
     JSONObject topic=new JSONObject().put("doc",docId).put("postid",post).put("threadid",thread).put("forumid",form.forumId).put("formid",form.forumId).put("forum_type",form.family).put("forum", "forum_"+code).put("offline_body",true).put("form_code",code).put("title",displayTitle).put("post_title",title.trim().isEmpty()?threadTitles.getOrDefault(thread,""):title).put("body",body).put("date",date).put("date_original_unix",originalKey).put("hijri_date",originalDate.get("hijri_date")).put("miladi_date",originalDate.get("miladi_date")).put("url",url).put("forum_title",forumTitle);
     topicMap.put(topic);map.put(String.valueOf(docId),new JSONArray().put("chunk_"+chunkId+".json"));dates.put(String.valueOf(docId),originalKey);postids.put(String.valueOf(docId),post);docId++;
     if(topicMap.length()==128){json(folder,"topics/chunk_"+chunkId+".json",topicMap,js,code);topicMap=new JSONArray();chunkId++;}
    }
    writer.commit();
   }
   String code=form.family+form.forumId;
   if(topicMap.length()>0)json(folder,"topics/chunk_"+chunkId+".json",topicMap,js,code);
   json(folder,"topics/map.json",map,js,code);json(folder,"topics/dates.json",dates,js,code);json(folder,"topics/postids.json",postids,js,code);
   json(folder,"topics/ranking.json",new JSONObject().put("maxDoc",docId).put("docs",rankDocs).put("wholeKind",(kinds&PARTIAL)!=0?((kinds&FULL)!=0?"full":"whole"):""),js,code);
   try(DirectoryReader reader=DirectoryReader.open(dir)){Terms all=MultiFields.getTerms(reader,"key");for(String kind:new String[]{"full","partial","root","whole"}){
    if(kind.equals("whole")&&((kinds&PARTIAL)==0||(kinds&FULL)!=0))continue;
    int flag=kind.equals("full")?FULL:kind.equals("partial")||kind.equals("whole")?PARTIAL:STEM;if((kinds&flag)==0)continue;
    writeKind(folder,code,kind,all,js,sourceIds(reader));
   }}
  }finally{deleteTree(scratch);}
 }
 static JSONArray rankingNorms(String text,Analyzer stem,List<Key> indexedKeys)throws Exception{
  JSONArray out=new JSONArray();int partialLength=0,last=-1;
  // Keys has already sorted these tokens by position. Counting distinct partial
  // positions reproduces Lucene's overlap discount without generating grams again.
  for(Key key:indexedKeys)if(key.text.startsWith("P|")&&key.position!=last){partialLength++;last=key.position;}
  try(Analyzer full=SearchQuerySupport.analyzer(false)){
   for(int kind=0;kind<3;kind++){int length=partialLength;
    if(kind!=1){length=0;Analyzer analyzer=kind==0?full:stem;try(TokenStream ts=analyzer.tokenStream("rank",text)){PositionIncrementAttribute inc=ts.addAttribute(PositionIncrementAttribute.class);ts.reset();while(ts.incrementToken())if(inc.getPositionIncrement()>0)length++;ts.end();}}
    float value=(float)(1.0/Math.sqrt(Math.max(1,length)));out.put(org.apache.lucene.util.SmallFloat.byte315ToFloat(org.apache.lucene.util.SmallFloat.floatToByte315(value)));
   }
  }return out;
 }
 static String get(ResultSet r,Map<String,Integer> cols,String... names)throws SQLException{for(String n:names){Integer i=cols.get(n);if(i!=null){String s=r.getString(i);if(s!=null)return s;}}return "";}
 static int[] sourceIds(DirectoryReader reader)throws IOException{
  NumericDocValues ids=MultiDocValues.getNumericValues(reader,"offline_doc");
  if(ids==null&&reader.maxDoc()>0)throw new IOException("Missing stable offline document IDs");
  int[] out=new int[reader.maxDoc()];boolean[] seen=new boolean[out.length];
  for(int i=0;i<out.length;i++){long value=ids.get(i);if(value<0||value>=out.length||seen[(int)value])throw new IOException("Invalid stable offline document ID: "+value);out[i]=(int)value;seen[out[i]]=true;}
  return out;
 }
 static void writeKind(Path folder,String form,String kind,Terms terms,boolean js,int[] sourceIds)throws Exception{
  String prefix=kind.equals("full")||kind.equals("whole")?"F|":kind.equals("partial")?"P|":"R|";String path=kind+"/";
  ByteArrayOutputStream[] tips=new ByteArrayOutputStream[256];int[] chunks=new int[256];byte[][] previous=new byte[256][];
  Blocks docs=new Blocks(folder,form,path+"_0.doc.chunks/",js),positions=new Blocks(folder,form,path+"_0.pos.chunks/",js);
  if(terms!=null){TermsEnum iterator=terms.iterator();BytesRef ref;
   while((ref=iterator.next())!=null){String term=ref.utf8ToString();if(!term.startsWith(prefix))continue;
    ByteArrayOutputStream d=new ByteArrayOutputStream(),p=new ByteArrayOutputStream();vint(d,iterator.docFreq());PostingsEnum pe=iterator.postings(null,PostingsEnum.POSITIONS);int oldDoc=0,id;
    // Lucene merges may reorder internal document IDs. Persist the topic ID with each
    // indexed document and sort postings by that ID before delta encoding them.
    SortedMap<Integer,int[]> ordered=new TreeMap<Integer,int[]>();
    while((id=pe.nextDoc())!=PostingsEnum.NO_MORE_DOCS){int[] pos=new int[pe.freq()];for(int n=0;n<pos.length;n++)pos[n]=pe.nextPosition();ordered.put(sourceIds[id],pos);}
    for(Map.Entry<Integer,int[]> entry:ordered.entrySet()){id=entry.getKey();vint(d,id-oldDoc);oldDoc=id;int[] pos=entry.getValue();vint(d,pos.length);int old=0;for(int value:pos){vint(p,value-old);old=value;}}
    int[] ds=docs.add(d.toByteArray()),ps=positions.add(p.toByteArray());int bucket=hash(term);if(tips[bucket]==null){tips[bucket]=new ByteArrayOutputStream();previous[bucket]=new byte[0];}
    byte[] key=term.getBytes(StandardCharsets.UTF_8),prev=previous[bucket];int common=0;while(common<key.length&&common<prev.length&&key[common]==prev[common])common++;
    ByteArrayOutputStream tip=tips[bucket];vint(tip,common);vint(tip,key.length-common);tip.write(key,common,key.length-common);vint(tip,iterator.docFreq());for(int x:ds)vint(tip,x);for(int x:ps)vint(tip,x);previous[bucket]=key;
    if(tip.size()>256*1024){binary(folder,path+"_0.tip.chunks/"+String.format(Locale.ROOT,"%03d_%d.bin",bucket,chunks[bucket]++),tip.toByteArray(),js,form);tips[bucket]=null;}
   }
  }
  docs.close();positions.close();JSONArray counts=new JSONArray();for(int i=0;i<256;i++){if(tips[i]!=null&&tips[i].size()>0){binary(folder,path+"_0.tip.chunks/"+String.format(Locale.ROOT,"%03d_%d.bin",i,chunks[i]++),tips[i].toByteArray(),js,form);}counts.put(chunks[i]);}
  json(folder,path+"_0.tip.json",new JSONObject().put("chunks",counts),js,form);
 }
 static final class Blocks implements AutoCloseable{final Path folder;final String form,path;final boolean js;int id=0;ByteArrayOutputStream out=new ByteArrayOutputStream();Blocks(Path f,String c,String p,boolean j){folder=f;form=c;path=p;js=j;}int[] add(byte[] bytes)throws IOException{if(out.size()>0&&out.size()+bytes.length>4*1024*1024)flush();int[] at={id,out.size(),bytes.length};out.write(bytes);return at;}void flush()throws IOException{binary(folder,path+"block_"+id+++".bin",out.toByteArray(),js,form);out.reset();}public void close()throws IOException{if(out.size()>0)flush();}}
 static int hash(String s){int h=0x811c9dc5;for(int i=0;i<s.length();i++)h=(h^s.charAt(i))*16777619;return h&255;}
 static void vint(OutputStream out,int n)throws IOException{if(n<0)throw new IOException("Negative posting value");while((n&~127)!=0){out.write((n&127)|128);n>>>=7;}out.write(n);}
 static void binary(Path root,String key,byte[] bytes,boolean js,String form)throws IOException{Path p=root.resolve(key+(js?".js":""));Files.createDirectories(p.getParent());if(js)Files.write(p,("XMLJS.binary("+JSONObject.quote(form+"/"+key)+","+JSONObject.quote(Base64.getEncoder().encodeToString(bytes))+");").getBytes(StandardCharsets.UTF_8));else Files.write(p,bytes);}
 static void json(Path root,String key,Object value,boolean js,String form)throws IOException{Path p=root.resolve(key+(js?".js":""));Files.createDirectories(p.getParent());Files.write(p,(js?"XMLJS.receive("+JSONObject.quote(form+"/"+key)+","+value+");\n":value.toString()).getBytes(StandardCharsets.UTF_8));}
 static void deleteTree(Path root)throws IOException{if(Files.exists(root))try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
 static void patchWeb(Path www,boolean js,int kinds,Set<String> forms,int ranks)throws Exception{
  SearchIndexConfig.writeCompactState(www.toFile(),js,forms);
  JSONArray state=new JSONArray();for(String form:forms)state.put(new JSONObject().put("formid",form).put("id",form).put("installed",true).put("has_sqlite",true).put("indexed",true));
  Path config=www.resolve("res/config.js");String configText=new String(Files.readAllBytes(config),StandardCharsets.UTF_8);configText=SearchIndexConfig.replaceAppProperty(configText,"search_forms",state.toString());configText=SearchIndexConfig.replaceAppProperty(configText,"search_ranges",state.toString());
  Files.write(config,(configText+"\nwindow.NT_FORUM_STATE="+state.toString()+";\n").getBytes(StandardCharsets.UTF_8));
  Path cache=www.resolve("res/cache_update.js");Files.write(cache,("window.cache_update="+System.currentTimeMillis()+";window.search_index_type="+JSONObject.quote(js?"js":"bin")+";window.search_engin_type="+JSONObject.quote(js?"js":"bin")+";").getBytes(StandardCharsets.UTF_8));
  Path logic=www.resolve("res/searcher/search_logic.js");String s=new String(Files.readAllBytes(logic),StandardCharsets.UTF_8);
  int unicode=s.indexOf("/* NOON_UNICODE_SEARCH_V3");
  if(unicode>=0){int end=s.indexOf("})(window);",unicode);if(end<0)throw new IOException("Incomplete search normalization script");s=s.substring(0,unicode)+s.substring(end+"})(window);".length());}
  s=SearchTextNormalizer.javascript().replace("if(w.noonUnicodeNormalizationVersion===3)return;", "")+s;
  s=s.replace("var kind=indexType==='partial_index'?'partial':'full',ts=words(q,kind==='full'),prefix=kind==='partial'?'P|':'F|'", "var kind=indexType==='root_index'?'root':indexType==='partial_index'?'partial':'full',ts=words(q,true),prefix=kind==='root'?'R|':kind==='partial'?'P|':'F|'");
  s=s.replace(" if(mode!=='phrase'){var uq=[]", " if(kind==='root')ts=ts.map(function(t){return window.noonStemWord(t)});\n if(mode!=='phrase'){var uq=[]");
  s=s.replace("var size=Math.min(2,t.length);for(var i=0;i+size<=t.length;i++)add('P|'+t.substr(i,size),offset+i);","add('P|'+t,offset);");
  s=s.replace("function partialGramRows(terms){", "function partialGramRows(terms){if(terms.length===1)return loadDocs('partial','P|'+terms[0]).then(function(x){return x.list.map(function(row){return{doc:row[0],occurrences:row[1],score:row[1]}})});");
  s=s.replace("if(kind==='partial'){ts=words(q,true);", "if(kind==='partial'){ts=words(q,true).map(function(t){return t.replace(/ی/g,'ى')});");
  s=s.replace("/* NOON_NGRAM_1_2 */", "/* NOON_FULL_LENGTH_PARTIAL */");
  // Relevance rows from combinedRows must be sorted even for a single term.
  s=s.replace("function finishRows(rows){", "function finishRows(rows){if(!sortOrder||sortOrder==='relevance')rows.sort(function(a,b){return (b.score||0)-(a.score||0)||b.doc-a.doc});");
  // Compare dates numerically across ranges, including seconds, milliseconds and date strings.
  s=s.replace("var x=String(a._date),y=String(b._date);if(x!==y)return(x<y?-1:1)*(sort==='date_newest'?-1:1);", "var x=offlineDate(a._date),y=offlineDate(b._date);if(x===null&&y!==null)return 1;if(y===null&&x!==null)return -1;if(x!==y)return(x-y)*(sort==='date_newest'?-1:1);");
  s=s.replace(" var P=g.ES3Promise,engines={}", " function offlineDate(raw){var s=String(raw==null?'':raw).replace(/[٠-٩]/g,function(c){return String(c.charCodeAt(0)-1632)}).trim(),n;if(/^\\d{9,13}$/.test(s))return +s*(s.length<=10?1000:1);n=Date.parse(s);return isNaN(n)?null:n}\n var P=g.ES3Promise,engines={}");
  s=s.replace("function rawPhrase(){","function rawPhrase(){if(kind==='root')return generalPhraseRows();");
  // Partial already contains whole-word postings. Avoid collecting every page of two searches
  // before returning page one; this also lets the selected ranking order apply globally.
  s=s.replace("if(kind!=='partial_index')return base.search", "if(true)return base.search");
  s=s.replace("function search(q,indexType,mode,page,pageSize,sortOrder){", OfflineSearchRanking.script()+"\nfunction search(q,indexType,mode,page,pageSize,sortOrder){");
  s=s.replace("function finishRows(rows){", "function finishRows(rows){return offlineRankRows(rows,kind,ts,mode,sortOrder,q).then(function(rows){");
  s=s.replace("function pageRows(r){", "rows.sort(function(a,b){return offlineRankCompare(a,b,sortOrder)});function pageRows(r){");
  s=s.replace(":Promise.resolve(pageRows(rows))}", ":Promise.resolve(pageRows(rows))})}");
  s=s.replace("hits.sort(function(a,b){if(sort==='occurrences')", "hits.sort(function(a,b){if(sort!=='date_newest'&&sort!=='date_oldest'&&!!a.whole_word_match!==!!b.whole_word_match)return a.whole_word_match?-1:1;if(sort==='occurrences')");
  s=s.replace("||String(a._form).localeCompare(String(b._form))||b._local-a._local", "||offlineMergedPostOrder(a,b)");
  s=s.replace(" function offlineDate(raw){", " function offlineMergedPostOrder(a,b){var x=String(a.postid||'0').replace(/^0+/,''),y=String(b.postid||'0').replace(/^0+/,'');return y.length-x.length||(x<y?1:x>y?-1:0)}\n function offlineDate(raw){");
  s=s.replace("sortByOriginalDate(rows,dateOrder,dates)", "offlineDateSort(rows,dateOrder,dates)");
  s=s.replace("h._date=d.date||''", "h._date=d.date_original_unix||0");
  s=s.replace("var x=offlineDate(a._date),y=offlineDate(b._date);if(x===null&&y!==null)return 1;if(y===null&&x!==null)return -1;if(x!==y)return(x-y)*(sort==='date_newest'?-1:1);", "var x=+a._date||0,y=+b._date||0;if(!x&&y)return 1;if(x&&!y)return -1;if(x!==y)return(x-y)*(sort==='date_newest'?-1:1);");
  s=s.replace("function offlineMergedPostOrder(a,b){", "function offlineMergedPostOrder(a,b,sort){if(sort==='relevance')return String(a._form).localeCompare(String(b._form))||a._local-b._local;");
  s=s.replace("||offlineMergedPostOrder(a,b)", "||offlineMergedPostOrder(a,b,sort)");
  Files.write(logic,s.getBytes(StandardCharsets.UTF_8));
  Path stem=www.resolve("res/noon-stemmer.js");if(!Files.isRegularFile(stem))Files.write(stem,RootSearchSupport.stemScript().getBytes(StandardCharsets.UTF_8));
  Path search=www.resolve("res/searcher/app.js");String code=new String(Files.readAllBytes(search),StandardCharsets.UTF_8);
  // Result headings may include the author, but hit IDs must count only the
  // exact title/body rendered inside Viewer's .post-body.
  code=code.replace("var title=record.title!=null?record.title:doc.post_title||'';","var offline=record.offline_body===true||doc.offline_body===true;var title=offline?(record.post_title!=null?record.post_title:doc.post_title||''):(record.title!=null?record.title:doc.post_title||'');");
  String kindsJson=new JSONObject().put("full_index",(kinds&FULL)!=0).put("wildcard_index",false).put("partial_index",(kinds&PARTIAL)!=0).put("root_index",(kinds&STEM)!=0).toString();
  code=code.replace("applySelectorVisibility(kind,searchSelectorUi.searchTypeSelector);", "applySelectorVisibility(kind,{options:"+kindsJson+"});");
  // Exported topic chunks already contain complete cleaned post text. Use it immediately,
  // rather than requesting a second post script (which may not exist in an index-only copy).
  code=code.replace("var postid=postids[doc],base=postBaseFor(docs[doc]||{});", "var source=docs[doc]||{};if(source.offline_body===true){out[doc]=source;if(onRecord)onRecord(doc,source);return}var postid=postids[doc],base=postBaseFor(source);");
  JSONObject rankingOptions=new JSONObject();String[] rankings={"occurrences","relevance","date_newest","date_oldest"};
  for(int n=0;n<rankings.length;n++)rankingOptions.put(rankings[n],(ranks&(1<<n))!=0);
  code=code.replace("applySelectorVisibility(sort,searchSelectorUi.searchSortSelector);", "applySelectorVisibility(sort,{options:"+rankingOptions+"});");
  Files.write(search,code.getBytes(StandardCharsets.UTF_8));
 }
}
