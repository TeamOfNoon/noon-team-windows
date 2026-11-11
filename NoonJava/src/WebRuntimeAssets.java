import java.nio.charset.StandardCharsets;
import java.nio.file.*;
/** Common final browser resources for live serving and generated web apps. */
final class WebRuntimeAssets {
 static final String[] PATHS={"res/searcher/css/base.css","res/viewer/domain-text.js","res/viewer/app.js","res/searcher/app.js","res/searcher/search_logic.js"};
 static byte[] transform(String path,byte[] data)throws Exception{
  if(path.endsWith("res/searcher/css/base.css"))return SearchSnippetPreview.searchResultCss(new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
  if(path.endsWith("res/viewer/domain-text.js"))return SearchIndexConfig.withDomainTextAvailability(data);
  if(path.endsWith("res/viewer/app.js"))return SearchSnippetPreview.applyViewer(new String(SearchIndexConfig.withViewerAvailability(data),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
  if(path.endsWith("res/searcher/app.js"))return SearchSnippetPreview.apply(new String(SearchIndexConfig.withSearchAvailability(data),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
  if(path.endsWith("res/searcher/search_logic.js"))return SearchSnippetPreview.normalizeSearchLogic(new String(data,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
  return data;
 }
 static void export(Path www)throws Exception{
  for(String name:PATHS){Path file=www.resolve(name);if(Files.isRegularFile(file))Files.write(file,transform(name,Files.readAllBytes(file)));}
 }
}
