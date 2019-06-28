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
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.logging.Level;

import javax.net.ssl.SSLContext;

import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.sockets.ServerHandle;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements the core functionality of the socket service API.
 * 
 * @author Chris Holgate
 */
public final class SocketServiceCore implements SocketService {

  // Specify the identifier to be used for generated log messages.
  private static final String LOGGER_ID = "com.zynaptic.reaction.socket";

  // Specify the buffer capacity used when allocating small buffers (of requested
  // length less than or equal to SMALL_BUFFER_SIZE bytes).
  private static final int SMALL_BUFFER_SIZE = 128;

  // Specify the buffer capacity used when allocating medium buffers (of requested
  // length greater than SMALL_BUFFER_SIZE but less than or equal to
  // MEDIUM_BUFFER_SIZE bytes).
  private static final int MEDIUM_BUFFER_SIZE = 1024;

  private final Reactor reactor;
  private final Logger logger;
  private final LinkedList<ByteBuffer> smallBufferList;
  private final LinkedList<ByteBuffer> mediumBufferList;
  private final LinkedList<ByteBuffer> largeBufferList;

  /**
   * Provides the public constructor for the socket service instance.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   */
  public SocketServiceCore(Reactor reactor) {
    this.reactor = reactor;
    this.logger = reactor.getLogger(LOGGER_ID);
    this.smallBufferList = new LinkedList<ByteBuffer>();
    this.mediumBufferList = new LinkedList<ByteBuffer>();
    this.largeBufferList = new LinkedList<ByteBuffer>();
  }

  /*
   * Implements SocketService.getByteBuffer(...)
   */
  public ByteBuffer getByteBuffer(int bufferCapacity) {
    ByteBuffer buffer;

    // Reuse or create buffers of size SMALL_BUFFER_SIZE for requests that are
    // smaller than or equal to SMALL_BUFFER_SIZE bytes.
    if (bufferCapacity <= SMALL_BUFFER_SIZE) {
      synchronized (smallBufferList) {
        if (smallBufferList.isEmpty()) {
          buffer = ByteBuffer.allocateDirect(SMALL_BUFFER_SIZE);
          logger.log(Level.FINEST,
              "Allocated buffer capacity " + SMALL_BUFFER_SIZE + " for requested size " + bufferCapacity);
        } else {
          buffer = smallBufferList.removeFirst();
        }
      }
    }

    // Reuse or create buffers of size MEDIUM_BUFFER_SIZE for requests that are
    // larger than SMALL_BUFFER_SIZE but smaller than or equal to SMALL_BUFFER_SIZE
    // bytes.
    else if (bufferCapacity <= MEDIUM_BUFFER_SIZE) {
      synchronized (mediumBufferList) {
        if (mediumBufferList.isEmpty()) {
          buffer = ByteBuffer.allocateDirect(MEDIUM_BUFFER_SIZE);
          logger.log(Level.FINEST,
              "Allocated buffer capacity " + MEDIUM_BUFFER_SIZE + " for requested size " + bufferCapacity);
        } else {
          buffer = mediumBufferList.removeFirst();
        }
      }
    }

    // Reuse or create buffers for requests that are larger than MEDIUM_BUFFER_SIZE.
    // Existing buffers that are smaller than the requested size will be discarded.
    // Therefore new buffers are created with an extended capacity to reduce the
    // likelihood of having to discard buffers in this manner.
    else {
      synchronized (largeBufferList) {
        int extendedCapacity = (bufferCapacity * (SMALL_BUFFER_SIZE + 1)) / SMALL_BUFFER_SIZE;
        if (largeBufferList.isEmpty()) {
          buffer = ByteBuffer.allocateDirect(extendedCapacity);
          logger.log(Level.FINEST,
              "Allocated buffer capacity " + extendedCapacity + " for requested size " + bufferCapacity);
        } else {
          buffer = largeBufferList.removeFirst();
          if (buffer.capacity() < bufferCapacity) {
            logger.log(Level.FINEST, "Discarded direct buffer capacity " + buffer.capacity());
            buffer = ByteBuffer.allocateDirect(extendedCapacity);
            logger.log(Level.FINEST,
                "Allocated buffer capacity " + extendedCapacity + " for requested size " + bufferCapacity);
          }
        }
      }
    }
    buffer.limit(bufferCapacity);
    return buffer;
  }

  /*
   * Implements SocketService.getByteBuffer(...)
   */
  public ByteBuffer getByteBuffer(byte[] bufferData) {
    ByteBuffer buffer = getByteBuffer(bufferData.length);
    buffer.put(bufferData, 0, bufferData.length);
    buffer.flip();
    return buffer;
  }

  /*
   * Implements SocketService.releaseByteBuffer(...)
   */
  public void releaseByteBuffer(ByteBuffer byteBuffer) {
    if (byteBuffer.isDirect()) {
      byteBuffer.clear();
      logger.log(Level.FINEST, "Released direct buffer length " + byteBuffer.capacity());
      if (byteBuffer.capacity() == SMALL_BUFFER_SIZE) {
        synchronized (smallBufferList) {
          smallBufferList.addLast(byteBuffer);
        }
      } else if (byteBuffer.capacity() == MEDIUM_BUFFER_SIZE) {
        synchronized (mediumBufferList) {
          mediumBufferList.addLast(byteBuffer);
        }
      } else {
        synchronized (largeBufferList) {
          largeBufferList.addLast(byteBuffer);
        }
      }
    } else {
      logger.log(Level.FINEST, "Discarded wrapped buffer length " + byteBuffer.capacity());
    }
  }

  /*
   * Implements SocketService.openClient(...)
   */
  public Deferred<SocketHandle> openClient(InetAddress address, int port) {
    ClientSocketOpener clientOpener = new ClientSocketOpener(reactor, logger, this);
    return clientOpener.open(address, port);
  }

  /*
   * Implements SocketService.openServer(...)
   */
  public Deferred<ServerHandle> openServer(InetAddress address, int port) {
    ServerHandleCore serverHandle = new ServerHandleCore(reactor, logger, this);
    return serverHandle.open(address, port);
  }

  /*
   * Implements SocketService.openSSLClient(...)
   */
  public Deferred<SocketHandle> openSSLClient(InetAddress address, int port, SSLContext sslContext) {
    SSLSocketOpener clientOpener = new SSLSocketOpener(reactor, logger, this, sslContext);
    return clientOpener.openClient(address, port);
  }

  /*
   * Implements SocketService.openSSLServer(...)
   */
  public Deferred<ServerHandle> openSSLServer(InetAddress address, int port, SSLContext sslContext) {
    SSLServerAdaptor serverAdaptor = new SSLServerAdaptor(reactor, logger, this, sslContext);
    return serverAdaptor.open(address, port);
  }

  /*
   * Implements SocketService.openSSLAdaptor(...)
   */
  public Deferred<SocketHandle> openSSLAdaptor(SocketHandle transportSocket, SSLContext sslContext, boolean isClient) {
    SSLSocketOpener adaptorOpener = new SSLSocketOpener(reactor, logger, this, sslContext);
    return adaptorOpener.openAdaptor(transportSocket, isClient);
  }
}
