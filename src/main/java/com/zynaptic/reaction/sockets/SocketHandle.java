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

import java.nio.ByteBuffer;

import com.zynaptic.reaction.Deferred;

/**
 * Defines the socket handle interface which is used to interact with a single
 * client or server socket connection.
 * 
 * @author Chris Holgate
 */
public interface SocketHandle {

  /**
   * Accesses the socket identifier string which is associated with the socket
   * handle. This will typically be made up of the address and port number of the
   * underlying network connection.
   * 
   * @return Returns the socket identifier associated with the socket handle.
   */
  public String getSocketId();

  /**
   * Determines whether the socket is open for reading and writing data.
   * 
   * @return Returns a boolean value which will be set to 'true' if the socket is
   *   open for reading and writing data and 'false' otherwise.
   */
  public boolean isOpen();

  /**
   * Initiates a write to the socket using a given write buffer of standard Java
   * type {@link ByteBuffer}. The data held in the buffer between the 'position'
   * and 'limit' indicators is the data that will be written to the socket. Only a
   * single write transaction may be active at any given time. Write completion is
   * indicated via the returned deferred event object. If everything in the buffer
   * could be written it will automatically be recycled and a 'null' reference
   * will be passed in the deferred callback. Otherwise a buffer that contains the
   * unwritten data between the 'position' and 'limit' indicators will be passed
   * in the deferred callback. TODO: Check all implementations to see if the
   * buffer returned in this manner can always be assumed to be the same buffer
   * object as was passed as the input parameter.
   * 
   * @param writeBuffer This is the buffer that contains the data to be written to
   *   the socket.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion of the write operation. Callbacks will be issued
   *   with a 'null' reference if all the data was successfully written and will
   *   be issued with a buffer parameter containing the unwritten data if not all
   *   the data could be written. If an attempt is made to write to a socket that
   *   has been closed, an error callback will be issued that passes an exception
   *   of type {@link SocketClosedException}.
   */
  public Deferred<ByteBuffer> write(ByteBuffer writeBuffer);

  /**
   * Initiates a read to the socket using a specified read request size. Only a
   * single read transaction may be active at any given time. Read completion is
   * indicated via the returned deferred event object, where a read buffer of
   * standard Java type {@link ByteBuffer} will be passed in the deferred
   * callback. The data held in the buffer between the 'position' and 'limit'
   * indicators is the data that was read from the socket. Any number of bytes up
   * to the requested read size may be returned in this manner.
   * 
   * @param readSize This is the number of bytes which are being requested by the
   *   read operation.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion of the read operation. Callbacks will be issued with
   *   a buffer parameter containing the data read from the socket, up to a
   *   maximum of 'readSize' bytes. If an attempt is made to read from a socket
   *   that has been closed, an error callback will be issued that passes an
   *   exception of type {@link SocketClosedException}.
   */
  public Deferred<ByteBuffer> read(int readSize);

  /**
   * Initiates a read to the socket using a specified target buffer. Only a single
   * read transaction may be active at any given time. The target buffer is of
   * standard Java type {@link ByteBuffer} and may contain existing data between
   * the 'position' and 'limit' indicators. There must be sufficient space in the
   * buffer to hold at least one additional byte. Read completion is indicated via
   * the returned deferred event object, where a read buffer will be passed in the
   * deferred callback. The data held in the buffer between the 'position' and
   * 'limit' indicators is the data that was already present in the buffer
   * concatenated with the data then read from the socket. Any number of bytes up
   * to the buffer capacity may be returned in this manner. TODO: Check all
   * implementations to see if the buffer returned in this manner can always be
   * assumed to be the same buffer object as was passed as the input parameter.
   * 
   * @param readBuffer This is the buffer which is to be used as the target for
   *   the read operation.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion of the read operation. Callbacks will be issued with
   *   a buffer parameter containing the data read from the socket, up to the
   *   capacity of the supplied target buffer. If an attempt is made to read from
   *   a socket that has been closed, an error callback will be issued that passes
   *   an exception of type {@link SocketClosedException}.
   */
  public Deferred<ByteBuffer> read(ByteBuffer readBuffer);

  /**
   * Initiates a close operation on the socket. Only a single close request may be
   * active at any given time. Close completion is indicated via the returned
   * deferred event object, where a boolean status value will be passed as the
   * callback parameter. The status flag will be set to 'true' if the socket was
   * closed as a result of the request and 'false' if the socket was already
   * closed. TODO: Check all implementations to ensure they conform to this.
   * 
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion of the close operation. Callbacks will be issued
   *   with a boolean status parameter which will be set to 'true' if the socket
   *   was closed as a result of the close request and 'false' otherwise.
   */
  public Deferred<Boolean> close();

}
