/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.core.table.record;

import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.Event;
import io.siddhi.core.event.state.MetaStateEvent;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.state.StateEventFactory;
import io.siddhi.core.event.state.populater.StateEventPopulatorFactory;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.executor.ConstantExpressionExecutor;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.query.processor.ProcessingMode;
import io.siddhi.core.query.processor.stream.window.QueryableProcessor;
import io.siddhi.core.query.selector.QuerySelector;
import io.siddhi.core.table.InMemoryTable;
import io.siddhi.core.table.Table;
import io.siddhi.core.util.SiddhiConstants;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.CompiledSelection;
import io.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.parser.ExpressionParser;
import io.siddhi.core.util.parser.SelectorParser;
import io.siddhi.core.util.parser.helper.QueryParserHelper;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.TableDefinition;
import io.siddhi.query.api.execution.query.StoreQuery;
import io.siddhi.query.api.execution.query.input.store.InputStore;
import io.siddhi.query.api.execution.query.output.stream.OutputStream;
import io.siddhi.query.api.execution.query.output.stream.ReturnStream;
import io.siddhi.query.api.execution.query.selection.OrderByAttribute;
import io.siddhi.query.api.execution.query.selection.OutputAttribute;
import io.siddhi.query.api.execution.query.selection.Selector;
import io.siddhi.query.api.expression.Expression;
import io.siddhi.query.api.expression.Variable;
import io.siddhi.query.compiler.SiddhiCompiler;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.siddhi.core.util.StoreQueryRuntimeUtil.executeSelector;
import static io.siddhi.core.util.parser.StoreQueryParser.buildExpectedOutputAttributes;
import static io.siddhi.core.util.parser.StoreQueryParser.generateMatchingMetaInfoHolderForCacheTable;

/**
 * An abstract implementation of table. Abstract implementation will handle {@link ComplexEventChunk} so that
 * developer can directly work with event data.
 */
public abstract class AbstractQueryableRecordTable extends AbstractRecordTable implements QueryableProcessor {

    private static final Logger log = Logger.getLogger(AbstractQueryableRecordTable.class);
    private int cacheSize;
    private InMemoryTable cachedTable;
    private Boolean isCacheEnabled = Boolean.FALSE;
    private CompiledCondition compiledConditionForCaching;
    private CompiledSelection compiledSelectionForCaching;
    private Attribute[] outputAttributesForCaching;

    @Override
    public void init(TableDefinition tableDefinition, SiddhiAppContext siddhiAppContext,
                     StreamEventCloner storeEventCloner, ConfigReader configReader) {
        this.tableDefinition = tableDefinition;

        if (!tableDefinition.getAnnotations().get(0).getAnnotations("Cache").isEmpty()) {
            isCacheEnabled = Boolean.TRUE;
            cacheSize = Integer.parseInt(tableDefinition.getAnnotations().get(0).getAnnotations("Cache").
                    get(0).getElement("Size"));

            cachedTable = new InMemoryTable(); //todo: add another column for expiry - timestamp
            cachedTable.initTable(generateCacheTableDefinition(tableDefinition), storeEventPool,
                    storeEventCloner, configReader, siddhiAppContext, recordTableHandler);
            //todo: ask suho how to get subpart of store table defn

            String queryName = "table_cache" + tableDefinition.getId();
            SiddhiQueryContext siddhiQueryContext = new SiddhiQueryContext(siddhiAppContext, queryName);

            Expression onCondition = Expression.value(true);

            MatchingMetaInfoHolder matchingMetaInfoHolder =
                    generateMatchingMetaInfoHolderForCacheTable(tableDefinition);

            StoreQuery storeQuery = StoreQuery.query().
                    from(
                            InputStore.store(tableDefinition.getId())).
                    select(
                            Selector.selector().
                                    limit(Expression.value((cacheSize + 1)))
                    );

            List<VariableExpressionExecutor> variableExpressionExecutors = new ArrayList<>();

            compiledConditionForCaching = compileCondition(onCondition, matchingMetaInfoHolder,
                    variableExpressionExecutors, tableMap, siddhiQueryContext);

            List<Attribute> expectedOutputAttributes = buildExpectedOutputAttributes(storeQuery,
                    tableMap, SiddhiConstants.UNKNOWN_STATE, matchingMetaInfoHolder, siddhiQueryContext);

            compiledSelectionForCaching = compileSelection(storeQuery.getSelector(), expectedOutputAttributes,
                    matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);

            outputAttributesForCaching = expectedOutputAttributes.toArray(new Attribute[0]);
        }
    }

