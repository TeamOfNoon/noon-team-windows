import java.util.*;
import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.standard.StandardFilter;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.ar.ArabicNormalizationFilter;
import org.apache.lucene.analysis.ar.ArabicStemFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

/** Shared indexing/query analysis and bounded Boolean nodes for long queries. */
final class SearchQuerySupport {
    static Analyzer analyzer(final boolean roots) {
        return new Analyzer() {
            protected TokenStreamComponents createComponents(String field) {
                StandardTokenizer tokenizer = new StandardTokenizer();
                tokenizer.setMaxTokenLength(StandardTokenizer.MAX_TOKEN_LENGTH_LIMIT);
                TokenStream stream = roots ? tokenizer : new StandardFilter(tokenizer);
                stream = new LowerCaseFilter(stream);
                if (roots) {
                    stream = new ArabicNormalizationFilter(stream);
                    stream = new ArabicStemFilter(stream);
                    stream = new PorterStemFilter(stream);
                }
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }
    static Query terms(String field, List<String> terms, BooleanClause.Occur occur) {
        List<Query> level = new ArrayList<Query>();
        for (String term : terms) level.add(new TermQuery(new Term(field, term)));
        return queries(level,occur);
    }
    static Query queries(List<Query> initial, BooleanClause.Occur occur) {
        List<Query> level=new ArrayList<Query>(initial);
        int limit = BooleanQuery.getMaxClauseCount();
        if (limit < 2) throw new IllegalStateException("Boolean clause capacity must be at least two");
        boolean grouped = level.size() > limit;
        while (level.size() > limit) {
            List<Query> next = new ArrayList<Query>();
            for (int from = 0; from < level.size(); from += limit)
                next.add(group(level.subList(from, Math.min(level.size(), from + limit)), occur, true));
            level = next;
        }
        return group(level, occur, grouped);
    }
    private static Query group(List<Query> queries, BooleanClause.Occur occur, boolean grouped) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        if (grouped) builder.setDisableCoord(true);
        for (Query query : queries) builder.add(query, occur);
        if (occur == BooleanClause.Occur.SHOULD) builder.setMinimumNumberShouldMatch(1);
        return builder.build();
    }
}
