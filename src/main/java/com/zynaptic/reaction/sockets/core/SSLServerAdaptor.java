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

package com.zynaptic.reaction.sockets.core;

import java.net.InetAddress;
import java.util.Collection;
import java.util.logging.Level;

import javax.net.ssl.SSLContext;

import com.zynaptic.reaction.Deferrable;
import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.Signal;
import com.zynaptic.reaction.Signalable;
import com.zynaptic.reaction.sockets.ServerHandle;
import com.zynaptic.reaction.sockets.SocketClosedException;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements an SSL socket server adaptor which converts underlying socket
 * server connections to SSL/TLS encoded links.
 * 
 * @author Chris Holgate
 */
class SSLServerAdaptor implements ServerHandle, Deferrable<SocketHandle, Void>, Signalable<SocketHandle> {
  private final Reactor reactor;
  private final Logger logger;
  private final SocketService socketService;
  private final SSLContext sslContext;
  private final ServerHandleCore transportServer;
  private Signal<SocketHandle> acceptSignal;

  /**
   * Provides the protected constructor for creating new SSL socket server adaptor
   * instances. This should only be called from the associated socket service
   * implementation.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   * @param logger This is the log service which is to be used for message
   *   logging.
   * @param socketService This is the socket service which was responsible for
   *   creating the SSL socket adaptor.
   * @param sslContext This is the SSL context object which encapsulates the
   *   SSL/TLS parameters and configuration to be used.
   */
  SSLServerAdaptor(Reactor reactor, Logger logger, SocketService socketService, SSLContext sslContext) {
    this.reactor = reactor;
    this.logger = logger;
    this.socketService = socketService;
    this.sslContext = sslContext;
    transportServer = new ServerHandleCore(reactor, logger, socketService);
    acceptSignal = null;
  }

  /**
   * Initiates a server socket open request, binding to the specified local
   * address and port.
   * 
   * @param address This is the local address of the socket server.
   * @param port This is the local port number of the socket server.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to this server socket adaptor
   *   as its callback parameter.
   */
  Deferred<ServerHandle> open(InetAddress address, int port) {
    return transportServer.open(address, port).addCallback(x -> {
      return this;
    });
  }

  /*
   * Delegates ServerHandle.getServerId() to the transport server.
   */
  public String getServerId() {
    return transportServer.getServerId();
  }

  /*
   * Delegates ServerHandle.isOpen() to the transport server.
   */
  public boolean isOpen() {
    return transportServer.isOpen();
  }

  /*
   * Implements ServerHandle.addSocketAcceptor(...)
   */
  public synchronized boolean addSocketAcceptor(Signalable<SocketHandle> socketAcceptor) throws SocketClosedException {
    if (!transportServer.isOpen()) {
      throw new SocketClosedException("Attempted to add socket acceptor to closed server channel");
    }

    // Start accepting on adding the first socket acceptor.
    if (acceptSignal == null) {
      acceptSignal = reactor.newSignal();
      acceptSignal.subscribe(socketAcceptor);
      transportServer.addSocketAcceptor(this);
      return true;
    }

    // Add subsequent socket acceptors. These may not receive all socket requests.
    else {
      acceptSignal.subscribe(socketAcceptor);
      return false;
    }
  }

  /*
   * Implements ServerHandle.addSocketAcceptors(...)
   */
  public synchronized boolean addSocketAcceptors(Collection<Signalable<SocketHandle>> socketAcceptors)
      throws SocketClosedException {
    if (!transportServer.isOpen()) {
      throw new SocketClosedException("Attempted to add socket acceptors to closed server channel");
    }

    // Start accepting on adding the first set of socket acceptors.
    if (acceptSignal == null) {
      acceptSignal = reactor.newSignal();
      for (Signalable<SocketHandle> acceptor : socketAcceptors) {
        acceptSignal.subscribe(acceptor);
      }
      transportServer.addSocketAcceptor(this);
      return true;
    }

    // Add subsequent socket acceptors. These may not receive all socket requests.
    else {
      for (Signalable<SocketHandle> acceptor : socketAcceptors) {
        acceptSignal.subscribe(acceptor);
      }
      return false;
    }
  }

  /*
   * Implements ServerHandle.close()
   */
  public synchronized Deferred<Boolean> close() {
    if (acceptSignal != null) {
      acceptSignal.signalFinalize(null);
      acceptSignal = null;
      return transportServer.close();
    } else {
      return reactor.callDeferred(false);
    }
  }

  /*
   * Signal notification on accepting a new transport socket connection. Creates
   * an SSL/TLS adaptor for the socket and then initiates the handshake.
   */
  public void onSignal(Signal<SocketHandle> signalId, SocketHandle transportSocket) {
    SSLSocketOpener adaptorOpener = new SSLSocketOpener(reactor, logger, socketService, sslContext);
    adaptorOpener.openAdaptor(transportSocket, false).addDeferrable(this, true);
  }

  /*
   * Callback on opening the SSL/TLS adaptor. Notify subscribers of the new socket
   * handle.
   */
  public synchronized Void onCallback(Deferred<SocketHandle> deferred, SocketHandle socketHandle) {
    if (acceptSignal != null) {
      acceptSignal.signal(socketHandle);
    }
    return null;
  }

  /*
   * Callback on failing to open the transport socket. Log the error condition but
   * do not notify the socket to subscribers.
   */
  public synchronized Void onErrback(Deferred<SocketHandle> deferred, Exception error) {
    logger.log(Level.INFO, "Failed to open SSL/TLS connection", error);
    return null;
  }
}