    private TableDefinition generateCacheTableDefinition(TableDefinition tableDefinition) {
        StringBuilder defineCache = new StringBuilder("define table ");
        defineCache.append(tableDefinition.getId()).append(" (");

        for (Attribute attribute: tableDefinition.getAttributeList()) {
            defineCache.append(attribute.getName()).append(" ").append(attribute.getType().name().toLowerCase()).
                    append(", ");
        }
        defineCache = new StringBuilder(defineCache.substring(0, defineCache.length() - 2));
        defineCache.append("); ");

        return SiddhiCompiler.parseTableDefinition(defineCache.toString());
    }

    @Override
    protected void connectAndLoadCache() throws ConnectionUnavailableException {
        connect();

        if (isCacheEnabled) {
            StateEvent stateEvent = new StateEvent(1, 0);
            StreamEvent preLoadedData = query(stateEvent, compiledConditionForCaching, compiledSelectionForCaching,
                    outputAttributesForCaching);

            int preLoadedDataSize;
            if (preLoadedData != null) {
                preLoadedDataSize = 1;
                StreamEvent preLoadedDataCopy = preLoadedData;

                while (preLoadedDataCopy.getNext() != null) {
                    preLoadedDataSize = preLoadedDataSize + 1;
                    preLoadedDataCopy = preLoadedDataCopy.getNext();
                }

                if (preLoadedDataSize <= cacheSize) {
                    ComplexEventChunk<StreamEvent> loadedCache = new ComplexEventChunk<>();
                    loadedCache.add(preLoadedData);

                    cachedTable.addEvents(loadedCache, preLoadedDataSize);
                } else {
                    log.warn("Table size bigger than cache table size. So cache is left empty");
                }
            }
        }
    }

    protected CompiledCondition generateCacheCompileCondition(Expression condition,
                                                              MatchingMetaInfoHolder storeMatchingMetaInfoHolder,
                                                              SiddhiQueryContext siddhiQueryContext,
                                                              List<VariableExpressionExecutor>
                                                                      storeVariableExpressionExecutors,
                                                              Map<String, Table> storeTableMap) {
        if (isCacheEnabled) {
            MetaStateEvent metaStateEvent = new MetaStateEvent(storeMatchingMetaInfoHolder.getMetaStateEvent().
                    getMetaStreamEvents().length);
            for (MetaStreamEvent referenceMetaStreamEvent: storeMatchingMetaInfoHolder.getMetaStateEvent().
                    getMetaStreamEvents()) {
                metaStateEvent.addEvent(referenceMetaStreamEvent);
            }
            MatchingMetaInfoHolder matchingMetaInfoHolder = new MatchingMetaInfoHolder(
                    metaStateEvent,
                    storeMatchingMetaInfoHolder.getMatchingStreamEventIndex(),
                    storeMatchingMetaInfoHolder.getStoreEventIndex(),
                    storeMatchingMetaInfoHolder.getMatchingStreamDefinition(),
                    tableDefinition,
                    storeMatchingMetaInfoHolder.getCurrentState());
            if (siddhiQueryContext.getName().startsWith("store_select_query_")) {
                metaStateEvent.getMetaStreamEvent(0).setEventType(MetaStreamEvent.EventType.TABLE);
            }

            Map<String, Table> tableMap = new ConcurrentHashMap<>();
            tableMap.put(tableDefinition.getId(), cachedTable);

            return cachedTable.compileCondition(condition, matchingMetaInfoHolder,
                    storeVariableExpressionExecutors, tableMap, siddhiQueryContext);
        } else {
            return null;
        }
    }

