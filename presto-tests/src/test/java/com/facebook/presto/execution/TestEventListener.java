/*
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
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.execution.TestEventListenerPlugin.TestingEventListenerPlugin;
import com.facebook.presto.resourceGroups.ResourceGroupManagerPlugin;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.eventlistener.QueryCompletedEvent;
import com.facebook.presto.spi.eventlistener.QueryCreatedEvent;
import com.facebook.presto.spi.eventlistener.QueryProgressEvent;
import com.facebook.presto.spi.eventlistener.SplitCompletedEvent;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.tests.DistributedQueryRunner;
import com.facebook.presto.tpch.TpchPlugin;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.execution.TestQueues.createResourceGroupId;
import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static com.facebook.presto.utils.ResourceUtils.getResourceFilePath;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.util.stream.Collectors.toSet;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestEventListener
{
    private static final int SPLITS_PER_NODE = 3;
    private final EventsBuilder generatedEvents = new EventsBuilder();

    private DistributedQueryRunner queryRunner;
    private Session session;
    private long lastSeenQueryProgressEventId;

    @BeforeClass
    private void setUp()
            throws Exception
    {
        session = testSessionBuilder()
                .setSystemProperty("task_concurrency", "1")
                .setCatalog("tpch")
                .setSchema("tiny")
                .setClientInfo("{\"clientVersion\":\"testVersion\"}")
                .build();

        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("event.query-progress-publish-interval", "1ms")
                .build();

        queryRunner = new DistributedQueryRunner(session, 1, properties);
        queryRunner.installPlugin(new TpchPlugin());
        queryRunner.installPlugin(new TestingEventListenerPlugin(generatedEvents));
        queryRunner.installPlugin(new ResourceGroupManagerPlugin());
        queryRunner.createCatalog("tpch", "tpch", ImmutableMap.of("tpch.splits-per-node", Integer.toString(SPLITS_PER_NODE)));
        queryRunner.getCoordinator().getResourceGroupManager().get()
                .forceSetConfigurationManager("file", ImmutableMap.of("resource-groups.config-file", getResourceFilePath("resource_groups_config_simple.json")));
    }

    @AfterClass(alwaysRun = true)
    private void tearDown()
    {
        queryRunner.close();
        queryRunner = null;
    }

    private MaterializedResult runQueryAndWaitForEvents(@Language("SQL") String sql, int numEventsExpected)
            throws Exception
    {
        return runQueryAndWaitForEvents(sql, numEventsExpected, session);
    }

    private MaterializedResult runQueryAndWaitForEvents(@Language("SQL") String sql, int numEventsExpected, Session alternateSession)
            throws Exception
    {
        generatedEvents.initialize(numEventsExpected);
        MaterializedResult result = queryRunner.execute(alternateSession, sql);
        generatedEvents.waitForEvents(10);

        return result;
    }

    @Test
    public void testConstantQuery()
            throws Exception
    {
        // QueryCreated: 1, QueryProgressEvent:1, QueryCompleted: 1, Splits: 1
        runQueryAndWaitForEvents("SELECT 1", 4);

        QueryCreatedEvent queryCreatedEvent = getOnlyElement(generatedEvents.getQueryCreatedEvents());
        assertEquals(queryCreatedEvent.getContext().getServerVersion(), "testversion");
        assertEquals(queryCreatedEvent.getContext().getServerAddress(), "127.0.0.1");
        assertEquals(queryCreatedEvent.getContext().getEnvironment(), "testing");
        assertEquals(queryCreatedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQuery(), "SELECT 1");
        assertFalse(queryCreatedEvent.getMetadata().getPreparedQuery().isPresent());

        QueryProgressEvent queryProgressEvent = generatedEvents.getQueryProgressEvent();
        assertNotNull(queryProgressEvent);
        assertTrue(queryProgressEvent.getMonotonicallyIncreasingEventId() > lastSeenQueryProgressEventId);
        lastSeenQueryProgressEventId = queryProgressEvent.getMonotonicallyIncreasingEventId();
        assertTrue(queryProgressEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryProgressEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryProgressEvent.getStatistics().getTotalRows(), 0L);
        assertEquals(queryProgressEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryProgressEvent.getMetadata().getQueryId());

        QueryCompletedEvent queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        assertTrue(queryCompletedEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryCompletedEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryCompletedEvent.getStatistics().getTotalRows(), 0L);
        assertEquals(queryCompletedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryCompletedEvent.getMetadata().getQueryId());
        assertFalse(queryCompletedEvent.getMetadata().getPreparedQuery().isPresent());

        List<SplitCompletedEvent> splitCompletedEvents = generatedEvents.getSplitCompletedEvents();
        assertEquals(splitCompletedEvents.get(0).getQueryId(), queryCompletedEvent.getMetadata().getQueryId());
        // No input scanned for a constant query
        assertEquals(splitCompletedEvents.get(0).getStatistics().getCompletedPositions(), 0L);
    }

    @Test
    public void testNormalQuery()
            throws Exception
    {
        // We expect the following events
        // QueryCreated: 1, QueryProgressEvent:1, QueryCompleted: 1, Splits: SPLITS_PER_NODE (leaf splits) + LocalExchange[SINGLE] split + Aggregation/Output split
        int expectedEvents = 1 + 1 + 1 + SPLITS_PER_NODE + 1 + 1;
        runQueryAndWaitForEvents("SELECT sum(linenumber) FROM lineitem", expectedEvents);

        QueryCreatedEvent queryCreatedEvent = getOnlyElement(generatedEvents.getQueryCreatedEvents());
        assertEquals(queryCreatedEvent.getContext().getServerVersion(), "testversion");
        assertEquals(queryCreatedEvent.getContext().getServerAddress(), "127.0.0.1");
        assertEquals(queryCreatedEvent.getContext().getEnvironment(), "testing");
        assertEquals(queryCreatedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQuery(), "SELECT sum(linenumber) FROM lineitem");
        assertFalse(queryCreatedEvent.getMetadata().getPreparedQuery().isPresent());

        QueryProgressEvent queryProgressEvent = generatedEvents.getQueryProgressEvent();
        assertNotNull(queryProgressEvent);
        assertTrue(queryProgressEvent.getMonotonicallyIncreasingEventId() > lastSeenQueryProgressEventId);
        lastSeenQueryProgressEventId = queryProgressEvent.getMonotonicallyIncreasingEventId();
        assertTrue(queryProgressEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryProgressEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryProgressEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryProgressEvent.getMetadata().getQueryId());
        assertFalse(queryProgressEvent.getMetadata().getPreparedQuery().isPresent());

        QueryCompletedEvent queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        assertTrue(queryCompletedEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryCompletedEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryCompletedEvent.getIoMetadata().getOutput(), Optional.empty());
        assertEquals(queryCompletedEvent.getIoMetadata().getInputs().size(), 1);
        assertEquals(queryCompletedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(getOnlyElement(queryCompletedEvent.getIoMetadata().getInputs()).getCatalogName(), "tpch");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryCompletedEvent.getMetadata().getQueryId());
        assertFalse(queryCompletedEvent.getMetadata().getPreparedQuery().isPresent());
        assertEquals(queryCompletedEvent.getStatistics().getCompletedSplits(), SPLITS_PER_NODE + 2);

        List<SplitCompletedEvent> splitCompletedEvents = generatedEvents.getSplitCompletedEvents();
        assertEquals(splitCompletedEvents.size(), SPLITS_PER_NODE + 2); // leaf splits + aggregation split

        // All splits must have the same query ID
        Set<String> actual = splitCompletedEvents.stream()
                .map(SplitCompletedEvent::getQueryId)
                .collect(toSet());
        assertEquals(actual, ImmutableSet.of(queryCompletedEvent.getMetadata().getQueryId()));

        // Sum of row count processed by all leaf stages is equal to the number of rows in the table
        long actualCompletedPositions = splitCompletedEvents.stream()
                .filter(e -> !e.getStageExecutionId().endsWith(".0.0"))    // filter out the root stage
                .mapToLong(e -> e.getStatistics().getCompletedPositions())
                .sum();

        MaterializedResult result = runQueryAndWaitForEvents("SELECT count(*) FROM lineitem", expectedEvents);
        long expectedCompletedPositions = (long) result.getMaterializedRows().get(0).getField(0);

        assertEquals(actualCompletedPositions, expectedCompletedPositions);
        assertEquals(queryCompletedEvent.getStatistics().getTotalRows(), expectedCompletedPositions);
    }

    @Test
    public void testPrepareAndExecute()
            throws Exception
    {
        String selectQuery = "SELECT count(*) FROM lineitem WHERE shipmode = ?";
        String prepareQuery = "PREPARE stmt FROM " + selectQuery;

        // QueryCreated: 1, QueryProgressEvent: 1, QueryCompleted: 1, Splits: 0
        runQueryAndWaitForEvents(prepareQuery, 3);

        QueryCreatedEvent queryCreatedEvent = getOnlyElement(generatedEvents.getQueryCreatedEvents());
        assertEquals(queryCreatedEvent.getContext().getServerVersion(), "testversion");
        assertEquals(queryCreatedEvent.getContext().getServerAddress(), "127.0.0.1");
        assertEquals(queryCreatedEvent.getContext().getEnvironment(), "testing");
        assertEquals(queryCreatedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQuery(), prepareQuery);
        assertFalse(queryCreatedEvent.getMetadata().getPreparedQuery().isPresent());

        QueryProgressEvent queryProgressEvent = generatedEvents.getQueryProgressEvent();
        assertNotNull(queryProgressEvent);
        assertTrue(queryProgressEvent.getMonotonicallyIncreasingEventId() > lastSeenQueryProgressEventId);
        lastSeenQueryProgressEventId = queryProgressEvent.getMonotonicallyIncreasingEventId();
        assertTrue(queryProgressEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryProgressEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryProgressEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryProgressEvent.getMetadata().getQueryId());
        assertFalse(queryProgressEvent.getMetadata().getPreparedQuery().isPresent());

        QueryCompletedEvent queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        assertTrue(queryCompletedEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryCompletedEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryCompletedEvent.getIoMetadata().getOutput(), Optional.empty());
        assertEquals(queryCompletedEvent.getIoMetadata().getInputs().size(), 0);  // Prepare has no inputs
        assertEquals(queryCompletedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryCompletedEvent.getMetadata().getQueryId());
        assertFalse(queryCompletedEvent.getMetadata().getPreparedQuery().isPresent());
        assertEquals(queryCompletedEvent.getStatistics().getCompletedSplits(), 0); // Prepare has no splits

        // Add prepared statement to a new session to eliminate any impact on other tests in this suite.
        Session sessionWithPrepare = Session.builder(session).addPreparedStatement("stmt", selectQuery).build();

        // We expect the following events
        // QueryCreated: 1, QueryProgressEvent:1, QueryCompleted: 1, Splits: SPLITS_PER_NODE (leaf splits) + LocalExchange[SINGLE] split + Aggregation/Output split
        int expectedEvents = 1 + 1 + 1 + SPLITS_PER_NODE + 1 + 1;
        runQueryAndWaitForEvents("EXECUTE stmt USING 'SHIP'", expectedEvents, sessionWithPrepare);

        queryCreatedEvent = getOnlyElement(generatedEvents.getQueryCreatedEvents());
        assertEquals(queryCreatedEvent.getContext().getServerVersion(), "testversion");
        assertEquals(queryCreatedEvent.getContext().getServerAddress(), "127.0.0.1");
        assertEquals(queryCreatedEvent.getContext().getEnvironment(), "testing");
        assertEquals(queryCreatedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQuery(), "EXECUTE stmt USING 'SHIP'");
        assertTrue(queryCreatedEvent.getMetadata().getPreparedQuery().isPresent());
        assertEquals(queryCreatedEvent.getMetadata().getPreparedQuery().get(), selectQuery);

        queryProgressEvent = generatedEvents.getQueryProgressEvent();
        assertNotNull(queryProgressEvent);
        assertTrue(queryProgressEvent.getMonotonicallyIncreasingEventId() > lastSeenQueryProgressEventId);
        lastSeenQueryProgressEventId = queryProgressEvent.getMonotonicallyIncreasingEventId();
        assertTrue(queryProgressEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryProgressEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryProgressEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryProgressEvent.getMetadata().getQueryId());
        assertTrue(queryProgressEvent.getMetadata().getPreparedQuery().isPresent());
        assertEquals(queryProgressEvent.getMetadata().getPreparedQuery().get(), selectQuery);

        queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        assertTrue(queryCompletedEvent.getContext().getResourceGroupId().isPresent());
        assertEquals(queryCompletedEvent.getContext().getResourceGroupId().get(), createResourceGroupId("global", "user-user"));
        assertEquals(queryCompletedEvent.getIoMetadata().getOutput(), Optional.empty());
        assertEquals(queryCompletedEvent.getIoMetadata().getInputs().size(), 1);
        assertEquals(queryCompletedEvent.getContext().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");
        assertEquals(getOnlyElement(queryCompletedEvent.getIoMetadata().getInputs()).getCatalogName(), "tpch");
        assertEquals(queryCreatedEvent.getMetadata().getQueryId(), queryCompletedEvent.getMetadata().getQueryId());
        assertTrue(queryCompletedEvent.getMetadata().getPreparedQuery().isPresent());
        assertEquals(queryCompletedEvent.getMetadata().getPreparedQuery().get(), selectQuery);
        assertEquals(queryCompletedEvent.getStatistics().getCompletedSplits(), SPLITS_PER_NODE + 2);
    }

    @Test
    public void testOutputStats()
            throws Exception
    {
        // We expect the following events
        // QueryCreated: 1, QueryProgressEvent:1, QueryCompleted: 1, Splits: SPLITS_PER_NODE (leaf splits) + LocalExchange[SINGLE] split + Aggregation/Output split
        int expectedEvents = 1 + 1 + 1 + SPLITS_PER_NODE + 1 + 1;
        MaterializedResult result = runQueryAndWaitForEvents("SELECT 1 FROM lineitem", expectedEvents);
        QueryCreatedEvent queryCreatedEvent = getOnlyElement(generatedEvents.getQueryCreatedEvents());
        QueryProgressEvent queryProgressEvent = generatedEvents.getQueryProgressEvent();
        QueryCompletedEvent queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        QueryStats queryStats = queryRunner.getCoordinator().getQueryManager().getFullQueryInfo(new QueryId(queryCreatedEvent.getMetadata().getQueryId())).getQueryStats();

        assertNotNull(queryProgressEvent);
        assertTrue(queryProgressEvent.getMonotonicallyIncreasingEventId() > lastSeenQueryProgressEventId);
        lastSeenQueryProgressEventId = queryProgressEvent.getMonotonicallyIncreasingEventId();
        assertTrue(queryStats.getOutputDataSize().toBytes() > 0L);
        assertTrue(queryCompletedEvent.getStatistics().getOutputBytes() > 0L);
        assertEquals(result.getRowCount(), queryStats.getOutputPositions());
        assertEquals(result.getRowCount(), queryCompletedEvent.getStatistics().getOutputRows());

        runQueryAndWaitForEvents("SELECT COUNT(1) FROM lineitem", expectedEvents);
        queryCreatedEvent = getOnlyElement(generatedEvents.getQueryCreatedEvents());
        queryProgressEvent = generatedEvents.getQueryProgressEvent();
        queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        queryStats = queryRunner.getCoordinator().getQueryManager().getFullQueryInfo(new QueryId(queryCreatedEvent.getMetadata().getQueryId())).getQueryStats();

        assertNotNull(queryProgressEvent);
        assertTrue(queryProgressEvent.getMonotonicallyIncreasingEventId() > lastSeenQueryProgressEventId);
        lastSeenQueryProgressEventId = queryProgressEvent.getMonotonicallyIncreasingEventId();
        assertTrue(queryStats.getOutputDataSize().toBytes() > 0L);
        assertTrue(queryCompletedEvent.getStatistics().getOutputBytes() > 0L);
        assertEquals(1L, queryStats.getOutputPositions());
        assertEquals(1L, queryCompletedEvent.getStatistics().getOutputRows());
    }

    @Test
    public void testGraphvizQueryPlanOutput()
            throws Exception
    {
        int expectedEvents = 1 + 1 + 1 + SPLITS_PER_NODE + 1 + 1;
        String query = "EXPLAIN (type distributed, format graphviz) SELECT * FROM LINEITEM limit 1";
        Session sessionForEventLoggingWithStats = Session.builder(session)
                .setSystemProperty("print_stats_for_non_join_query", "true")
                .build();
        runQueryAndWaitForEvents("SELECT * FROM lineitem limit 1", expectedEvents, sessionForEventLoggingWithStats);
        QueryCompletedEvent queryCompletedEvent = getOnlyElement(generatedEvents.getQueryCompletedEvents());
        MaterializedResult expected = runQueryAndWaitForEvents(query, expectedEvents);
        assertEquals(queryCompletedEvent.getMetadata().getGraphvizPlan().get(), getOnlyElement(expected.getOnlyColumnAsSet()));
    }

    public static class EventsBuilder
    {
        private ImmutableList.Builder<QueryCreatedEvent> queryCreatedEvents;
        private ImmutableList.Builder<QueryCompletedEvent> queryCompletedEvents;
        private ImmutableList.Builder<SplitCompletedEvent> splitCompletedEvents;
        private volatile QueryProgressEvent queryProgressEvent;

        private CountDownLatch eventsLatch;

        public synchronized void initialize(int numEvents)
        {
            queryCreatedEvents = ImmutableList.builder();
            queryCompletedEvents = ImmutableList.builder();
            splitCompletedEvents = ImmutableList.builder();
            queryProgressEvent = null;

            eventsLatch = new CountDownLatch(numEvents);
        }

        public void waitForEvents(int timeoutSeconds)
                throws InterruptedException
        {
            eventsLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        public synchronized void addQueryCreated(QueryCreatedEvent event)
        {
            queryCreatedEvents.add(event);
            eventsLatch.countDown();
        }

        public synchronized void addQueryCompleted(QueryCompletedEvent event)
        {
            queryCompletedEvents.add(event);
            eventsLatch.countDown();
        }

        public synchronized void addSplitCompleted(SplitCompletedEvent event)
        {
            splitCompletedEvents.add(event);
            eventsLatch.countDown();
        }

        public synchronized void addQueryProgress(QueryProgressEvent event)
        {
            // Store only one QueryProgress event
            if (queryProgressEvent == null) {
                queryProgressEvent = event;
                eventsLatch.countDown();
            }
        }

        public List<QueryCreatedEvent> getQueryCreatedEvents()
        {
            return queryCreatedEvents.build();
        }

        public List<QueryCompletedEvent> getQueryCompletedEvents()
        {
            return queryCompletedEvents.build();
        }

        public List<SplitCompletedEvent> getSplitCompletedEvents()
        {
            return splitCompletedEvents.build();
        }

        public QueryProgressEvent getQueryProgressEvent()
        {
            return queryProgressEvent;
        }
    }
}
