/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.reference.sys.node.local;

import io.crate.metadata.ReferenceImplementation;
import io.crate.metadata.sys.SysNodesTableInfo;
import io.crate.monitor.ExtendedNodeInfo;
import io.crate.operation.reference.NestedObjectExpression;
import io.crate.operation.reference.sys.node.local.fs.NodeFsExpression;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.monitor.jvm.JvmService;
import org.elasticsearch.monitor.os.OsService;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;

public class NodeSysExpression extends NestedObjectExpression {

    private final NodeService nodeService;
    private final OsService osService;
    private final JvmService jvmService;
    private final ExtendedNodeInfo extendedNodeInfo;

    @Inject
    public NodeSysExpression(ClusterService clusterService,
                             OsService osService,
                             NodeService nodeService,
                             JvmService jvmService,
                             Discovery discovery,
                             ThreadPool threadPool,
                             ExtendedNodeInfo extendedNodeInfo) {
        this.nodeService = nodeService;
        this.osService = osService;
        this.jvmService = jvmService;
        this.extendedNodeInfo = extendedNodeInfo;
        childImplementations.put(SysNodesTableInfo.SYS_COL_HOSTNAME,
                new NodeHostnameExpression());
        childImplementations.put(SysNodesTableInfo.SYS_COL_REST_URL,
                new NodeRestUrlExpression(clusterService));
        childImplementations.put(SysNodesTableInfo.SYS_COL_ID,
                new NodeIdExpression(clusterService));
        childImplementations.put(SysNodesTableInfo.SYS_COL_NODE_NAME,
                new NodeNameExpression(discovery));
        childImplementations.put(SysNodesTableInfo.SYS_COL_PORT,
                new NodePortExpression(nodeService));
        childImplementations.put(SysNodesTableInfo.SYS_COL_VERSION,
                new NodeVersionExpression());
        childImplementations.put(SysNodesTableInfo.SYS_COL_THREAD_POOLS,
                new NodeThreadPoolsExpression(threadPool));
        childImplementations.put(SysNodesTableInfo.SYS_COL_OS_INFO,
                new NodeOsInfoExpression(osService.info()));
    }

    @Override
    public ReferenceImplementation getChildImplementation(String name) {
        switch (name) {
            case SysNodesTableInfo.SYS_COL_MEM:
                return new NodeMemoryExpression(osService.stats());

            case SysNodesTableInfo.SYS_COL_LOAD:
                return new NodeLoadExpression(extendedNodeInfo.osStats());

            case SysNodesTableInfo.SYS_COL_OS:
                return new NodeOsExpression(extendedNodeInfo.osStats());

            case SysNodesTableInfo.SYS_COL_PROCESS:
                try {
                    return new NodeProcessExpression(nodeService.stats().getProcess(), extendedNodeInfo.processCpuStats());
                } catch (IOException e) {
                    // This is a bug in ES method signature, IOException is never thrown
                }
                break;

            case SysNodesTableInfo.SYS_COL_HEAP:
                return new NodeHeapExpression(jvmService.stats());

            case SysNodesTableInfo.SYS_COL_NETWORK:
                return new NodeNetworkExpression(extendedNodeInfo.networkStats());

            case SysNodesTableInfo.SYS_COL_FS:
                return new NodeFsExpression(extendedNodeInfo.fsStats());
        }
        return super.getChildImplementation(name);
    }
}
