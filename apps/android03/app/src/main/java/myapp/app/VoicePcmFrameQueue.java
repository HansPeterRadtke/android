package myapp.app;

import java.util.ArrayDeque;

/**
 * Bounded single-producer/single-consumer PCM queue.
 *
 * The microphone thread only copies a small frame here. Network writes run
 * on a different thread. Exhaustion is an explicit failure, never a silent
 * claim that all audio was sent. The queue is memory-only: it is not a durable
 * recording, and the application must not claim server delivery for it.
 */
final class VoicePcmFrameQueue {
  private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
  private final long capacityBytes;
  private long pendingBytes;
  private long capturedBytes;
  private long acceptedBytes;
  private long sentBytes;
  private long refusedFrames;
  private boolean closed;

  VoicePcmFrameQueue(long capacityBytes) {
    if (capacityBytes <= 0 || capacityBytes > 256L * 1024L * 1024L)
      throw new IllegalArgumentException("invalid PCM capacity");
    this.capacityBytes = capacityBytes;
  }

  synchronized boolean offer(byte[] samples, int length) {
    if (samples == null || length <= 0 || length > samples.length || (length & 1) != 0)
      throw new IllegalArgumentException("invalid PCM frame");
    capturedBytes += length;
    if (closed || length > capacityBytes || pendingBytes > capacityBytes - length) {
      refusedFrames++;
      return false;
    }
    byte[] copy = new byte[length];
    System.arraycopy(samples, 0, copy, 0, length);
    pending.addLast(copy);
    pendingBytes += length;
    acceptedBytes += length;
    notifyAll();
    return true;
  }

  synchronized byte[] peek() { return pending.peekFirst(); }

  synchronized boolean commitAcceptedHead(byte[] expected) {
    if (expected == null || pending.peekFirst() != expected) return false;
    pending.removeFirst();
    pendingBytes -= expected.length;
    sentBytes += expected.length;
    notifyAll();
    return true;
  }

  synchronized byte[] awaitHead(long waitMs) throws InterruptedException {
    if (pending.isEmpty() && !closed && waitMs > 0) wait(waitMs);
    return pending.peekFirst();
  }

  synchronized byte[] poll() {
    byte[] frame = pending.pollFirst();
    if (frame != null) pendingBytes -= frame.length;
    return frame;
  }

  synchronized void requeueFirst(byte[] frame) {
    if (frame == null || frame.length == 0) return;
    if (pendingBytes > capacityBytes - frame.length)
      throw new IllegalStateException("cannot restore unsent microphone samples");
    pending.addFirst(frame);
    pendingBytes += frame.length;
    notifyAll();
  }

  synchronized void acknowledgeSocketAccepted(int length) {
    if (length <= 0) throw new IllegalArgumentException("invalid frame length");
    sentBytes += length;
  }

  synchronized void close() { closed = true; notifyAll(); }
  synchronized boolean isClosed() { return closed; }
  synchronized long pendingBytes() { return pendingBytes; }
  synchronized long acceptedBytes() { return acceptedBytes; }
  synchronized long capturedBytes() { return capturedBytes; }
  synchronized long sentBytes() { return sentBytes; }
  synchronized long refusedFrames() { return refusedFrames; }
  synchronized int pendingFrames() { return pending.size(); }
  synchronized long capacityBytes() { return capacityBytes; }
}
