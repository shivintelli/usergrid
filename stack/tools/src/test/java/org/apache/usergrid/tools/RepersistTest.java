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
package org.apache.usergrid.tools;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.usergrid.ServiceITSetup;
import org.apache.usergrid.ServiceITSetupImpl;
import org.apache.usergrid.services.AbstractServiceIT;
import org.junit.ClassRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;


public class RepersistTest extends AbstractServiceIT {
    static final Logger logger = LoggerFactory.getLogger( RepersistTest.class );

    @ClassRule
    public static ServiceITSetup setup = new ServiceITSetupImpl();


    @org.junit.Test
    public void testBasicOperation() throws Exception {

        String rand = RandomStringUtils.randomAlphanumeric( 10 );

        // create app with some data

        String orgName = "org_" + rand;
        String appName = "app_" + rand;
        String userName = "user_" + rand;

        ExportDataCreator creator = new ExportDataCreator();
        creator.startTool( new String[] {
            "-organization", orgName,
            "-application", appName,
            "-username", userName,
            "-users", "5",
            "-collections", "1",
            "-host", "localhost:9160",
            "-eshost", "localhost:9200",
            "-escluster", "elasticsearch",
            "-ugcluster","usergrid"

        }, false);

        // repersist it

        Repersist repersist = new Repersist();
        repersist.startTool( new String[] {
                "-apps", orgName + "/" + appName,
                "-host", "localhost:9160",
                "-eshost", "localhost:9200",
                "-escluster", "elasticsearch",
                "-ugcluster","usergrid"

        }, false);

        assertEquals( 38, repersist.count );

    }

}
