/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.collect;

import com.google.common.collect.ImmutableMap;
import io.crate.analyze.symbol.Literal;
import io.crate.analyze.symbol.Symbol;
import io.crate.core.collections.TreeMapBuilder;
import io.crate.jobs.KeepAliveListener;
import io.crate.metadata.*;
import io.crate.operation.collect.sources.CollectSourceResolver;
import io.crate.operation.collect.sources.FileCollectSource;
import io.crate.operation.reference.sys.node.NodeSysExpression;
import io.crate.planner.node.dql.CollectPhase;
import io.crate.planner.node.dql.FileUriCollectPhase;
import io.crate.planner.projection.Projection;
import io.crate.test.integration.CrateUnitTest;
import io.crate.testing.CollectingRowReceiver;
import io.crate.types.DataTypes;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Paths;
import java.util.*;

import static io.crate.testing.TestingHelpers.createReference;
import static io.crate.testing.TestingHelpers.isRow;
import static org.hamcrest.Matchers.contains;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MapSideDataCollectOperationTest extends CrateUnitTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testFileUriCollect() throws Exception {
        ClusterService clusterService = mock(ClusterService.class);
        DiscoveryNode discoveryNode = mock(DiscoveryNode.class);
        when(discoveryNode.id()).thenReturn("dummyNodeId");
        DiscoveryNodes discoveryNodes = mock(DiscoveryNodes.class);
        when(discoveryNodes.localNodeId()).thenReturn("dummyNodeId");
        ClusterState clusterState = mock(ClusterState.class);
        when(clusterState.nodes()).thenReturn(discoveryNodes);
        when(clusterService.state()).thenReturn(clusterState);
        DiscoveryService discoveryService = mock(DiscoveryService.class);
        when(discoveryService.localNode()).thenReturn(discoveryNode);
        Functions functions = new Functions(
                ImmutableMap.<FunctionIdent, FunctionImplementation>of(),
                ImmutableMap.<String, DynamicFunctionResolver>of());
        NestedReferenceResolver referenceResolver = new NestedReferenceResolver() {
            @Override
            public ReferenceImplementation getImplementation(ReferenceInfo referenceInfo) {
                return null;
            }
        };

        CollectSourceResolver collectSourceResolver = mock(CollectSourceResolver.class);
        when(collectSourceResolver.getService(any(CollectPhase.class), anyString()))
                .thenReturn(new FileCollectSource(functions, clusterService));
        MapSideDataCollectOperation collectOperation = new MapSideDataCollectOperation(
                clusterService,
                functions,
                referenceResolver,
                mock(NodeSysExpression.class),
                collectSourceResolver,
                mock(ThreadPool.class)
        );

        File tmpFile = temporaryFolder.newFile("fileUriCollectOperation.json");
        try (FileWriter writer = new FileWriter(tmpFile)) {
            writer.write("{\"name\": \"Arthur\", \"id\": 4, \"details\": {\"age\": 38}}\n");
            writer.write("{\"id\": 5, \"name\": \"Trillian\", \"details\": {\"age\": 33}}\n");
        }

        Routing routing = new Routing(
                TreeMapBuilder.<String, Map<String, List<Integer>>>newMapBuilder()
                .put("dummyNodeId", new TreeMap<String, List<Integer>>())
                .map()
        );
        FileUriCollectPhase collectNode = new FileUriCollectPhase(
                UUID.randomUUID(),
                0,
                "test",
                routing,
                RowGranularity.DOC,
                Literal.newLiteral(Paths.get(tmpFile.toURI()).toUri().toString()),
                Arrays.<Symbol>asList(
                        createReference("name", DataTypes.STRING),
                        createReference(new ColumnIdent("details", "age"), DataTypes.INTEGER)
                ),
                Arrays.<Projection>asList(),
                null,
                false
        );

        String threadPoolName = JobCollectContext.threadPoolName(collectNode, "dummyNodeId");

        CollectingRowReceiver cd = new CollectingRowReceiver();
        cd.prepare();
        JobCollectContext jobCollectContext = mock(JobCollectContext.class);
        KeepAliveListener keepAliveListener = mock(KeepAliveListener.class);
        when(jobCollectContext.keepAliveListener()).thenReturn(keepAliveListener);
        Collection<CrateCollector> collectors = collectOperation.createCollectors(collectNode, cd, jobCollectContext);
        collectOperation.launchCollectors(collectors, threadPoolName);
        assertThat(cd.result(), contains(
                isRow("Arthur", 38),
                isRow("Trillian", 33)
        ));
    }
}
