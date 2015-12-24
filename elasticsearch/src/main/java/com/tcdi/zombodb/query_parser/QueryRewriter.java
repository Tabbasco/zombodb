/*
 * Portions Copyright 2013-2015 Technology Concepts & Design, Inc
 * Portions Copyright 2015 ZomboDB, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tcdi.zombodb.query_parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.search.*;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.io.stream.ByteBufferStreamInput;
import org.elasticsearch.common.io.stream.InputStreamStreamInput;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.*;
import org.elasticsearch.index.query.support.QueryInnerHitBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.range.RangeBuilder;
import org.elasticsearch.search.aggregations.bucket.range.date.DateRangeBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTermsBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.term.TermSuggestionBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.*;

import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.search.aggregations.AggregationBuilders.*;

public class QueryRewriter {

    public static String[] WILDCARD_TOKENS = {"ZDB_ESCAPE_ZDB", "ZDB_STAR_ZDB", "ZDB_QUESTION_ZDB", "ZDB_TILDE_ZDB"};
    public static String[] WILDCARD_VALUES = {"\\\\",           "*",            "?",                "~"};

    private enum DateHistogramIntervals {
        year, quarter, month, week, day, hour, minute, second
    }

    /* short for QueryBuilderFactory */
    private interface QBF {
        QBF DUMMY = new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                throw new QueryRewriteException("Should not get here");
            }
        };

        QueryBuilder b(QueryParserNode n);
    }

    /**
     * Container for range aggregation spec
     */
    static class RangeSpecEntry {
        public String key;
        public Double from;
        public Double to;
    }

    /**
     * Container for date range aggregation spec
     */
    static class DateRangeSpecEntry {
        public String key;
        public String from;
        public String to;
    }

    public static class QueryRewriteException extends RuntimeException {
        public QueryRewriteException(String message) {
            super(message);
        }

        public QueryRewriteException(Throwable cause) {
            super(cause);
        }

        public QueryRewriteException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String DateSuffix = ".date";

    private final Client client;
    private final ASTQueryTree tree;
    private final QueryParserNode rootNode;
    private final String searchPreference;

    private final String indexName;
    private final String input;
    private boolean allowSingleIndex;
    private boolean ignoreASTChild;
    private final boolean useParentChild;
    private boolean _isBuildingAggregate = false;
    private boolean queryRewritten = false;

    private Map<String, StringBuilder> arrayData;

    private final IndexMetadataManager metadataManager;

    public QueryRewriter(Client client, String indexName, String searchPreference, String input, boolean allowSingleIndex, boolean useParentChild) {
        this(client, indexName, searchPreference, input, allowSingleIndex, false, useParentChild);
    }

    private QueryRewriter(Client client, String indexName, String searchPreference, String input, boolean allowSingleIndex, boolean ignoreASTChild, boolean useParentChild) {
        this(client, indexName, searchPreference, input, allowSingleIndex, ignoreASTChild, useParentChild, false);
    }

    public  QueryRewriter(Client client, final String indexName, String searchPreference, String input, boolean allowSingleIndex, boolean ignoreASTChild, boolean useParentChild, boolean extractParentQuery) {
        this.client = client;
        this.indexName = indexName;
        this.input = input;
        this.allowSingleIndex = allowSingleIndex;
        this.ignoreASTChild = ignoreASTChild;
        this.useParentChild = useParentChild;
        this.searchPreference = searchPreference;

        metadataManager = new IndexMetadataManager(
                client,
                new ASTIndexLink(QueryParserTreeConstants.JJTINDEXLINK) {
                    @Override
                    public String getLeftFieldname() {
                        return metadataManager == null || metadataManager.getMetadataForMyOriginalIndex() == null ? null : metadataManager.getMetadataForMyOriginalIndex().getPrimaryKeyFieldName();
                    }

                    @Override
                    public String getIndexName() {
                        return indexName;
                    }

                    @Override
                    public String getRightFieldname() {
                        return metadataManager == null ||  metadataManager.getMetadataForMyOriginalIndex() == null ? null : metadataManager.getMetadataForMyOriginalIndex().getPrimaryKeyFieldName();
                    }
                });

        try {
            final StringBuilder newQuery = new StringBuilder(input.length());
            QueryParser parser;
            arrayData = Utils.extractArrayData(input, newQuery);

            parser = new QueryParser(new StringReader(newQuery.toString()));
            tree = parser.parse(true);

            if (extractParentQuery) {
                ASTParent parentQuery = (ASTParent) tree.getChild(ASTParent.class);
                tree.removeNode(parentQuery);
                tree.renumber();
                if (tree.getQueryNode() != null) {
                    tree.getQueryNode().removeNode(parentQuery);
                    tree.getQueryNode().renumber();
                }
            }

            // load index mappings for any index defined in #options()
            metadataManager.loadReferencedMappings(tree.getOptions());

            ASTAggregate aggregate = tree.getAggregate();
            ASTSuggest suggest = tree.getSuggest();
            if (aggregate != null || suggest != null) {
                String fieldname = aggregate != null ? aggregate.getFieldname() : suggest.getFieldname();
                final ASTIndexLink indexLink = metadataManager.findField(fieldname);
                if (indexLink != metadataManager.getMyIndex()) {
                    // change "myIndex" to that of the aggregate/suggest index
                    // so that we properly expand() the queries to do the right things
                    metadataManager.setMyIndex(indexLink);
                }
            }

            // now optimize the _all field into #expand()s, if any are in other indexes
            new IndexLinkOptimizer(tree, metadataManager).optimize();

            rootNode = useParentChild ? tree : tree.getChild(ASTChild.class);
            if (ignoreASTChild) {
                ASTChild child = (ASTChild) tree.getChild(ASTChild.class);
                if (child != null) {
                    ((QueryParserNode) child.parent).removeNode(child);
                    ((QueryParserNode) child.parent).renumber();
                }
            }

        } catch (ParseException pe) {
            throw new QueryRewriteException(pe);
        }
    }

    public String dumpAsString() {
        return tree.dumpAsString();
    }

    public Map<String, ?> describedNestedObject(String fieldname) throws Exception {
        return metadataManager.describedNestedObject(fieldname);
    }

    public QueryBuilder rewriteQuery() {
        try {
            return build(rootNode);
        } finally {
            queryRewritten = true;
        }
    }

    public AbstractAggregationBuilder rewriteAggregations() {
        try {
            _isBuildingAggregate = true;
            return build(tree.getAggregate());
        } finally {
            _isBuildingAggregate = false;
        }
    }

    public boolean isAggregateNested() {
        return tree.getAggregate().isNested();
    }

    public SuggestBuilder.SuggestionBuilder rewriteSuggestions() {
        try {
            _isBuildingAggregate = true;
            return build(tree.getSuggest());
        } finally {
            _isBuildingAggregate = false;
        }
    }

    public String getAggregateIndexName() {
        if (tree.getAggregate() != null)
            return metadataManager.findField(tree.getAggregate().getFieldname()).getIndexName();
        else if (tree.getSuggest() != null)
            return metadataManager.findField(tree.getSuggest().getFieldname()).getIndexName();
        else
            throw new QueryRewriteException("Cannot figure out which index to use for aggregation");
    }

    public String getAggregateFieldName() {
        String fieldname = tree.getAggregate().getFieldname();
        IndexMetadata md = metadataManager.getMetadataForField(fieldname);

        if (fieldname.contains(".")) {
            String base = fieldname.substring(0, fieldname.indexOf('.'));
            if (base.equals(md.getLink().getFieldname()))   // strip base fieldname becase it's in a named index, not a json field
                fieldname = fieldname.substring(fieldname.indexOf('.')+1);
        }

        return fieldname;
    }

    public String getSearchIndexName() {
        if (!queryRewritten)
            throw new IllegalStateException("Must call .rewriteQuery() before calling .getSearchIndexName()");

        if (metadataManager.getUsedIndexes().size() == 1 && allowSingleIndex)
            return metadataManager.getUsedIndexes().iterator().next().getIndexName();
        else
            return metadataManager.getMyIndex().getIndexName();
    }

    private AbstractAggregationBuilder build(ASTAggregate agg) {
        if (agg == null)
            return null;

        AbstractAggregationBuilder ab;

        if (agg instanceof ASTTally)
            ab = build((ASTTally) agg);
        else if (agg instanceof ASTRangeAggregate)
            ab = build((ASTRangeAggregate) agg);
        else if (agg instanceof ASTSignificantTerms)
            ab = build((ASTSignificantTerms) agg);
        else if (agg instanceof ASTExtendedStats)
            ab = build((ASTExtendedStats) agg);
        else
            throw new QueryRewriteException("Unrecognized aggregation type: " + agg.getClass().getName());

        ASTAggregate subagg = agg.getSubAggregate();
        if (subagg != null && ab instanceof AggregationBuilder) {
            if (!metadataManager.getMetadataForField(subagg.getFieldname()).getLink().getIndexName().equals(metadataManager.getMyIndex().getIndexName()))
                throw new QueryRewriteException("Nested aggregates in separate indexes are not supported");

            ((AggregationBuilder) ab).subAggregation(build(subagg));
        }

        if (agg.isNested()) {
            ab = nested("nested").path(agg.getNestedPath())
                    .subAggregation(
                            filter("filter")
                                    .filter(queryFilter(build(tree)))
                                    .subAggregation(ab).subAggregation(missing("missing").field(agg.getFieldname()))
                    );
        }

        return ab;
    }

    private TermSuggestionBuilder build(ASTSuggest agg) {
        if (agg == null)
            return null;

        TermSuggestionBuilder tsb = new TermSuggestionBuilder("suggestions");
        tsb.field(agg.getFieldname());
        tsb.size(agg.getMaxTerms());
        tsb.text(agg.getStem());
        tsb.suggestMode("always");
        tsb.minWordLength(1);
        tsb.shardSize(agg.getMaxTerms() * 10);

        return tsb;
    }

    private AggregationBuilder build(ASTTally agg) {
        String fieldname = agg.getFieldname();
        IndexMetadata md = metadataManager.getMetadataForField(fieldname);

//        fieldname = getAggregateFieldName();

        boolean useHistogram = false;
        if (hasDate(md, fieldname)) {
            try {
                DateHistogramIntervals.valueOf(agg.getStem());
                useHistogram = true;
            } catch (IllegalArgumentException iae) {
                useHistogram = false;
            }
        }

        if (useHistogram) {
            DateHistogramBuilder dhb = dateHistogram(agg.getFieldname())
                    .field(agg.getFieldname() + DateSuffix)
                    .order(stringToDateHistogramOrder(agg.getSortOrder()));

            switch (DateHistogramIntervals.valueOf(agg.getStem())) {
                case year:
                    dhb.interval(DateHistogramInterval.YEAR);
                    dhb.format("yyyy");
                    break;
                case month:
                    dhb.interval(DateHistogramInterval.MONTH);
                    dhb.format("yyyy-MM");
                    break;
                case day:
                    dhb.interval(DateHistogramInterval.DAY);
                    dhb.format("yyyy-MM-dd");
                    break;
                case hour:
                    dhb.interval(DateHistogramInterval.HOUR);
                    dhb.format("yyyy-MM-dd HH");
                    break;
                case minute:
                    dhb.interval(DateHistogramInterval.MINUTE);
                    dhb.format("yyyy-MM-dd HH:mm");
                    break;
                case second:
                    dhb.format("yyyy-MM-dd HH:mm:ss");
                    dhb.interval(DateHistogramInterval.SECOND);
                    break;
                default:
                    throw new QueryRewriteException("Unsupported date histogram interval: " + agg.getStem());
            }

            return dhb;
        } else {
            TermsBuilder tb = terms(agg.getFieldname())
                    .field(fieldname)
                    .size(agg.getMaxTerms())
                    .shardSize(0)
                    .order(stringToTermsOrder(agg.getSortOrder()));

            if ("string".equalsIgnoreCase(md.getType(agg.getFieldname())))
                tb.include(agg.getStem());

            return tb;
        }
    }

    /**
     * Determine if a particular field name is present in the index
     *
     * @param md index metadata
     * @param fieldname field name to check for
     * @return true if this field exists, false otherwise
     */
    private boolean hasDate(final IndexMetadata md, final String fieldname) {
        return md.hasField(fieldname + DateSuffix);
    }

    private static <T> T createRangeSpec(Class<T> type, String value) {
        try {
            ObjectMapper om = new ObjectMapper();
            return om.readValue(value, type);
        } catch (IOException ioe) {
            throw new QueryRewriteException("Problem decoding range spec: " + value, ioe);
        }
    }

    private AggregationBuilder build(ASTRangeAggregate agg) {
        final String fieldname = agg.getFieldname();
        final IndexMetadata md = metadataManager.getMetadataForField(fieldname);

        // if this is a date field, execute a date range aggregation
        if (hasDate(md, fieldname)) {
            final DateRangeBuilder dateRangeBuilder = new DateRangeBuilder(fieldname)
                    .field(fieldname + DateSuffix);

            for (final DateRangeSpecEntry e : createRangeSpec(DateRangeSpecEntry[].class, agg.getRangeSpec())) {
                if (e.to == null && e.from == null)
                    throw new QueryRewriteException("Invalid range spec entry:  one of 'to' or 'from' must be specified");

                if (e.from == null)
                    dateRangeBuilder.addUnboundedTo(e.key, e.to);
                else if (e.to == null)
                    dateRangeBuilder.addUnboundedFrom(e.key, e.from);
                else
                    dateRangeBuilder.addRange(e.key, e.from, e.to);
            }

            return dateRangeBuilder;
        } else {
            // this is not a date field so execute a normal numeric range aggregation
            final RangeBuilder rangeBuilder = new RangeBuilder(fieldname)
                    .field(fieldname);

            for (final RangeSpecEntry e : createRangeSpec(RangeSpecEntry[].class, agg.getRangeSpec())) {
                if (e.to == null && e.from == null)
                    throw new QueryRewriteException("Invalid range spec entry:  one of 'to' or 'from' must be specified");

                if (e.from == null)
                    rangeBuilder.addUnboundedTo(e.key, e.to);
                else if (e.to == null)
                    rangeBuilder.addUnboundedFrom(e.key, e.from);
                else
                    rangeBuilder.addRange(e.key, e.from, e.to);
            }

            return rangeBuilder;
        }
    }

    private AggregationBuilder build(ASTSignificantTerms agg) {
        IndexMetadata md = metadataManager.getMetadataForMyIndex();
        SignificantTermsBuilder stb = significantTerms(agg.getFieldname())
                .field(agg.getFieldname())
                .size(agg.getMaxTerms());

        if ("string".equalsIgnoreCase(md.getType(agg.getFieldname())))
            stb.include(agg.getStem());

        return stb;
    }

    private AbstractAggregationBuilder build(ASTExtendedStats agg) {
        return extendedStats(agg.getFieldname())
                .field(agg.getFieldname());
    }

    private static Terms.Order stringToTermsOrder(String s) {
        switch (s) {
            case "term":
                return Terms.Order.term(true);
            case "count":
                return Terms.Order.count(false);
            case "reverse_term":
                return Terms.Order.term(false);
            case "reverse_count":
                return Terms.Order.count(true);
            default:
                return null;
        }
    }

    private static Histogram.Order stringToDateHistogramOrder(String s) {
        switch (s) {
            case "term":
                return Histogram.Order.KEY_ASC;
            case "count":
                return Histogram.Order.COUNT_ASC;
            case "reverse_term":
                return Histogram.Order.KEY_DESC;
            case "reverse_count":
                return Histogram.Order.COUNT_DESC;
            default:
                return null;
        }
    }

    private QueryBuilder build(QueryParserNode node) {
        if (node == null)
            return null;
        else if (node instanceof ASTChild)
            return build((ASTChild) node);
        else if (node instanceof ASTParent)
            return build((ASTParent) node);
        else if (node instanceof ASTAnd)
            return build((ASTAnd) node);
        else if (node instanceof ASTWith)
            return build((ASTWith) node);
        else if (node instanceof ASTNot)
            return build((ASTNot) node);
        else if (node instanceof ASTOr)
            return build((ASTOr) node);

        return build0(node);
    }

    private QueryBuilder build0(QueryParserNode node) {
        QueryBuilder qb;
        if (node instanceof ASTArray)
            qb = build((ASTArray) node);
        else if (node instanceof ASTArrayData)
            qb = build((ASTArrayData) node);
        else if (node instanceof ASTBoolean)
            qb = build((ASTBoolean) node);
        else if (node instanceof ASTFuzzy)
            qb = build((ASTFuzzy) node);
        else if (node instanceof ASTNotNull)
            qb = build((ASTNotNull) node);
        else if (node instanceof ASTNull)
            qb = build((ASTNull) node);
        else if (node instanceof ASTNumber)
            qb = build((ASTNumber) node);
        else if (node instanceof ASTPhrase)
            qb = build((ASTPhrase) node);
        else if (node instanceof ASTPrefix)
            qb = build((ASTPrefix) node);
        else if (node instanceof ASTProximity)
            qb = build((ASTProximity) node);
        else if (node instanceof ASTQueryTree)
            qb = build((ASTQueryTree) node);
        else if (node instanceof ASTRange)
            qb = build((ASTRange) node);
        else if (node instanceof ASTWildcard)
            qb = build((ASTWildcard) node);
        else if (node instanceof ASTWord)
            qb = build((ASTWord) node);
        else if (node instanceof ASTScript)
            qb = build((ASTScript) node);
        else if (node instanceof ASTExpansion)
            qb = build((ASTExpansion) node);
        else
            throw new QueryRewriteException("Unexpected node type: " + node.getClass().getName());

        maybeBoost(node, qb);

        return qb;
    }

    private void maybeBoost(QueryParserNode node, QueryBuilder qb) {
        if (qb instanceof BoostableQueryBuilder && node.getBoost() != 0.0)
            ((BoostableQueryBuilder) qb).boost(node.getBoost());
    }

    private QueryBuilder build(ASTQueryTree root) throws QueryRewriteException {
        QueryParserNode queryNode = root.getQueryNode();

        if (queryNode == null)
            return matchAllQuery();

        // and build the query
        return build(queryNode);
    }

    private QueryBuilder build(ASTAnd node) {
        BoolQueryBuilder fb = boolQuery();

        for (QueryParserNode child : node) {
            fb.must(build(child));
        }
        return fb;
    }

    private int withDepth = 0;
    private String withNestedPath;
    private QueryBuilder build(ASTWith node) {
        if (withDepth == 0)
            withNestedPath = Utils.validateSameNestedPath(node);

        BoolQueryBuilder fb = boolQuery();

        withDepth++;
        try {
            for (QueryParserNode child : node) {
                fb.must(build(child));
            }
        } finally {
            withDepth--;
        }

        if (withDepth == 0) {
            if (shouldJoinNestedFilter())
                return nestedQuery(withNestedPath, fb);
            else
                return nestedQuery(withNestedPath, fb);
//                return filteredQuery(matchAllQuery(), nestedFilter(withNestedPath, fb).join(false));
        } else {
            return fb;
        }
    }

    private QueryBuilder build(ASTOr node) {
        BoolQueryBuilder fb = boolQuery();

        for (QueryParserNode child : node) {
            fb.should(build(child));
        }
        return fb;
    }

    private QueryBuilder build(ASTNot node) {
        BoolQueryBuilder qb = boolQuery();

        if (_isBuildingAggregate)
            return matchAllQuery();

        for (QueryParserNode child : node) {
            qb.mustNot(build(child));
        }
        return qb;
    }

    private QueryBuilder build(ASTChild node) {
        if (node.hasChildren() && !ignoreASTChild) {
            if (useParentChild) {
                return hasChildQuery(node.getTypename(), build(node.getChild(0)));
            } else {
                return build(node.getChild(0));
            }
        } else {
            return matchAllQuery();
        }
    }

    private QueryBuilder build(ASTParent node) {
        if (_isBuildingAggregate)
            return matchAllQuery();
        else if (node.hasChildren())
            return hasParentQuery(node.getTypename(), build(node.getChild(0)));
        else
            return matchAllQuery();
    }

    private String nested = null;

    private Stack<ASTExpansion> generatedExpansionsStack = new Stack<>();

    private QueryBuilder build(final ASTExpansion node) {
        final ASTIndexLink link = node.getIndexLink();

        try {
            if (node.isGenerated())
                generatedExpansionsStack.push(node);

            return expand(node, link);
        } finally {
            if (node.isGenerated())
                generatedExpansionsStack.pop();
        }
    }

    private QueryBuilder build(ASTWord node) {
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                Object value = n.getValue();
                return termQuery(n.getFieldname(), value);
            }
        });
    }

    private QueryBuilder build(ASTScript node) {
        try {
            return scriptQuery(Script.readScript(new ByteBufferStreamInput(ByteBuffer.wrap(node.getValue().toString().trim().getBytes()))));
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private QueryBuilder build(ASTPhrase node) {
        if (node.getOperator() == QueryParserNode.Operator.REGEX)
            return buildStandard(node, QBF.DUMMY);

        final List<String> tokens = Utils.tokenizePhrase(client, metadataManager, node.getFieldname(), node.getEscapedValue());
        boolean hasWildcards = node.getDistance() > 0 || !node.isOrdered();
        for (int i=0; i<tokens.size(); i++) {
            String token = tokens.get(i);

            token = replaceWildcardTokens(tokens, i, token);

            hasWildcards |= Utils.countValidWildcards(token) > 0;
        }

        for (Iterator<String> itr = tokens.iterator(); itr.hasNext();) {
            String token = itr.next();
            if (token.length() == 1) {
                if (token.equals("\\"))
                    itr.remove();
            }
        }

        if (hasWildcards) {
            if (tokens.size() == 1) {
                return build(Utils.convertToWildcardNode(node.getFieldname(), node.getOperator(), tokens.get(0)));
            } else {

                // convert list of tokens into a proximity query,
                // parse it into a syntax tree
                ASTProximity prox = new ASTProximity(QueryParserTreeConstants.JJTPROXIMITY);
                prox.setFieldname(node.getFieldname());
                prox.distance = node.getDistance();
                prox.ordered = node.isOrdered();

                for (int i=0; i<tokens.size(); i++) {
                    String token = tokens.get(i);

                    prox.jjtAddChild(Utils.convertToWildcardNode(node.getFieldname(), node.getOperator(), token), i);
                }

                return build(prox);
            }
        } else {
            // remove escapes
            for (int i=0; i<tokens.size(); i++) {
                tokens.set(i, Utils.unescape(tokens.get(i)));
            }

            // build proper filters
            if (tokens.size() == 1) {
                // only 1 token, so just return a term filter
                return buildStandard(node, new QBF() {
                    @Override
                    public QueryBuilder b(QueryParserNode n) {
                        return termQuery(n.getFieldname(), n.getValue());
                    }
                });
            } else {
                // more than 1 token, so return a query filter
                return buildStandard(node, new QBF() {
                    @Override
                    public QueryBuilder b(QueryParserNode n) {
                        return matchPhraseQuery(n.getFieldname(), Utils.join(tokens));
                    }
                });
            }
        }

    }

    private String replaceWildcardTokens(List<String> tokens, int i, String token) {
        for (int j=0; j<WILDCARD_TOKENS.length; j++) {
            String wildcard = WILDCARD_TOKENS[j];
            String replacement = WILDCARD_VALUES[j];

            if (token.contains(wildcard)) {
                tokens.set(i, token = token.replaceAll(wildcard, replacement));
            }
        }
        return token;
    }

    private QueryBuilder build(ASTNumber node) {
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return termQuery(n.getFieldname(), n.getValue());
            }
        });
    }

    private void validateOperator(QueryParserNode node) {
        switch (node.getOperator()) {
            case EQ:
            case NE:
            case CONTAINS:
                break;
            default:
                throw new QueryRewriteException("Unsupported operator: " + node.getOperator());
        }
    }

    private QueryBuilder build(ASTNull node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return missingQuery(n.getFieldname());
            }
        });
    }

    private QueryBuilder build(ASTNotNull node) {
        IndexMetadata md = metadataManager.getMetadataForField(node.getFieldname());
        if (md != null && node.getFieldname().equalsIgnoreCase(md.getPrimaryKeyFieldName()))
            return matchAllQuery();    // optimization when we know every document has a value for the specified field

        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return existsQuery(n.getFieldname());
            }
        });
    }

    private QueryBuilder build(ASTBoolean node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return termQuery(n.getFieldname(), n.getValue());
            }
        });
    }

    private QueryBuilder build(final ASTFuzzy node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return fuzzyQuery(n.getFieldname(), n.getValue()).prefixLength(n.getFuzzyness() == 0 ? 3 : n.getFuzzyness());
            }
        });
    }

    private QueryBuilder build(final ASTPrefix node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return prefixQuery(n.getFieldname(), String.valueOf(n.getValue()));
            }
        });
    }

    private QueryBuilder build(final ASTWildcard node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                return wildcardQuery(n.getFieldname(), String.valueOf(n.getValue()));
            }
        });
    }

    private QueryBuilder build(final ASTArray node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                boolean isNE = node.getOperator() == QueryParserNode.Operator.NE;
                final Iterable<Object> itr = node.hasExternalValues() ? node.getExternalValues() : node.getChildValues();
                final int cnt = node.hasExternalValues() ? node.getTotalExternalValues() : node.jjtGetNumChildren();
                int minShouldMatch = (node.isAnd() && !isNE) || (!node.isAnd() && isNE) ? cnt : 1;

                TermsQueryBuilder builder = termsQuery(n.getFieldname(), new AbstractCollection<Object>() {
                    @Override
                    public Iterator<Object> iterator() {
                        return itr.iterator();
                    }

                    @Override
                    public int size() {
                        return cnt;
                    }
                });

                if (minShouldMatch > 1)
                    builder.minimumShouldMatch(minShouldMatch+"");
                return builder;
            }
        });
    }

    private QueryBuilder build(final ASTArrayData node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                final EscapingStringTokenizer st = new EscapingStringTokenizer(arrayData.get(node.value.toString()).toString(), ", \r\n\t\f\"'[]");
                final int size = st.countTokens();

                return termsQuery(n.getFieldname(),
                        new AbstractCollection<String>() {
                            @Override
                            public Iterator<String> iterator() {
                                return new Iterator<String>() {
                                    @Override
                                    public boolean hasNext() {
                                        return st.hasMoreTokens();
                                    }

                                    @Override
                                    public String next() {
                                        return st.nextToken();
                                    }

                                    @Override
                                    public void remove() {

                                    }
                                };
                            }

                            @Override
                            public int size() {
                                return size;
                            }
                        }
                );
            }
        });
    }

    private QueryBuilder build(final ASTRange node) {
        validateOperator(node);
        return buildStandard(node, new QBF() {
            @Override
            public QueryBuilder b(QueryParserNode n) {
                QueryParserNode start = n.getChild(0);
                QueryParserNode end = n.getChild(1);
                return rangeQuery(node.getFieldname()).from(start.getValue()).to(end.getValue());
            }
        });
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, QueryParserNode node) {
        SpanQueryBuilder qb;

        if (node instanceof ASTWord)
            qb = buildSpan(prox, (ASTWord) node);
        else if (node instanceof ASTNumber)
            qb = buildSpan(prox, (ASTNumber) node);
        else if (node instanceof ASTBoolean)
            qb = buildSpan(prox, (ASTBoolean) node);
        else if (node instanceof ASTFuzzy)
            qb = buildSpan(prox, (ASTFuzzy) node);
        else if (node instanceof ASTPrefix)
            qb = buildSpan(prox, (ASTPrefix) node);
        else if (node instanceof ASTWildcard)
            qb = buildSpan(prox, (ASTWildcard) node);
        else if (node instanceof ASTPhrase)
            qb = buildSpan(prox, (ASTPhrase) node);
        else if (node instanceof ASTNull)
            qb = buildSpan(prox, (ASTNull) node);
        else if (node instanceof ASTNotNull)
            return buildSpan(prox, (ASTNotNull) node);
        else if (node instanceof ASTProximity)
            qb = buildSpan((ASTProximity) node);
        else
            throw new QueryRewriteException("Unsupported PROXIMITY node: " + node.getClass().getName());

        maybeBoost(node, qb);
        return qb;
    }

    private SpanQueryBuilder buildSpan(ASTProximity node) {
        SpanNearQueryBuilder qb = spanNearQuery();

        for (QueryParserNode child : node) {
            qb.clause(buildSpan(node, child));
        }

        qb.slop(node.getDistance());
        qb.inOrder(node.isOrdered());
        return qb;
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTWord node) {
        if (prox.getOperator() == QueryParserNode.Operator.REGEX)
            return spanMultiTermQueryBuilder(regexpQuery(node.getFieldname(), node.getEscapedValue()));

        return spanTermQuery(prox.getFieldname(), String.valueOf(node.getValue()));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTNull node) {
        // when building spans, treat 'null' as a regular term
        return spanTermQuery(prox.getFieldname(), String.valueOf(node.getValue()));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTNotNull node) {
        return spanMultiTermQueryBuilder(wildcardQuery(prox.getFieldname(), String.valueOf(node.getValue())));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTNumber node) {
        return spanTermQuery(prox.getFieldname(), String.valueOf(node.getValue()));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTBoolean node) {
        return spanTermQuery(prox.getFieldname(), String.valueOf(node.getValue()));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTFuzzy node) {
        return spanMultiTermQueryBuilder(fuzzyQuery(prox.getFieldname(), node.getValue()).prefixLength(node.getFuzzyness() == 0 ? 3 : node.getFuzzyness()));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTPrefix node) {
        return spanMultiTermQueryBuilder(prefixQuery(prox.getFieldname(), String.valueOf(node.getValue())));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTWildcard node) {
        return spanMultiTermQueryBuilder(wildcardQuery(prox.getFieldname(), String.valueOf(node.getValue())));
    }

    private SpanQueryBuilder buildSpan(ASTProximity prox, ASTPhrase node) {
        if (prox.getOperator() == QueryParserNode.Operator.REGEX)
            return spanMultiTermQueryBuilder(regexpQuery(node.getFieldname(), node.getEscapedValue()));

        final List<String> tokens = Utils.tokenizePhrase(client, metadataManager, node.getFieldname(), node.getEscapedValue());
        for (int i=0; i<tokens.size(); i++) {
            replaceWildcardTokens(tokens, i, tokens.get(i));
        }

        for (Iterator<String> itr = tokens.iterator(); itr.hasNext();) {
            String token = itr.next();
            if (token.length() == 1) {
                if (token.equals("\\"))
                    itr.remove();
            }
        }

        if (tokens.size() == 1) {
            return buildSpan(prox, Utils.convertToWildcardNode(node.getFieldname(), node.getOperator(), tokens.get(0)));
        } else {
            SpanNearQueryBuilder qb = spanNearQuery();
            for (String token : tokens) {
                qb.clause(buildSpan(prox, Utils.convertToWildcardNode(node.getFieldname(), node.getOperator(), token)));
            }
            qb.slop(0);
            qb.inOrder(true);
            return qb;
        }
    }

    private QueryBuilder build(ASTProximity node) {
        node.forceFieldname(node.getFieldname());

        SpanNearQueryBuilder qb = spanNearQuery();
        qb.slop(node.getDistance());
        qb.inOrder(node.isOrdered());

        for (QueryParserNode child : node) {
            qb.clause(buildSpan(node, child));
        }

        return qb;
    }

    private QueryBuilder buildStandard(QueryParserNode node, QBF qbf) {
        return maybeNest(node, buildStandard0(node, qbf));
    }

    private QueryBuilder buildStandard0(QueryParserNode node, QBF qbf) {
        switch (node.getOperator()) {
            case EQ:
            case CONTAINS:
                return qbf.b(node);

            case NE:
                return boolQuery().mustNot(qbf.b(node));

            case LT:
                return rangeQuery(node.getFieldname()).lt(node.getValue());
            case GT:
                return rangeQuery(node.getFieldname()).gt(node.getValue());
            case LTE:
                return rangeQuery(node.getFieldname()).lte(node.getValue());
            case GTE:
                return rangeQuery(node.getFieldname()).gte(node.getValue());

            case REGEX:
                return regexpQuery(node.getFieldname(), node.getEscapedValue());

            case CONCEPT: {
                int minTermFreq = 2;
                // drop the minTermFreq to 1 if we
                // determine that the field being queried is NOT of type "fulltext"
                IndexMetadata md = metadataManager.getMetadataForField(node.getFieldname());
                if (md != null)
                    if (!"fulltext".equalsIgnoreCase(md.getAnalyzer(node.getFieldname())))
                        minTermFreq = 1;

                return moreLikeThisQuery(node.getFieldname()).likeText(String.valueOf(node.getValue())).maxQueryTerms(80).minWordLength(3).minTermFreq(minTermFreq).stopWords(IndexMetadata.MLT_STOP_WORDS);
            }

            case FUZZY_CONCEPT:
                throw new RuntimeException("WTF Elastic");
//                return fuzzyLikeThisFieldQuery(node.getFieldname()).likeText(String.valueOf(node.getValue())).maxQueryTerms(80).fuzziness(Fuzziness.AUTO);

            default:
                throw new QueryRewriteException("Unexpected operator: " + node.getOperator());
        }
    }

    private QueryBuilder maybeNest(QueryParserNode node, QueryBuilder fb) {
        if (withDepth == 0 && node.isNested()) {
            if (shouldJoinNestedFilter())
                return nestedQuery(node.getNestedPath(), fb);
            else
                return nestedQuery(node.getNestedPath(), fb);
//                return filteredQuery(matchAllQuery(), nestedFilter(node.getNestedPath(), fb).join(false));
        } else if (!node.isNested()) {
            if (_isBuildingAggregate)
                return matchAllQuery();
            return fb;  // it's not nested, so just return
        }


        if (nested != null) {
            // we are currently nesting, so make sure this node's path
            // matches the one we're in
            if (node.getNestedPath().equals(nested))
                return fb;  // since we're already nesting, no need to do anything
            else
                throw new QueryRewriteException("Attempt to use nested path '" + node.getNestedPath() + "' inside '" + nested + "'");
        }

        return fb;
    }


    private boolean shouldJoinNestedFilter() {
        return !_isBuildingAggregate || !tree.getAggregate().isNested();
    }

    private QueryBuilder makeParentFilter(ASTExpansion node) {
        if (ignoreASTChild)
            return null;

        ASTIndexLink link = node.getIndexLink();
        IndexMetadata md = metadataManager.getMetadata(link);
        if (md != null && md.getNoXact())
            return null;

        QueryRewriter qr = new QueryRewriter(client, indexName, searchPreference, input, allowSingleIndex, true, true);
        QueryParserNode parentQuery = qr.tree.getChild(ASTParent.class);
        if (parentQuery != null) {
            return queryFilter(build(parentQuery));
        } else {
            return hasParentQuery("xact", qr.rewriteQuery());
        }
    }

    private Stack<ASTExpansion> buildExpansionStack(QueryParserNode root, Stack<ASTExpansion> stack) {

        if (root != null) {
            if (root instanceof ASTExpansion) {
                stack.push((ASTExpansion) root);
                buildExpansionStack(((ASTExpansion) root).getQuery(), stack);
            } else {
                for (QueryParserNode child : root)
                    buildExpansionStack(child, stack);
            }
        }
        return stack;
    }

    private QueryParserNode loadFielddata(ASTExpansion node, String leftFieldname, String rightFieldname) {
        ASTIndexLink link = node.getIndexLink();
        QueryParserNode nodeQuery = node.getQuery();
        IndexMetadata nodeMetadata = metadataManager.getMetadata(link);
        IndexMetadata leftMetadata = metadataManager.getMetadataForField(leftFieldname);
        IndexMetadata rightMetadata = metadataManager.getMetadataForField(rightFieldname);
        boolean isPkey = nodeMetadata != null && leftMetadata != null && rightMetadata != null &&
                nodeMetadata.getPrimaryKeyFieldName().equals(nodeQuery.getFieldname()) && leftMetadata.getPrimaryKeyFieldName().equals(leftFieldname) && rightMetadata.getPrimaryKeyFieldName().equals(rightFieldname);

        if (nodeQuery instanceof ASTNotNull && isPkey) {
            // if the query is a "not null" query against a primary key field and is targeting a primary key field
            // we can just rewrite the query as a "not null" query against the leftFieldname
            // and avoid doing a search at all
            ASTNotNull notNull = new ASTNotNull(QueryParserTreeConstants.JJTNOTNULL);
            notNull.setFieldname(leftFieldname);
            return notNull;
        }

        QueryBuilder nodeFilter = build(nodeQuery);
        SearchRequestBuilder builder = SearchAction.INSTANCE.newRequestBuilder(client)
                .setSize(10240)
                .setQuery(constantScoreQuery(nodeFilter))
                .setIndices(link.getIndexName())
                .setTypes("data")
                .setSearchType(SearchType.SCAN)
                .setScroll(TimeValue.timeValueMinutes(10))
                .addFieldDataField(rightFieldname)
                .setPostFilter(makeParentFilter(node))
                .setPreference(searchPreference);

        ActionFuture<SearchResponse> future = client.search(builder.request());
        try {
            ASTArray array = new ASTArray(QueryParserTreeConstants.JJTARRAY);
            array.setFieldname(leftFieldname);

            SearchResponse response = future != null ? future.get() : null;
            long totalHits = response == null ? -1 : response.getHits().getTotalHits();

            if (response == null || totalHits == 0) {
                return array;
            } else if (response.getFailedShards() > 0) {
                StringBuilder sb = new StringBuilder();
                for (ShardSearchFailure failure : response.getShardFailures()) {
                    sb.append(failure).append("\n");
                }
                throw new QueryRewriteException(response.getFailedShards() + " shards failed:\n" + sb);
            }

            Set<Object> values = new TreeSet<>();
            int cnt = 0;
            while (cnt != totalHits) {
                response = client.searchScroll(SearchScrollAction.INSTANCE.newRequestBuilder(client)
                        .setScrollId(response.getScrollId())
                        .setScroll(TimeValue.timeValueSeconds(10))
                        .request()).get();

                if (response.getTotalShards() != response.getSuccessfulShards())
                    throw new Exception(response.getTotalShards() - response.getSuccessfulShards() + " shards failed");

                SearchHits hits = response.getHits();
                for (SearchHit hit : hits) {
                    List l = hit.field(rightFieldname).getValues();
                    if (l != null)
                        values.addAll(l);
                }
                cnt += hits.hits().length;
            }
            array.setExternalValues(values, cnt);

            return array;
        } catch (Exception e) {
            throw new QueryRewriteException(e);
        }
    }

    private QueryBuilder expand(final ASTExpansion root, final ASTIndexLink link) {
        if (isInTestMode())
            return build(root.getQuery());

        Stack<ASTExpansion> stack = buildExpansionStack(root, new Stack<ASTExpansion>());

        ASTIndexLink myIndex = metadataManager.getMyIndex();
        ASTIndexLink targetIndex = !generatedExpansionsStack.isEmpty() ? root.getIndexLink() : myIndex;
        QueryParserNode last = null;

        if (link.getFieldname() != null)
            IndexLinkOptimizer.stripPath(root, link.getFieldname());

        try {
            while (!stack.isEmpty()) {
                ASTExpansion expansion = stack.pop();
                String expansionFieldname = expansion.getFieldname();

                if (expansionFieldname == null)
                    expansionFieldname = expansion.getIndexLink().getRightFieldname();

                if (generatedExpansionsStack.isEmpty() && expansion.getIndexLink() == myIndex) {
                    last = expansion.getQuery();
                } else {
                    String leftFieldname;
                    String rightFieldname;

                    if (expansion.isGenerated()) {

                        if (last == null) {
                            last = loadFielddata(expansion, expansion.getIndexLink().getLeftFieldname(), expansion.getIndexLink().getRightFieldname());
                        }

                        // at this point 'expansion' represents the set of records that match the #expand<>(...)'s subquery
                        // all of which are targeted towards the index that contains the #expand's <fieldname>

                        // the next step is to turn them into a set of 'expansionField' values
                        // then turn that around into a set of ids against myIndex, if the expansionField is not in myIndex
                        last = loadFielddata(expansion, expansion.getIndexLink().getLeftFieldname(), expansion.getIndexLink().getRightFieldname());

                        ASTIndexLink expansionSourceIndex = metadataManager.findField(expansionFieldname);
                        if (expansionSourceIndex != myIndex) {
                            // replace the ASTExpansion in the tree with the fieldData version
                            expansion.jjtAddChild(last, 1);

                            String targetPkey = myIndex.getRightFieldname();
                            String sourcePkey = metadataManager.getMetadata(expansion.getIndexLink().getIndexName()).getPrimaryKeyFieldName();

                            leftFieldname = targetPkey;
                            rightFieldname = sourcePkey;

                            last = loadFielddata(expansion, leftFieldname, rightFieldname);
                        }
                    } else {

                        List<String> path = metadataManager.calculatePath(targetIndex, expansion.getIndexLink());

                        boolean oneToOne = true;
                        if (path.size() == 2) {
                            leftFieldname = path.get(0);
                            rightFieldname = path.get(1);

                            leftFieldname = leftFieldname.substring(leftFieldname.indexOf(':') + 1);
                            rightFieldname = rightFieldname.substring(rightFieldname.indexOf(':') + 1);

                        } else if (path.size() == 3) {
                            oneToOne = false;
                            String middleFieldname;

                            leftFieldname = path.get(0);
                            middleFieldname = path.get(1);
                            rightFieldname = path.get(2);

                            if (metadataManager.areFieldPathsEquivalent(leftFieldname, middleFieldname) && metadataManager.areFieldPathsEquivalent(middleFieldname, rightFieldname)) {
                                leftFieldname = leftFieldname.substring(leftFieldname.indexOf(':') + 1);
                                rightFieldname = rightFieldname.substring(rightFieldname.indexOf(':') + 1);
                            } else {
                                throw new QueryRewriteException("Field equivalency cannot be determined");
                            }
                        } else {
                            // although I think we can with a while() loop that keeps resolving field data with each
                            // node in the path
                            throw new QueryRewriteException("Don't know how to resolve multiple levels of indirection");
                        }

                        if (oneToOne && metadataManager.getUsedIndexes().size() == 1 && allowSingleIndex) {
                            last = expansion.getQuery();
                        } else {
                            last = loadFielddata(expansion, leftFieldname, rightFieldname);
                        }
                    }
                }

                // replace the ASTExpansion in the tree with the fieldData version
                ((QueryParserNode) expansion.parent).replaceChild(expansion, last);
            }
        } finally {
            metadataManager.setMyIndex(myIndex);
        }

        return build(last);
    }

    protected boolean isInTestMode() {
        return false;
    }
}
