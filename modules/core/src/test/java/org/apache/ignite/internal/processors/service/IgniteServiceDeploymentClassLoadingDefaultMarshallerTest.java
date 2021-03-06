/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.internal.processors.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.marshaller.Marshaller;
import org.apache.ignite.services.Service;
import org.apache.ignite.services.ServiceConfiguration;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 * Tests that not all nodes in cluster need user's service definition (only nodes according to filter).
 */
public class IgniteServiceDeploymentClassLoadingDefaultMarshallerTest extends GridCommonAbstractTest {
    /** */
    private static final String NOOP_SERVICE_CLS_NAME = "org.apache.ignite.tests.p2p.NoopService";

    /** IP finder. */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** */
    private static final int SERVER_NODE = 0;

    /** */
    private static final int SERVER_NODE_WITH_EXT_CLASS_LOADER = 1;

    /** */
    private static final int CLIENT_NODE = 2;

    /** */
    private static final int CLIENT_NODE_WITH_EXT_CLASS_LOADER = 3;

    /** */
    private static final String NODE_NAME_ATTR = "NODE_NAME";

    /** */
    private static ClassLoader extClsLdr;

    /** */
    private Set<String> extClsLdrGrids = new HashSet<>();

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setPeerClassLoadingEnabled(false);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        cfg.setMarshaller(marshaller());

        cfg.setUserAttributes(Collections.singletonMap(NODE_NAME_ATTR, igniteInstanceName));

        if (getTestIgniteInstanceName(CLIENT_NODE_WITH_EXT_CLASS_LOADER).equals(igniteInstanceName)
            || getTestIgniteInstanceName(CLIENT_NODE).equals(igniteInstanceName))
            cfg.setClientMode(true);

        if (extClsLdrGrids.contains(igniteInstanceName))
            cfg.setClassLoader(extClsLdr);

        return cfg;
    }

    /**
     * @return Marshaller.
     */
    protected Marshaller marshaller() {
        return null;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        extClsLdrGrids.clear();

        extClsLdrGrids.add(getTestIgniteInstanceName(SERVER_NODE_WITH_EXT_CLASS_LOADER));
        extClsLdrGrids.add(getTestIgniteInstanceName(CLIENT_NODE_WITH_EXT_CLASS_LOADER));
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        super.afterTest();
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        extClsLdr = getExternalClassLoader();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        extClsLdr = null;
    }

    /**
     * @throws Exception If failed.
     */
    public void testServiceDeployment1() throws Exception {
        startGrid(SERVER_NODE);

        startGrid(SERVER_NODE_WITH_EXT_CLASS_LOADER).services().deploy(serviceConfig());

        startGrid(CLIENT_NODE);

        startGrid(CLIENT_NODE_WITH_EXT_CLASS_LOADER).services().deploy(serviceConfig());

        ignite(SERVER_NODE).services().serviceDescriptors();

        ignite(SERVER_NODE_WITH_EXT_CLASS_LOADER).services().serviceDescriptors();
    }

    /**
     * @throws Exception If failed.
     */
    public void testServiceDeployment2() throws Exception {
        startGrid(SERVER_NODE);

        startGrid(CLIENT_NODE_WITH_EXT_CLASS_LOADER).services().deploy(serviceConfig());

        startGrid(CLIENT_NODE);

        startGrid(SERVER_NODE_WITH_EXT_CLASS_LOADER).services().deploy(serviceConfig());
    }

    /**
     * @throws Exception If failed.
     */
    public void testServiceDeployment3() throws Exception {
        startGrid(SERVER_NODE_WITH_EXT_CLASS_LOADER).services().deploy(serviceConfig());

        startGrid(SERVER_NODE);

        startGrid(CLIENT_NODE);

        startGrid(CLIENT_NODE_WITH_EXT_CLASS_LOADER).services().deploy(serviceConfig());
    }

    /**
     * @return Service configuration.
     * @throws Exception If failed.
     */
    private ServiceConfiguration serviceConfig() throws Exception {
        ServiceConfiguration srvCfg = new ServiceConfiguration();

        srvCfg.setNodeFilter(new TestNodeFilter(extClsLdrGrids));

        Class<Service> srvcCls = (Class<Service>)extClsLdr.loadClass(NOOP_SERVICE_CLS_NAME);

        Service srvc = srvcCls.newInstance();

        srvCfg.setService(srvc);

        srvCfg.setName("TestDeploymentService");

        srvCfg.setMaxPerNodeCount(1);

        return srvCfg;
    }

    /**
     *
     */
    private static class TestNodeFilter implements IgnitePredicate<ClusterNode> {
        /** */
        private static final long serialVersionUID = 0;

        /** */
        private Set<String> grids;

        /**
         * @param grids Grid names.
         */
        private TestNodeFilter(Set<String> grids) {
            this.grids = grids;
        }

        /** {@inheritDoc} */
        @SuppressWarnings("SuspiciousMethodCalls")
        @Override public boolean apply(ClusterNode node) {
            return grids.contains(node.attribute(NODE_NAME_ATTR));
        }
    }
}
