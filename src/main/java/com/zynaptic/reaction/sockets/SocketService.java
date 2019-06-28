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

package com.zynaptic.reaction.sockets;

import java.net.InetAddress;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLContext;

import com.zynaptic.reaction.Deferred;

/**
 * Defines the socket service interface which is used to manage socket
 * connections and their associated byte buffers.
 * 
 * @author Chris Holgate
 */
public interface SocketService {

  /**
   * Obtains a byte buffer with the specified capacity. The returned buffer will
   * have a capacity that matches or exceeds the requested capacity, with the
   * 'position' indicator being set to the start of the buffer and the 'limit'
   * indicator being set to the requested capacity.
   * 
   * @param bufferCapacity This is the minimum capacity of the requested buffer.
   * @return Returns a reference to a byte buffer of the specified capacity.
   */
  public ByteBuffer getByteBuffer(int bufferCapacity);

  /**
   * Obtains a byte buffer which contains the specified data array. The returned
   * buffer will have a capacity that matches or exceeds the length of the data
   * array, with the contents of the data array being placed at the start of the
   * buffer. The 'position' indicator will be set to the start of the buffer and
   * the 'limit' indicator will be set to the end of the inserted data array.
   * 
   * @param bufferData This is the data array which is to be inserted into the
   *   start of the buffer.
   * @return Returns a reference to a byte buffer that contains the data specified
   *   in the input parameter.
   */
  public ByteBuffer getByteBuffer(byte[] bufferData);

  /**
   * Releases a byte buffer which is no longer in use by the application. The
   * buffer will be appended to an unused buffer list so that it can be reused on
   * subsequent calls to {@link SocketService#getByteBuffer(int)}.
   * 
   * @param byteBuffer This is a reference to the byte buffer which is no longer
   *   in use by the application.
   */
  public void releaseByteBuffer(ByteBuffer byteBuffer);

  /**
   * Initiates a client socket open request, using the specified socket server
   * remote address and port.
   * 
   * @param address This is the remote address of the socket server to which the
   *   new client is connecting.
   * @param port This is the remote port number of the socket server to which the
   *   new client is connecting.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to a newly created client
   *   socket handle as its callback parameter.
   */
  public Deferred<SocketHandle> openClient(InetAddress address, int port);

  /**
   * Initiates a socket server open request, using the specified local address and
   * port. The new socket server will be bound to the specified port and will
   * start accepting client connection requests to that port once at least one
   * socket acceptance handler has been attached to it.
   * 
   * @param address This is the local address to which the new socket server will
   *   be bound.
   * @param port This is the local port number to which the new socket server will
   *   be bound.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to a newly created socket
   *   server handle.
   */
  public Deferred<ServerHandle> openServer(InetAddress address, int port);

  /**
   * Initiates an SSL/TLS client socket open request, using the specified socket
   * server remote address and port. The SSL/TLS parameters and configuration to
   * use are encapsulated by the supplied SSL context object.
   * 
   * @param address This is the remote address of the socket server to which the
   *   new client is connecting.
   * @param port This is the remote port number of the socket server to which the
   *   new client is connecting.
   * @param sslContext This is the SSL context object which encapsulates the
   *   SSL/TLS parameters and configuration to be used.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to a newly created client
   *   socket handle as its callback parameter.
   */
  public Deferred<SocketHandle> openSSLClient(InetAddress address, int port, SSLContext sslContext);

  /**
   * Initiates an SSL/TLS socket server open request, using the specified local
   * address and port. The SSL/TLS parameters and configuration to use are
   * encapsulated by the supplied SSL context object. The new socket server will
   * be bound to the specified port and will start accepting client connection
   * requests to that port once at least one socket acceptance handler has been
   * attached to it.
   * 
   * @param address This is the local address to which the new socket server will
   *   be bound.
   * @param port This is the local port number to which the new socket server will
   *   be bound.
   * @param sslContext This is the SSL context object which encapsulates the
   *   SSL/TLS parameters and configuration to be used.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to a newly created socket
   *   server handle.
   */
  public Deferred<ServerHandle> openSSLServer(InetAddress address, int port, SSLContext sslContext);

  /**
   * Initiates an SSL/TLS socket adaptor open request, using an existing transport
   * socket. The SSL/TLS parameters and configuration to use are encapsulated by
   * the supplied SSL context object.
   * 
   * @param transportSocket This is the existing transport socket which should be
   *   used by the SSL/TLS protocol. It should already be open and ready to
   *   transfer data.
   * @param sslContext This is the SSL context object which encapsulates the
   *   SSL/TLS parameters and configuration to be used.
   * @param isClient This is a boolean flag which when set to 'true' indicates
   *   that the SSL/TLS adaptor should act as a protocol client and when set to
   *   'false' indicates that it should act as a protocol server.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to a newly created SSL/TLS
   *   adaptor handle as its callback parameter.
   */
  public Deferred<SocketHandle> openSSLAdaptor(SocketHandle transportSocket, SSLContext sslContext, boolean isClient);

}
