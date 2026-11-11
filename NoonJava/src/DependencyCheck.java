import java.sql.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;


/** Real in-memory checks; does not open or modify user databases or indexes. */
public final class DependencyCheck {
 public static int run(){int result=0;try{lucene();}catch(Throwable e){result|=1;}try{sqlite();}catch(Throwable e){result|=2;}try{json();}catch(Throwable e){result|=4;}try{html();}catch(Throwable e){result|=8;}try{lang();}catch(Throwable e){result|=16;}return result;}
 public static void lucene()throws Exception{
  try(Directory dir=new RAMDirectory();StandardAnalyzer analyzer=new StandardAnalyzer()){
   try(IndexWriter writer=new IndexWriter(dir,new IndexWriterConfig(analyzer))){
    Document doc=new Document();doc.add(new TextField("content","noon dependency check",Field.Store.YES));writer.addDocument(doc);
   }
   try(DirectoryReader reader=DirectoryReader.open(dir)){
    Query q=new TermQuery(new Term("content","noon"));
    if(new IndexSearcher(reader).search(q,1).totalHits!=1)throw new Exception("Lucene check failed");
   }
  }
 }
 public static void json()throws Exception{
  org.json.JSONObject value=new org.json.JSONObject().put("check",true);
  if(!value.getBoolean("check"))throw new Exception("JSON check failed");
 }
 public static void html()throws Exception{
  if(!org.jsoup.Jsoup.parse("<p>noon</p>").text().equals("noon"))throw new Exception("jsoup check failed");
 }
 public static void lang()throws Exception{
  if(!org.apache.commons.lang.StringUtils.isBlank(" "))throw new Exception("Commons Lang check failed");
 }
 public static void sqlite()throws Exception{
  Driver driver=new org.sqlite.JDBC();
  try(Connection connection=driver.connect("jdbc:sqlite::memory:",new java.util.Properties());Statement st=connection.createStatement();ResultSet r=st.executeQuery("SELECT 1")){
   if(!r.next()||r.getInt(1)!=1)throw new Exception("SQLite check failed");
  }
 }
}
