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

package com.zynaptic.reaction.sockets.test.core;

import java.net.InetAddress;
import java.util.logging.Level;

import com.zynaptic.reaction.Deferrable;
import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.Signal;
import com.zynaptic.reaction.Signalable;
import com.zynaptic.reaction.Timeable;
import com.zynaptic.reaction.core.ReactorControl;
import com.zynaptic.reaction.core.ReactorCore;
import com.zynaptic.reaction.sockets.ServerHandle;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;
import com.zynaptic.reaction.sockets.core.SocketServiceCore;
import com.zynaptic.reaction.util.FixedUpMonotonicClock;
import com.zynaptic.reaction.util.ReactorLogSystemOut;

/**
 * Implements a client/server loopback testcase for conventional sockets. This
 * may be executed as a standalone Java application or as a JUnit test via the
 * associated JUnit test wrapper.
 * 
 * @author Chris Holgate
 */
public class LocalClientServerLoopback {

  // Specify the local port to be used for the loopback test.
  private static final int LOOPBACK_PORT = 8023;

  // Specify the valid range of packet buffer sizes.
  private static final int MIN_BUFFER_SIZE = 48;
  private static final int MAX_BUFFER_SIZE = 1500;

  // Specify the test execution time and shutdown delay.
  private static final int TEST_EXECUTION_TIME = 2000;
  private static final int TEST_SHUTDOWN_TIME = 2000;

  // Specify the pseudo-random data seed.
  private static final long DATA_SEED = 0xDA7A5EED;

