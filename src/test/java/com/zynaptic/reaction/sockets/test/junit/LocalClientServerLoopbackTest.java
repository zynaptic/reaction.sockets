/*
 * Zynaptic Reaction Sockets - An asynchronous programming framework for Java.
 * 
 * Copyright (c) 2016-2019, Zynaptic Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * Please visit www.zynaptic.com or contact reaction@zynaptic.com if you need
 * additional information or have any questions.
 */

package com.zynaptic.reaction.sockets.test.junit;

import java.util.logging.Level;

import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.core.ReactorControl;
import com.zynaptic.reaction.core.ReactorCore;
import com.zynaptic.reaction.sockets.test.core.LocalClientServerLoopback;
import com.zynaptic.reaction.util.FixedUpMonotonicClock;
import com.zynaptic.reaction.util.ReactorLogSystemOut;

import junit.framework.Assert;
import junit.framework.TestCase;

/**
 * Provides JUnit test wrapper around the local client server loopback test for
 * conventional sockets.
 * 
 * @author Chris Holgate
 */
public class LocalClientServerLoopbackTest extends TestCase {
  public void testLocalClientServerLoopback() {
    Level logLevel = Level.INFO;
    ReactorControl reactorControl = ReactorCore.getReactorControl();
    Reactor reactor = ReactorCore.getReactor();
    reactorControl.start(new FixedUpMonotonicClock(), new ReactorLogSystemOut());
    int testStatus;
    try {
      reactor.getLogger("com.zynaptic.relay.socket").setLogLevel(logLevel);
      testStatus = new LocalClientServerLoopback().runTest(reactor, logLevel).defer();
      reactorControl.stop();
      reactorControl.join();
      reactor.getLogger("TESTBENCH").log(Level.INFO, "*** TEST COMPLETED STATUS = " + testStatus + " ***");
    } catch (Exception error) {
      reactor.getLogger("TESTBENCH").log(Level.SEVERE, "*** TEST FAILED WITH ERROR ***");
      Assert.fail(error.toString());
    }
  }
}
