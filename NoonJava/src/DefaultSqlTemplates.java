import java.util.Properties;

/** Built-in defaults for runtime SQLite -> JS generation.
 *  The external templets/sqlite-js.properties file may override these values,
 *  but the desktop no longer fails just because that file is incomplete.
 */
final class DefaultSqlTemplates {
    private DefaultSqlTemplates(){}
    static void apply(Properties p){
        p.setProperty("config.columns.post_order","published");
        p.setProperty("config.columns.post_userid","userid,user_id,useid");
        p.setProperty("config.columns.sticky","sticky,is_sticky,pinned");
        p.setProperty("config.columns.thread_time","lastpost,lastupdate,edited,published,datetime,dateline,updated,timestamp");
        p.setProperty("config.content.bbcode_to_html","true");
        p.setProperty("config.forum.page_size","20");
        p.setProperty("config.imam.userid","4");
        p.setProperty("config.order.asc_sql","ASC");
        p.setProperty("config.order.asc_token","ASE");
        p.setProperty("config.order.desc_sql","DESC");
        p.setProperty("config.order.desc_token","DESE");
        p.setProperty("config.sqlite.batch_size","400");
        p.setProperty("config.sticky.limit","100000");
        p.setProperty("config.sticky.true_values","1,true,yes,y,on");
        p.setProperty("config.thread.page_size","10");
        p.setProperty("expr.compare_asc","(${time_expr} < ? OR (${time_expr} = ? AND threadid < ?))");
        p.setProperty("expr.compare_desc","(${time_expr} > ? OR (${time_expr} = ? AND threadid > ?))");
        p.setProperty("expr.post_order_fallback","postid ASC");
        p.setProperty("expr.post_order_with_time","published ASC,postid ASC");
        p.setProperty("expr.sticky_fallback","0");
        p.setProperty("expr.sticky_field","CASE WHEN LOWER(CAST(\"${column}\" AS TEXT)) IN ('1','true','yes','y','on') THEN 1 ELSE 0 END");
        p.setProperty("expr.thread_time_fallback","0");
        p.setProperty("expr.thread_time_part","CAST(NULLIF(\"${column}\",'') AS INTEGER)");
        p.setProperty("expr.thread_time_wrap","COALESCE(${parts},0)");
        p.setProperty("js.forum_sticky","var FORUM_STICKY=${json};");
        p.setProperty("js.forum_threads","var FORUM_THREADS=${json};");
        p.setProperty("js.index_mn","var INDEX_MN = ${json};");
        p.setProperty("js.index_vb","var INDEX_VB = ${json};");
        p.setProperty("js.loader_separator",",\\n");
        p.setProperty("js.post","var RAW_JSON=${json};");
        p.setProperty("js.thread","window.THREAD=${root};\\nwindow.__pushPost=function(o){window.THREAD.thread_data.push(o);};\\nloadAll([\\n${loads}\\n],0);\\n");
        p.setProperty("js.thread_asset_loader","ver(base + \"Assets/loader.js\")");
        p.setProperty("js.thread_post_loader","ver(\"js_json/posts/post_${postid}.js\")");
        p.setProperty("math.batch_end","min(total,from+batch_size)");
        p.setProperty("math.batch_next","min(total,from+batch_size)");
        p.setProperty("math.bind_index","index-from+1");
        p.setProperty("math.counter_sum","left+right");
        p.setProperty("math.first_page","1");
        p.setProperty("math.last_page","max(1,ceildiv(total,page_size))");
        p.setProperty("math.normalize_page","max(1,page)");
        p.setProperty("math.offset_from_page","(max(1,page)-1)*page_size");
        p.setProperty("math.page_from_position","max(1,ceildiv(position,page_size))");
        p.setProperty("math.page_loop_next","page+1");
        p.setProperty("math.position_from_index","index+1");
        p.setProperty("math.slice_from","min(total,(max(1,page)-1)*page_size)");
        p.setProperty("math.slice_to","min(total,from+page_size)");
        p.setProperty("math.target_page","(before/page_size)+1");
        p.setProperty("math.zero_item_page","(index/page_size)+1");
        p.setProperty("output.forum","forum_${publicKey}_pa_${page}_order_${order}_lastpost.js");
        p.setProperty("output.index_mn","index_mn.js");
        p.setProperty("output.index_vb","index_vb.js");
        p.setProperty("output.post","post_${postid}.js");
        p.setProperty("output.post_up","thread_${threadid}.html#post-${postid}");
        p.setProperty("output.sticky","stickyforum_${publicKey}_order_${order}_lastpost.js");
        p.setProperty("output.thread","thread_${threadid}_pa_${page}.js");
        p.setProperty("output.thread_up","forum_${family}${forumid}_pa_${page}.html#thread-${threadid}");
        p.setProperty("route.forum",".*?/forum_(mn\\d+|vb\\d+)/js_json/forums/forum_(?:mn|vb)\\d+_pa_(\\d+)_order_([A-Za-z]+)_lastpost\\.js$");
        p.setProperty("route.index_mn_suffix","viewer/index_mn.js");
        p.setProperty("route.index_vb_suffix","viewer/index_vb.js");
        p.setProperty("route.post",".*?/forum_(mn\\d+|vb\\d+)/js_json/posts/post_(\\d+)\\.js$");
        p.setProperty("route.sticky",".*?/forum_(mn\\d+|vb\\d+)/js_json/forums/stickyforum_(?:mn|vb)\\d+_order_([A-Za-z]+)_lastpost\\.js$");
        p.setProperty("route.thread",".*?/forum_(mn\\d+|vb\\d+)/js_json/threads/thread_(\\d+)_pa_(\\d+)\\.js$");
        p.setProperty("sql.extra_post_one","SELECT * FROM posts WHERE postid=? LIMIT 1");
        p.setProperty("sql.extra_posts_all","SELECT * FROM posts");
        p.setProperty("sql.extra_thread_one","SELECT * FROM threads WHERE threadid=? LIMIT 1");
        p.setProperty("sql.extra_threads_all","SELECT * FROM threads");
        p.setProperty("sql.extra_threads_in","SELECT * FROM threads WHERE threadid IN (${placeholders})");
        p.setProperty("sql.extract_post_ids","SELECT postid FROM posts ORDER BY postid");
        p.setProperty("sql.extract_thread_ids","SELECT threadid FROM threads ORDER BY threadid");
        p.setProperty("sql.forum_counters","SELECT formid, threads, posts, media_youtube FROM counts");
        p.setProperty("sql.forum_threads_page","SELECT * FROM threads WHERE (${sticky_expr})=${sticky_value} ORDER BY ${time_expr} ${direction},threadid ${direction} LIMIT ? OFFSET ?");
        p.setProperty("sql.forum_title","SELECT title FROM forums WHERE forumid=? LIMIT 1");
        p.setProperty("sql.has_table","SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1");
        p.setProperty("sql.imam_posts","SELECT postid FROM posts WHERE \"${uid_column}\"=${imam_userid}");
        p.setProperty("sql.index_forums","SELECT * FROM forums ORDER BY display_order, forumid");
        p.setProperty("sql.normal_thread_count","SELECT COUNT(*) FROM threads WHERE (${sticky_expr})=0");
        p.setProperty("sql.open_init","PRAGMA query_only=ON");
        p.setProperty("sql.post_ids_by_thread","SELECT postid FROM posts WHERE threadid=? ORDER BY ${order_column}");
        p.setProperty("sql.post_one","SELECT * FROM posts WHERE postid=? LIMIT 1");
        p.setProperty("sql.posts_for_threads","SELECT threadid,postid FROM posts WHERE threadid IN (${placeholders}) ORDER BY threadid,${order}");
        p.setProperty("sql.posts_uid_one","SELECT \"${uid_column}\" FROM posts WHERE postid=? LIMIT 1");
        p.setProperty("sql.table_info","PRAGMA table_info(\"${table}\")");
        p.setProperty("sql.thread_one","SELECT * FROM threads WHERE threadid=? LIMIT 1");
        p.setProperty("sql.thread_page_count_before","SELECT COUNT(*) FROM threads WHERE (${sticky_expr})=0 AND ${compare}");
        p.setProperty("sql.thread_position","SELECT (${sticky_expr}) AS st,${time_expr} AS tm FROM threads WHERE threadid=? LIMIT 1");
        p.setProperty("sql.thread_title","SELECT title FROM threads WHERE threadid=? LIMIT 1");
        p.setProperty("sql.users_all","SELECT * FROM user");
        p.setProperty("sql.vb5_threads","SELECT * FROM threads ORDER BY threadid");
    }
}
