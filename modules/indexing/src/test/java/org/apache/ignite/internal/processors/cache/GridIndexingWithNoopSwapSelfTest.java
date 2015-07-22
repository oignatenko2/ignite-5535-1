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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.eviction.fifo.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.processors.cache.IgniteCacheAbstractQuerySelfTest.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.spi.swapspace.noop.*;
import org.apache.ignite.testframework.junits.common.*;

import java.util.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CacheRebalanceMode.*;

/**
 * GG-4368
 */
public class GridIndexingWithNoopSwapSelfTest extends GridCommonAbstractTest {
    /** */
    private static final TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    protected Ignite ignite;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        c.setDiscoverySpi(disco);

        c.setSwapSpaceSpi(new NoopSwapSpaceSpi());

        CacheConfiguration<?,?> cc = defaultCacheConfiguration();

        cc.setCacheMode(PARTITIONED);
        cc.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        cc.setRebalanceMode(SYNC);
        cc.setSwapEnabled(true);
        cc.setNearConfiguration(new NearCacheConfiguration());

        FifoEvictionPolicy plc = new FifoEvictionPolicy();
        plc.setMaxSize(1000);

        cc.setEvictionPolicy(plc);
        cc.setBackups(1);
        cc.setAtomicityMode(TRANSACTIONAL);
        cc.setIndexedTypes(
            Integer.class, ObjectValue.class
        );

        c.setCacheConfiguration(cc);

        return c;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        ignite = startGrid();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        ignite = null;
    }

    /** @throws Exception If failed. */
    public void testQuery() throws Exception {
        IgniteCache<Integer, ObjectValue> cache = ignite.cache(null);

        int cnt = 10;

        for (int i = 0; i < cnt; i++)
            cache.getAndPut(i, new ObjectValue("test" + i, i));

        for (int i = 0; i < cnt; i++) {
            assertNotNull(cache.localPeek(i, ONHEAP_PEEK_MODES));

            cache.localEvict(Collections.singleton(i)); // Swap.
        }

        SqlQuery<Integer, ObjectValue> qry =
            new SqlQuery(ObjectValue.class, "intVal >= ? order by intVal");

        assertEquals(0, cache.query(qry.setArgs(0)).getAll().size());
    }
}