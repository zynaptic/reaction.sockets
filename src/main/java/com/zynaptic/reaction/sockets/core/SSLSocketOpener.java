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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import com.zynaptic.reaction.Deferrable;
import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.Threadable;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements the SSL client socket opener state machine. This carries out the
 * SSL client socket setup process, returning an opened socket handle on
 * completion.
 * 
 * @author Chris Holgate
 */
final class SSLSocketOpener implements Threadable<SocketHandle, SocketHandle>, Deferrable<SocketHandle, Void> {
  private final Reactor reactor;
  private final Logger logger;
  private final SocketService socketService;
  private final SSLContext sslContext;
  private InetAddress address = null;
  private int port = 0;
  private boolean isClient = true;
  private Deferred<SocketHandle> deferredOpen = null;
  private SocketHandle transportSocket = null;

  /**
   * Provides the protected constructor for creating new SSL client socket opener
   * instances. This should only be called from the associated socket service
   * implementation.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   * @param logger This is the log service which is to be used for message
   *   logging.
   * @param socketService This is the socket service which was responsible for
   *   creating the socket handle.
   * @param sslContext This is the SSL context instance which specifies the
   *   SSL/TLS configuration to be used for the connection.
   */
  SSLSocketOpener(Reactor reactor, Logger logger, SocketService socketService, SSLContext sslContext) {
    this.reactor = reactor;
    this.logger = logger;
    this.socketService = socketService;
    this.sslContext = sslContext;
  }

  /**
   * Initiates a socket open request on the client socket, using the specified
   * remote address and port. This uses the SSL context information previously
   * assigned to the constructor.
   * 
   * @param address This is the remote address of the socket server to which this
   *   client is connecting.
   * @param port This is the remote port number of the socket server to which this
   *   client is connecting.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to the SSL socket handle as its
   *   callback parameter.
   */
  synchronized Deferred<SocketHandle> openClient(InetAddress address, int port) {
    this.address = address;
    this.port = port;
    deferredOpen = reactor.newDeferred();
    socketService.openClient(address, port).addDeferrable(this, true);
    return deferredOpen.makeRestricted();
  }

  /**
   * Initiates an SSL adaptor open request, given an existing transport socket.
   * 
   * @param transportSocket This is the opened transport socket which will be used
   *   by the SSL adaptor to transfer wrapped data.
   * @param isClient this is a boolean flag which when set to 'true' indicates
   *   that the SSL endpoint is a client and when set to 'false' indicates that
   *   the SSL endpoint is a server.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to the SSL socket handle as its
   *   callback parameter.
   */
  synchronized Deferred<SocketHandle> openAdaptor(SocketHandle transportSocket, boolean isClient) {
    this.transportSocket = transportSocket;
    this.isClient = isClient;
    deferredOpen = reactor.newDeferred();
    reactor.runThread(this, transportSocket).addDeferrable(this, true);
    return deferredOpen.makeRestricted();
  }

  /*
   * Run SSL adaptor setup in an independent thread to avoid blocking the main
   * event loop thread.
   */
  public synchronized SocketHandle run(SocketHandle transportSocket) throws SSLException {
    SSLEngine sslEngine;
    if (address != null) {
      sslEngine = sslContext.createSSLEngine(address.getCanonicalHostName(), port);
    } else {
      sslEngine = sslContext.createSSLEngine();
    }
    sslEngine.setUseClientMode(isClient);
    SSLSocketAdaptor socketAdaptor = new SSLSocketAdaptor(reactor, logger, socketService);
    socketAdaptor.startHandshake(transportSocket, sslEngine);
    return socketAdaptor;
  }

  /*
   * Callback processing after opening the initial transport socket and then
   * subsequently running SSL adaptor setup.
   */
  public synchronized Void onCallback(Deferred<SocketHandle> deferred, SocketHandle socketHandle) {

    // Callback after opening the transport socket.
    if (transportSocket == null) {
      transportSocket = socketHandle;
      reactor.runThread(this, socketHandle).addDeferrable(this, true);
    }

    // Callback after performing the SSL adaptor setup.
    else if (deferredOpen != null) {
      deferredOpen.callback(socketHandle);
      deferredOpen = null;
    }
    return null;
  }

  /*
   * Error callback processing on failing to open the initial transport socket or
   * failing to run SSL adaptor setup.
   */
  public synchronized Void onErrback(Deferred<SocketHandle> deferred, Exception error) {
    if (transportSocket != null) {
      transportSocket.close().discard();
      transportSocket = null;
    }
    if (deferredOpen != null) {
      deferredOpen.errback(error);
      deferredOpen = null;
    }
    return null;
  }
}
