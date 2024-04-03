/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fury.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.util.Platform;

/**
 * A buffered stream by fury. Do not use original {@link InputStream} when this stream object
 * created. This stream will try to buffer data inside, the date read from original stream won't be
 * the data you expected. Use this stream as a wrapper instead.
 */
@NotThreadSafe
public class FuryInputStream extends InputStream implements FuryStreamReader {
  private static final int BUFFER_GROW_STEP_THRESHOLD = 100 * 1024 * 1024;
  private final InputStream stream;
  private final int bufferSize;
  private final MemoryBuffer buffer;

  public FuryInputStream(InputStream stream) {
    this(stream, 4096);
  }

  public FuryInputStream(InputStream stream, int bufferSize) {
    this.stream = stream;
    this.bufferSize = bufferSize;
    byte[] bytes = new byte[bufferSize];
    this.buffer = MemoryBuffer.fromByteArray(bytes, 0, 0, this);
  }

  @Override
  public int fillBuffer(int minFillSize) {
    MemoryBuffer buffer = this.buffer;
    byte[] heapMemory = buffer.getHeapMemory();
    int offset = buffer.size();
    int targetSize = offset + minFillSize;
    if (targetSize > heapMemory.length) {
      if (targetSize < BUFFER_GROW_STEP_THRESHOLD) {
        buffer.grow(targetSize * 2);
      } else {
        buffer.grow((int) (targetSize * 1.5));
      }
      heapMemory = buffer.getHeapMemory();
    }
    try {
      int read;
      read = stream.read(heapMemory, offset, Math.min(stream.available(), heapMemory.length));
      while (read < minFillSize) {
        int newRead = stream.read(heapMemory, offset + read, minFillSize - read);
        if (newRead < 0) {
          throw new IndexOutOfBoundsException("No enough data in the stream " + stream);
        }
        read += newRead;
      }
      buffer.increaseSize(read);
      return read;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void readTo(byte[] dst, int dstIndex, int len) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining >= len) {
      buf.readBytes(dst, dstIndex, len);
    } else {
      buf.readBytes(dst, dstIndex, remaining);
      len -= remaining;
      dstIndex += remaining;
      try {
        int read = stream.read(dst, dstIndex, len);
        while (read < len) {
          int newRead = stream.read(dst, dstIndex + read, len - read);
          if (newRead < 0) {
            throw new IndexOutOfBoundsException("No enough data in the stream " + stream);
          }
          read += newRead;
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void readToUnsafe(Object target, long targetPointer, int numBytes) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining < numBytes) {
      fillBuffer(numBytes - remaining);
    }
    byte[] heapMemory = buf.getHeapMemory();
    long address = buf.getUnsafeReaderAddress();
    Platform.copyMemory(heapMemory, address, target, targetPointer, numBytes);
    buf.increaseReaderIndexUnsafe(numBytes);
  }

  @Override
  public void readToByteBuffer(ByteBuffer dst, int pos, int length) {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining < length) {
      fillBuffer(length - remaining);
    }
    byte[] heapMemory = buf.getHeapMemory();
    dst.put(heapMemory, buf.unsafeHeapReaderIndex(), length);
    buf.increaseReaderIndexUnsafe(length);
  }

  @Override
  public MemoryBuffer getBuffer() {
    return buffer;
  }

  public InputStream getStream() {
    return stream;
  }

  /**
   * Shrink buffer to release memory, do not invoke this method is the deserialization for an object
   * didn't finish.
   */
  public void shrinkBuffer() {
    int remaining = buffer.remaining();
    int bufferSize = this.bufferSize;
    if (remaining > bufferSize || buffer.size() > bufferSize) {
      byte[] heapMemory = buffer.getHeapMemory();
      byte[] newBuffer = new byte[Math.max(bufferSize, remaining)];
      System.arraycopy(heapMemory, buffer.readerIndex(), newBuffer, 0, remaining);
      buffer.initHeapBuffer(newBuffer, 0, remaining);
      buffer.readerIndex(0);
    }
  }

  @Override
  public int read() throws IOException {
    MemoryBuffer buf = buffer;
    if (buf.remaining() > 0) {
      return buf.readByte() & 0xFF;
    }
    int available = stream.available();
    if (available > 0) {
      fillBuffer(1);
      return buf.readByte() & 0xFF;
    }
    return stream.read();
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining >= len) {
      buf.readBytes(b, off, len);
      return len;
    } else {
      buf.readBytes(b, off, remaining);
      return stream.read(b, off + remaining, len - remaining) + remaining;
    }
  }

  @Override
  public long skip(long n) throws IOException {
    MemoryBuffer buf = buffer;
    int remaining = buf.remaining();
    if (remaining >= n) {
      buf.increaseReaderIndex((int) n);
      return n;
    } else {
      buf.increaseReaderIndex(remaining);
      return stream.skip(n - remaining) + remaining;
    }
  }

  @Override
  public int available() throws IOException {
    return buffer.remaining() + stream.available();
  }

  @Override
  public void close() throws IOException {
    stream.close();
  }
}