    protected abstract void connect() throws ConnectionUnavailableException;

    @Override
    public StreamEvent query(StateEvent matchingEvent, CompiledCondition compiledCondition,
                             CompiledSelection compiledSelection) throws ConnectionUnavailableException {
        return query(matchingEvent, compiledCondition, compiledSelection, null);
    }

    public StreamEvent findInCache(CompiledCondition compiledCondition, StateEvent matchingEvent) {
        if (isCacheEnabled) {
            return cachedTable.find(matchingEvent, compiledCondition);
        } else {
            return null;
        }

    }

    @Override
    public CompiledCondition compileCondition(Expression condition, MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {
        ExpressionBuilder expressionBuilder = new ExpressionBuilder(condition, matchingMetaInfoHolder,
                variableExpressionExecutors, tableMap, siddhiQueryContext);
        CompiledCondition compileCondition = compileCondition(expressionBuilder);
        Map<String, ExpressionExecutor> expressionExecutorMap = expressionBuilder.getVariableExpressionExecutorMap();

        if (isCacheEnabled) {

            CompiledCondition cacheCompileCondition = generateCacheCompileCondition(condition, matchingMetaInfoHolder,
                    siddhiQueryContext, variableExpressionExecutors, tableMap);

            CompiledCondition compiledConditionAggregation = new CompiledConditionWithCache(compileCondition,
                    cacheCompileCondition);

            return new RecordStoreCompiledCondition(expressionExecutorMap, compiledConditionAggregation);
        } else {
            return new RecordStoreCompiledCondition(expressionExecutorMap, compileCondition);
        }
    }

    /**
     * Class to hold store compile condition and cache compile condition wrapped
     */
    public class CompiledConditionWithCache implements CompiledCondition {

        CompiledCondition storeCompileCondition;
        CompiledCondition cacheCompileCondition;



        public CompiledConditionWithCache(CompiledCondition storeCompileCondition,
                                          CompiledCondition cacheCompileCondition) {
            this.storeCompileCondition = storeCompileCondition;
            this.cacheCompileCondition = cacheCompileCondition;
        }

        public CompiledCondition getStoreCompileCondition() {
            return storeCompileCondition;
        }

        public CompiledCondition getCacheCompileCondition() {
            return cacheCompileCondition;
        }
    }

    @Override
    public StreamEvent find(CompiledCondition compiledCondition, StateEvent matchingEvent)
            throws ConnectionUnavailableException {
        RecordStoreCompiledCondition recordStoreCompiledCondition;
        if (isCacheEnabled) {

            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            CompiledConditionWithCache compiledConditionAggregation = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(compiledConditionTemp.
                    variableExpressionExecutorMap, compiledConditionAggregation.getStoreCompileCondition());

            StreamEvent cacheResults = findInCache(compiledConditionAggregation.getCacheCompileCondition(),
                    matchingEvent);
            if (cacheResults != null) {
                return cacheResults;
            }
        } else {
            recordStoreCompiledCondition =
                    ((RecordStoreCompiledCondition) compiledCondition);
        }

        Map<String, Object> findConditionParameterMap = new HashMap<>();
        for (Map.Entry<String, ExpressionExecutor> entry : recordStoreCompiledCondition.variableExpressionExecutorMap
                .entrySet()) {
            findConditionParameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }

        Iterator<Object[]> records;
        if (recordTableHandler != null) {
            records = recordTableHandler.find(matchingEvent.getTimestamp(), findConditionParameterMap,
                    recordStoreCompiledCondition.compiledCondition);
        } else {
            records = find(findConditionParameterMap, recordStoreCompiledCondition.compiledCondition);
        }
        ComplexEventChunk<StreamEvent> streamEventComplexEventChunk = new ComplexEventChunk<>(true);
        if (records != null) {
            while (records.hasNext()) {
                Object[] record = records.next();
                StreamEvent streamEvent = storeEventPool.newInstance();
                System.arraycopy(record, 0, streamEvent.getOutputData(), 0, record.length);
                streamEventComplexEventChunk.add(streamEvent);
            }
        }
        return streamEventComplexEventChunk.getFirst();

    }

    @Override
    public StreamEvent query(StateEvent matchingEvent, CompiledCondition compiledCondition,
                             CompiledSelection compiledSelection, Attribute[] outputAttributes)
            throws ConnectionUnavailableException {
        //todo: override delete funcs from abstractrecordtable and delete from cache table and record table

        ComplexEventChunk<StreamEvent> streamEventComplexEventChunk = new ComplexEventChunk<>(true);

        RecordStoreCompiledCondition recordStoreCompiledCondition;
        RecordStoreCompiledSelection recordStoreCompiledSelection;

        if (isCacheEnabled) {
            RecordStoreCompiledCondition compiledConditionTemp = (RecordStoreCompiledCondition) compiledCondition;
            CompiledConditionWithCache compiledConditionWithCache = (CompiledConditionWithCache)
                    compiledConditionTemp.compiledCondition;
            recordStoreCompiledCondition = new RecordStoreCompiledCondition(
                    compiledConditionTemp.variableExpressionExecutorMap,
                    compiledConditionWithCache.getStoreCompileCondition());

            CompiledSelectionWithCache compiledSelectionWithCache = (CompiledSelectionWithCache) compiledSelection;
            recordStoreCompiledSelection = compiledSelectionWithCache.recordStoreCompiledSelection;
            StreamEvent cacheResults = findInCache(compiledConditionWithCache.getCacheCompileCondition(),
                    matchingEvent);
            if (cacheResults != null) {
                StateEventFactory stateEventFactory = new StateEventFactory(compiledSelectionWithCache.
                        metaStreamInfoHolder.getMetaStateEvent());
                Event[] cacheResultsAfterSelection = executeSelector(cacheResults,
                        compiledSelectionWithCache.querySelector,
                        stateEventFactory, MetaStreamEvent.EventType.TABLE);
                assert cacheResultsAfterSelection != null;
                for (Event event : cacheResultsAfterSelection) {

                    Object[] record = event.getData();
                    StreamEvent streamEvent = storeEventPool.newInstance();
                    streamEvent.setOutputData(new Object[outputAttributes.length]);
                    System.arraycopy(record, 0, streamEvent.getOutputData(), 0, record.length);
                    streamEventComplexEventChunk.add(streamEvent);
                }
                return streamEventComplexEventChunk.getFirst();
            }

        } else {
            recordStoreCompiledSelection = ((RecordStoreCompiledSelection) compiledSelection);
            recordStoreCompiledCondition = ((RecordStoreCompiledCondition) compiledCondition);
        }

        Map<String, Object> parameterMap = new HashMap<>();
        for (Map.Entry<String, ExpressionExecutor> entry :
                recordStoreCompiledCondition.variableExpressionExecutorMap.entrySet()) {
            parameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }
        for (Map.Entry<String, ExpressionExecutor> entry :
                recordStoreCompiledSelection.variableExpressionExecutorMap.entrySet()) {
            parameterMap.put(entry.getKey(), entry.getValue().execute(matchingEvent));
        }

        Iterator<Object[]> records;
        if (recordTableHandler != null) {
            records = recordTableHandler.query(matchingEvent.getTimestamp(), parameterMap,
                    recordStoreCompiledCondition.compiledCondition,
                    recordStoreCompiledSelection.compiledSelection);
        } else {
            records = query(parameterMap, recordStoreCompiledCondition.compiledCondition,
                    recordStoreCompiledSelection.compiledSelection, outputAttributes);
        }
        if (records != null) {
            while (records.hasNext()) {
                Object[] record = records.next();
                StreamEvent streamEvent = storeEventPool.newInstance();
                streamEvent.setOutputData(new Object[outputAttributes.length]);
                System.arraycopy(record, 0, streamEvent.getOutputData(), 0, record.length);
                streamEventComplexEventChunk.add(streamEvent);
            }
        }
        return streamEventComplexEventChunk.getFirst();
    }


    /**
     * Query records matching the compiled condition and selection
     *
     * @param parameterMap      map of matching StreamVariable Ids and their values
     *                          corresponding to the compiled condition and selection
     * @param compiledCondition the compiledCondition against which records should be matched
     * @param compiledSelection the compiledSelection that maps records based to requested format
     * @return RecordIterator of matching records
     * @throws ConnectionUnavailableException
     */
    protected abstract RecordIterator<Object[]> query(Map<String, Object> parameterMap,
                                                      CompiledCondition compiledCondition,
                                                      CompiledSelection compiledSelection,
                                                      Attribute[] outputAttributes)
            throws ConnectionUnavailableException;

    /**
     * class to hold both store compile selection and cache compile selection wrapped
     */
    public class CompiledSelectionWithCache implements CompiledSelection {

        RecordStoreCompiledSelection recordStoreCompiledSelection;

        public QuerySelector getQuerySelector() {
            return querySelector;
        }

        QuerySelector querySelector;
        MatchingMetaInfoHolder metaStreamInfoHolder;



        CompiledSelectionWithCache(RecordStoreCompiledSelection recordStoreCompiledSelection,
                                   QuerySelector querySelector,
                                   MatchingMetaInfoHolder metaStreamInfoHolder) {
            this.recordStoreCompiledSelection = recordStoreCompiledSelection;
            this.querySelector = querySelector;
            this.metaStreamInfoHolder = metaStreamInfoHolder;
        }
    }



    public CompiledSelection compileSelection(Selector selector,
                                              List<Attribute> expectedOutputAttributes,
                                              MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {
        List<OutputAttribute> outputAttributes = selector.getSelectionList();
        if (outputAttributes.size() == 0) {
            MetaStreamEvent metaStreamEvent = matchingMetaInfoHolder.getMetaStateEvent().getMetaStreamEvent(
                    matchingMetaInfoHolder.getStoreEventIndex());
            List<Attribute> attributeList = metaStreamEvent.getLastInputDefinition().getAttributeList();
            for (Attribute attribute : attributeList) {
                outputAttributes.add(new OutputAttribute(new Variable(attribute.getName())));
            }
        }
        List<SelectAttributeBuilder> selectAttributeBuilders = new ArrayList<>(outputAttributes.size());
        for (OutputAttribute outputAttribute : outputAttributes) {
            ExpressionBuilder expressionBuilder = new ExpressionBuilder(outputAttribute.getExpression(),
                    matchingMetaInfoHolder, variableExpressionExecutors, tableMap, siddhiQueryContext);
            selectAttributeBuilders.add(new SelectAttributeBuilder(expressionBuilder, outputAttribute.getRename()));
        }

        MatchingMetaInfoHolder metaInfoHolderAfterSelect = new MatchingMetaInfoHolder(
                matchingMetaInfoHolder.getMetaStateEvent(), matchingMetaInfoHolder.getMatchingStreamEventIndex(),
                matchingMetaInfoHolder.getStoreEventIndex(), matchingMetaInfoHolder.getMatchingStreamDefinition(),
                matchingMetaInfoHolder.getMatchingStreamDefinition(), matchingMetaInfoHolder.getCurrentState());

        List<ExpressionBuilder> groupByExpressionBuilders = null;
        if (selector.getGroupByList().size() != 0) {
            groupByExpressionBuilders = new ArrayList<>(outputAttributes.size());
            for (Variable variable : selector.getGroupByList()) {
                groupByExpressionBuilders.add(new ExpressionBuilder(variable, metaInfoHolderAfterSelect,
                        variableExpressionExecutors, tableMap, siddhiQueryContext));
            }
        }

        ExpressionBuilder havingExpressionBuilder = null;
        if (selector.getHavingExpression() != null) {
            havingExpressionBuilder = new ExpressionBuilder(selector.getHavingExpression(), metaInfoHolderAfterSelect,
                    variableExpressionExecutors, tableMap, siddhiQueryContext);
        }

        List<OrderByAttributeBuilder> orderByAttributeBuilders = null;
        if (selector.getOrderByList().size() != 0) {
            orderByAttributeBuilders = new ArrayList<>(selector.getOrderByList().size());
            for (OrderByAttribute orderByAttribute : selector.getOrderByList()) {
                ExpressionBuilder expressionBuilder = new ExpressionBuilder(orderByAttribute.getVariable(),
                        metaInfoHolderAfterSelect, variableExpressionExecutors,
                        tableMap, siddhiQueryContext);
                orderByAttributeBuilders.add(new OrderByAttributeBuilder(expressionBuilder,
                        orderByAttribute.getOrder()));
            }
        }

        Long limit = null;
        Long offset = null;
        if (selector.getLimit() != null) {
            ExpressionExecutor expressionExecutor = ExpressionParser.parseExpression((Expression) selector.getLimit(),
                    metaInfoHolderAfterSelect.getMetaStateEvent(), SiddhiConstants.HAVING_STATE, tableMap,
                    variableExpressionExecutors, false, 0,
                    ProcessingMode.BATCH, false, siddhiQueryContext);
            limit = ((Number) (((ConstantExpressionExecutor) expressionExecutor).getValue())).longValue();
            if (limit < 0) {
                throw new SiddhiAppCreationException("'limit' cannot have negative value, but found '" + limit + "'",
                        selector, siddhiQueryContext.getSiddhiAppContext());
            }
        }
        if (selector.getOffset() != null) {
            ExpressionExecutor expressionExecutor = ExpressionParser.parseExpression((Expression) selector.getOffset(),
                    metaInfoHolderAfterSelect.getMetaStateEvent(), SiddhiConstants.HAVING_STATE, tableMap,
                    variableExpressionExecutors, false, 0,
                    ProcessingMode.BATCH, false, siddhiQueryContext);
            offset = ((Number) (((ConstantExpressionExecutor) expressionExecutor).getValue())).longValue();
            if (offset < 0) {
                throw new SiddhiAppCreationException("'offset' cannot have negative value, but found '" + offset + "'",
                        selector, siddhiQueryContext.getSiddhiAppContext());
            }
        }
        CompiledSelection compiledSelection = compileSelection(selectAttributeBuilders, groupByExpressionBuilders,
                havingExpressionBuilder, orderByAttributeBuilders, limit, offset);

        Map<String, ExpressionExecutor> expressionExecutorMap = new HashMap<>();
        if (selectAttributeBuilders.size() != 0) {
            for (SelectAttributeBuilder selectAttributeBuilder : selectAttributeBuilders) {
                expressionExecutorMap.putAll(
                        selectAttributeBuilder.getExpressionBuilder().getVariableExpressionExecutorMap());
            }
        }
        if (groupByExpressionBuilders != null && groupByExpressionBuilders.size() != 0) {
            for (ExpressionBuilder groupByExpressionBuilder : groupByExpressionBuilders) {
                expressionExecutorMap.putAll(groupByExpressionBuilder.getVariableExpressionExecutorMap());
            }
        }
        if (havingExpressionBuilder != null) {
            expressionExecutorMap.putAll(havingExpressionBuilder.getVariableExpressionExecutorMap());
        }
        if (orderByAttributeBuilders != null && orderByAttributeBuilders.size() != 0) {
            for (OrderByAttributeBuilder orderByAttributeBuilder : orderByAttributeBuilders) {
                expressionExecutorMap.putAll(
                        orderByAttributeBuilder.getExpressionBuilder().getVariableExpressionExecutorMap());
            }
        }

        if (isCacheEnabled) {
            CompiledSelectionWithCache compiledSelectionWithCache;

            ReturnStream returnStream = new ReturnStream(OutputStream.OutputEventType.CURRENT_EVENTS);
            int metaPosition = SiddhiConstants.UNKNOWN_STATE;
            List<VariableExpressionExecutor> variableExpressionExecutorsForQuerySelector = new ArrayList<>();

            QuerySelector querySelector = SelectorParser.parse(selector,
                    returnStream,
                    matchingMetaInfoHolder.getMetaStateEvent(), tableMap, variableExpressionExecutorsForQuerySelector,
                    metaPosition, ProcessingMode.BATCH, false, siddhiQueryContext);

            QueryParserHelper.updateVariablePosition(matchingMetaInfoHolder.getMetaStateEvent(),
                    variableExpressionExecutorsForQuerySelector);

            querySelector.setEventPopulator(
                    StateEventPopulatorFactory.constructEventPopulator(matchingMetaInfoHolder.getMetaStateEvent()));

            RecordStoreCompiledSelection recordStoreCompiledSelection =
                    new RecordStoreCompiledSelection(expressionExecutorMap, compiledSelection);

            compiledSelectionWithCache = new CompiledSelectionWithCache(recordStoreCompiledSelection, querySelector,
                    matchingMetaInfoHolder);
            return compiledSelectionWithCache;
        } else {
            return  new RecordStoreCompiledSelection(expressionExecutorMap, compiledSelection);
        }
    }

    /**
     * Compile the query selection
     *
     * @param selectAttributeBuilders  helps visiting the select attributes in order
     * @param groupByExpressionBuilder helps visiting the group by attributes in order
     * @param havingExpressionBuilder  helps visiting the having condition
     * @param orderByAttributeBuilders helps visiting the order by attributes in order
     * @param limit                    defines the limit level
     * @param offset                   defines the offset level
     * @return compiled selection that can be used for retrieving events on a defined format
     */
    protected abstract CompiledSelection compileSelection(List<SelectAttributeBuilder> selectAttributeBuilders,
                                                          List<ExpressionBuilder> groupByExpressionBuilder,
                                                          ExpressionBuilder havingExpressionBuilder,
                                                          List<OrderByAttributeBuilder> orderByAttributeBuilders,
                                                          Long limit, Long offset);


    private class RecordStoreCompiledSelection implements CompiledSelection {


        private final Map<String, ExpressionExecutor> variableExpressionExecutorMap;
        private final CompiledSelection compiledSelection;

        RecordStoreCompiledSelection(Map<String, ExpressionExecutor> variableExpressionExecutorMap,
                                     CompiledSelection compiledSelection) {

            this.variableExpressionExecutorMap = variableExpressionExecutorMap;
            this.compiledSelection = compiledSelection;
        }

    }

    /**
     * Holder of Selection attribute with renaming field
     */
    public class SelectAttributeBuilder {
        private final ExpressionBuilder expressionBuilder;
        private final String rename;

        public SelectAttributeBuilder(ExpressionBuilder expressionBuilder, String rename) {
            this.expressionBuilder = expressionBuilder;
            this.rename = rename;
        }

        public ExpressionBuilder getExpressionBuilder() {
            return expressionBuilder;
        }

        public String getRename() {
            return rename;
        }
    }

    /**
     * Holder of order by attribute with order orientation
     */
    public class OrderByAttributeBuilder {
        private final ExpressionBuilder expressionBuilder;
        private final OrderByAttribute.Order order;

        public OrderByAttributeBuilder(ExpressionBuilder expressionBuilder, OrderByAttribute.Order order) {
            this.expressionBuilder = expressionBuilder;
            this.order = order;
        }

        public ExpressionBuilder getExpressionBuilder() {
            return expressionBuilder;
        }

        public OrderByAttribute.Order getOrder() {
            return order;
        }
    }


}