  /*
   * Allow direct execution of test case as a Java application. No arguments are
   * required.
   */
  public static void main(String[] args) {
    Level logLevel = Level.FINEST;
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
      error.printStackTrace();
      testStatus = -1;
    }
    System.exit(testStatus);
  }

  // Specify instance scope data.
  private Reactor reactor = null;
  private Logger logger = null;
  private Level logLevel = null;
  private Deferred<Integer> deferredResult = null;
  private SocketService socketService = null;
  private ServerHandle serverHandle = null;
  private SocketHandle clientSocketHandle = null;
  private SocketTestDataSource dataSource = null;
  private SocketTestDataSink dataSink = null;

  /*
   * Main test entry point.
   */
  public synchronized Deferred<Integer> runTest(Reactor reactor, Level logLevel) {
    this.reactor = reactor;
    this.logger = reactor.getLogger("TESTBENCH");
    this.logLevel = logLevel;
    logger.log(Level.INFO, "*** RUNNING CONVENTIONAL SOCKET LOOPBACK TEST ***");
    this.deferredResult = reactor.newDeferred();

    // Open the server socket ready for use.
    socketService = new SocketServiceCore(reactor);
    socketService.openServer(InetAddress.getLoopbackAddress(), LOOPBACK_PORT)
        .addDeferrable(new ServerSetupCompletionHandler(), true);
    logger.log(Level.INFO, "Server setup initiated");
    return deferredResult.makeRestricted();
  }

  /*
   * Callback on having completed the server setup process.
   */
  private class ServerSetupCompletionHandler implements Deferrable<ServerHandle, Void> {
    public Void onCallback(Deferred<ServerHandle> deferred, ServerHandle newServerHandle) throws Exception {
      synchronized (LocalClientServerLoopback.this) {
        logger.log(Level.INFO, "Server setup complete");

        // Attach a server socket acceptor which processes inbound connection requests
        // and creates data sink components for integrity checking.
        serverHandle = newServerHandle;
        serverHandle.addSocketAcceptor(new ServerSocketAcceptor());

        // Open a client connection.
        socketService.openClient(InetAddress.getLoopbackAddress(), LOOPBACK_PORT)
            .addDeferrable(new ClientSetupCompletionHandler(), true);
        logger.log(Level.INFO, "Client setup initiated");
        return null;
      }
    }

    public Void onErrback(Deferred<ServerHandle> deferred, Exception error) {
      synchronized (LocalClientServerLoopback.this) {
        if (deferredResult != null) {
          deferredResult.errback(error);
          deferredResult = null;
        }
        return null;
      }
    }
  }

  /*
   * Callback on having completed the client setup process.
   */
  private class ClientSetupCompletionHandler implements Deferrable<SocketHandle, Void> {
    public Void onCallback(Deferred<SocketHandle> deferred, SocketHandle socketHandle) throws Exception {
      synchronized (LocalClientServerLoopback.this) {
        logger.log(Level.INFO, "Client setup complete");
        clientSocketHandle = socketHandle;
        dataSource = new SocketTestDataSource(reactor, reactor.getLogger("DATA-SOURCE"), socketService, socketHandle,
            DATA_SEED, null, MIN_BUFFER_SIZE, MAX_BUFFER_SIZE);
        reactor.getLogger("DATA-SOURCE").setLogLevel(logLevel);
        dataSource.generateData();
        logger.log(Level.INFO, "Data source activated");
        reactor.runTimerOneShot(new ExecutionCompletionHandler(), TEST_EXECUTION_TIME, null);
        return null;
      }
    }

    public Void onErrback(Deferred<SocketHandle> deferred, Exception error) throws Exception {
      synchronized (LocalClientServerLoopback.this) {
        if (deferredResult != null) {
          deferredResult.errback(error);
          deferredResult = null;
        }
        return null;
      }
    }
  }

  /*
   * Callback on having executed the test case for the required execution time.
   */
  private class ExecutionCompletionHandler implements Timeable<Void> {
    public void onTick(Void data) {
      synchronized (LocalClientServerLoopback.this) {
        logger.log(Level.INFO, "Test execution completed");
        clientSocketHandle.close().addDeferrable(new SocketCloseCompletionHandler(), true);
      }
    }
  }

  /*
   * Callback on having closed the client side socket.
   */
  private class SocketCloseCompletionHandler implements Deferrable<Boolean, Void> {
    public Void onCallback(Deferred<Boolean> deferred, Boolean status) throws Exception {
      logger.log(Level.INFO, "Socket close completed");
      synchronized (LocalClientServerLoopback.this) {
        serverHandle.close().addDeferrable(new ServerCloseCompletionHandler(), true);
        return null;
      }
    }

    public Void onErrback(Deferred<Boolean> deferred, Exception error) throws Exception {
      synchronized (LocalClientServerLoopback.this) {
        if (deferredResult != null) {
          deferredResult.errback(error);
          deferredResult = null;
        }
        return null;
      }
    }
  }

  /*
   * Callback on having closed the server port.
   */
  private class ServerCloseCompletionHandler implements Deferrable<Boolean, Void> {
    public Void onCallback(Deferred<Boolean> deferred, Boolean status) throws Exception {
      logger.log(Level.INFO, "Server close completed");
      synchronized (LocalClientServerLoopback.this) {
        reactor.runTimerOneShot(new ShutdownCompletionHandler(), TEST_SHUTDOWN_TIME, null);
        return null;
      }
    }

    public Void onErrback(Deferred<Boolean> deferred, Exception error) throws Exception {
      synchronized (LocalClientServerLoopback.this) {
        if (deferredResult != null) {
          deferredResult.errback(error);
          deferredResult = null;
        }
        return null;
      }
    }
  }

  /*
   * Callback on having completed shutdown.
   */
  private class ShutdownCompletionHandler implements Timeable<Void> {
    public void onTick(Void data) {
      logger.log(Level.INFO, "Test shutdown completed");
      synchronized (LocalClientServerLoopback.this) {
        if (deferredResult != null) {
          if (dataSource.getFatalError() != null) {
            deferredResult.errback(dataSource.getFatalError());
          } else if (dataSink.getFatalError() != null) {
            deferredResult.errback(dataSink.getFatalError());
          } else {
            deferredResult.callback(0);
          }
          deferredResult = null;
        }
      }
    }
  }

  /*
   * Callback on accepting a new server socket connection.
   */
  private class ServerSocketAcceptor implements Signalable<SocketHandle> {
    public void onSignal(Signal<SocketHandle> signalId, SocketHandle socketHandle) {
      synchronized (LocalClientServerLoopback.this) {
        if (socketHandle == null) {
          logger.log(Level.INFO, "Received server shutdown notification");
        } else {
          if (dataSink == null) {
            dataSink = new SocketTestDataSink(reactor, reactor.getLogger("DATA-SINK"), socketService, socketHandle,
                DATA_SEED, null, MIN_BUFFER_SIZE, MAX_BUFFER_SIZE);
            reactor.getLogger("DATA-SINK").setLogLevel(logLevel);
            dataSink.consumeData();
            logger.log(Level.INFO, "Data sink activated");
          } else if (deferredResult != null) {
            deferredResult.errback(new Exception("Only a single server connection is permitted for the loopback test"));
            deferredResult = null;
          }
        }
      }
    }
  }
}
