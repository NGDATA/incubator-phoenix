/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;

import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Maps;


public class SaltedIndexIT extends BaseIndexIT {
    private static final int TABLE_SPLITS = 3;
    private static final int INDEX_SPLITS = 4;
    
    @BeforeClass 
    public static void doSetup() throws Exception {
        Map<String,String> props = Maps.newHashMapWithExpectedSize(3);
        // Don't split intra region so we can more easily know that the n-way parallelization is for the explain plan
        props.put(QueryServices.MAX_INTRA_REGION_PARALLELIZATION_ATTRIB, Integer.toString(1));
        // Forces server cache to be used
        props.put(QueryServices.INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB, Integer.toString(2));
        // Drop the HBase table metadata for this test
        props.put(QueryServices.DROP_METADATA_ATTRIB, Boolean.toString(true));
        // Must update config before starting server
        startServer(getUrl(), new ReadOnlyProps(props.entrySet().iterator()));
    }
    
    private static void makeImmutableAndDeleteData() throws Exception {
        Connection conn = DriverManager.getConnection(getUrl(), TEST_PROPERTIES);
        try {
            conn.setAutoCommit(true);
            conn.createStatement().execute("DELETE FROM " + DATA_TABLE_FULL_NAME);
            conn.createStatement().execute("ALTER TABLE " + DATA_TABLE_FULL_NAME + " SET IMMUTABLE_ROWS=true");
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + DATA_TABLE_FULL_NAME).next();
            PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
            assertTrue(pconn.getMetaDataCache().getTable(new PTableKey(pconn.getTenantId(), DATA_TABLE_FULL_NAME)).isImmutableRows());
        } finally {
            conn.close();
        }
    }
    
    @Test
    public void testMutableTableIndexMaintanenceSaltedSalted() throws Exception {
        testMutableTableIndexMaintanence(TABLE_SPLITS, INDEX_SPLITS);
        makeImmutableAndDeleteData();
        testMutableTableIndexMaintanence(TABLE_SPLITS, INDEX_SPLITS);
    }

    @Test
    public void testMutableTableIndexMaintanenceSalted() throws Exception {
        testMutableTableIndexMaintanence(null, INDEX_SPLITS);
        makeImmutableAndDeleteData();
        testMutableTableIndexMaintanence(null, INDEX_SPLITS);
    }

    @Test
    public void testMutableTableIndexMaintanenceUnsalted() throws Exception {
        testMutableTableIndexMaintanence(null, null);
        makeImmutableAndDeleteData();
        testMutableTableIndexMaintanence(null, null);
    }

    private void testMutableTableIndexMaintanence(Integer tableSaltBuckets, Integer indexSaltBuckets) throws Exception {
        String query;
        ResultSet rs;
        
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS " + DATA_TABLE_FULL_NAME + " (k VARCHAR NOT NULL PRIMARY KEY, v VARCHAR)  " +  (tableSaltBuckets == null ? "" : " SALT_BUCKETS=" + tableSaltBuckets));
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
        
        conn.createStatement().execute("CREATE INDEX IF NOT EXISTS " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v DESC)" + (indexSaltBuckets == null ? "" : " SALT_BUCKETS=" + indexSaltBuckets));
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());

        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, "x");
        stmt.execute();
        stmt.setString(1,"b");
        stmt.setString(2, "y");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("y",rs.getString(1));
        assertEquals("b",rs.getString(2));
        assertTrue(rs.next());
        assertEquals("x",rs.getString(1));
        assertEquals("a",rs.getString(2));
        assertFalse(rs.next());

        query = "SELECT k,v FROM " + DATA_TABLE_FULL_NAME + " WHERE v = 'y'";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("b",rs.getString(1));
        assertEquals("y",rs.getString(2));
        assertFalse(rs.next());
        
        String expectedPlan;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        expectedPlan = indexSaltBuckets == null ? 
             "CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + INDEX_TABLE_FULL_NAME + " [~'y']" : 
            ("CLIENT PARALLEL 4-WAY SKIP SCAN ON 4 KEYS OVER " + INDEX_TABLE_FULL_NAME + " [0,~'y'] - [3,~'y']\n" + 
             "CLIENT MERGE SORT");
        assertEquals(expectedPlan,QueryUtil.getExplainPlan(rs));

        // Will use index, so rows returned in DESC order.
        // This is not a bug, though, because we can
        // return in any order.
        query = "SELECT k,v FROM " + DATA_TABLE_FULL_NAME + " WHERE v >= 'x'";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("b",rs.getString(1));
        assertEquals("y",rs.getString(2));
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertFalse(rs.next());
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        expectedPlan = indexSaltBuckets == null ? 
            "CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + INDEX_TABLE_FULL_NAME + " [*] - [~'x']" :
            ("CLIENT PARALLEL 4-WAY SKIP SCAN ON 4 RANGES OVER " + INDEX_TABLE_FULL_NAME + " [0,*] - [3,~'x']\n" + 
             "CLIENT MERGE SORT");
        assertEquals(expectedPlan,QueryUtil.getExplainPlan(rs));
        
        // Use data table, since point lookup trumps order by
        query = "SELECT k,v FROM " + DATA_TABLE_FULL_NAME + " WHERE k = 'a' ORDER BY v";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertFalse(rs.next());
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        expectedPlan = tableSaltBuckets == null ? 
                "CLIENT PARALLEL 1-WAY POINT LOOKUP ON 1 KEY OVER " + DATA_TABLE_FULL_NAME + "\n" +
                "    SERVER SORTED BY [V]\n" + 
                "CLIENT MERGE SORT" :
                    "CLIENT PARALLEL 1-WAY POINT LOOKUP ON 1 KEY OVER " + DATA_TABLE_FULL_NAME + "\n" + 
                    "    SERVER SORTED BY [V]\n" + 
                    "CLIENT MERGE SORT";
        assertEquals(expectedPlan,QueryUtil.getExplainPlan(rs));
        
        // Will use data table now, since there's a LIMIT clause and
        // we're able to optimize out the ORDER BY, unless the data
        // table is salted.
        query = "SELECT k,v FROM " + DATA_TABLE_FULL_NAME + " WHERE v >= 'x' ORDER BY k LIMIT 2";
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertTrue(rs.next());
        assertEquals("b",rs.getString(1));
        assertEquals("y",rs.getString(2));
        assertFalse(rs.next());
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        expectedPlan = tableSaltBuckets == null ? 
             "CLIENT PARALLEL 1-WAY FULL SCAN OVER " + DATA_TABLE_FULL_NAME + "\n" +
             "    SERVER FILTER BY V >= 'x'\n" + 
             "    SERVER 2 ROW LIMIT\n" + 
             "CLIENT 2 ROW LIMIT" :
                 "CLIENT PARALLEL 3-WAY FULL SCAN OVER " + DATA_TABLE_FULL_NAME + "\n" +
                 "    SERVER FILTER BY V >= 'x'\n" + 
                 "    SERVER 2 ROW LIMIT\n" + 
                 "CLIENT MERGE SORT\n" + 
                 "CLIENT 2 ROW LIMIT";
        assertEquals(expectedPlan,QueryUtil.getExplainPlan(rs));
    }
}
