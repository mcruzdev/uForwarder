package com.uber.data.kafka.consumerproxy.worker.limiter;

import com.netflix.concurrency.limits.Limiter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * AdaptiveInflightLimiter limits the number of infligt requests with floating limit. VegasLimit is
 * used to determine limit according to request latency and result
 *
 * <p>see {@link InflightLimiter}
 */
public abstract class AdaptiveInflightLimiter extends AbstractInflightLimiter {
  private final AtomicInteger queueLength;

  public AdaptiveInflightLimiter() {
    this.queueLength = new AtomicInteger(0);
  }

  /**
   * Sets upper limit of adaptive inflight limit
   *
   * @param maxInflight the max inflight
   */
  public abstract void setMaxInflight(int maxInflight);

  /**
   * internal implementation of tryAcquire
   *
   * @return the optional
   */
  abstract Optional<Limiter.Listener> tryAcquireImpl();

  @Override
  @Nullable
  Permit doAcquire(AcquireMode acquireMode) throws InterruptedException {
    if (!isClosed.get()) {
      synchronized (lock) {
        queueLength.incrementAndGet();
        try {
          while (!isClosed.get()) {
            Optional<Limiter.Listener> listener = tryAcquireImpl();
            if (listener.isPresent()) {
              return new AdaptivePermit(listener.get());
            } else if (acquireMode == AcquireMode.DryRun) {
              return NoopPermit.INSTANCE;
            } else if (acquireMode == AcquireMode.NonBlocking) {
              // unblock caller if failed to get permit
              return null;
            }

            // if not get permitted, wait for the unlock signal and try acquire permit again.
            lock.wait();
          }
        } finally {
          queueLength.decrementAndGet();
        }
      }
    }

    // Always permit if limiter is closed
    return NoopPermit.INSTANCE;
  }

  abstract class Metrics extends AbstractInflightLimiter.Metrics {
    @Override
    public long getBlockingQueueSize() {
      return queueLength.get();
    }
  }

  private class AdaptivePermit extends InflightLimiter.AbstractPermit {
    private Limiter.Listener listener;

    private AdaptivePermit(Limiter.Listener listener) {
      this.listener = listener;
    }

    @Override
    protected boolean doComplete(Result result) {
      switch (result) {
        case Dropped:
          listener.onDropped();
          break;
        case Succeed:
          listener.onSuccess();
          break;
        case Failed:
          listener.onIgnore();
          break;
        default:
          // Noop
      }
      /** unblock one blocking thread if there is any */
      synchronized (lock) {
        lock.notify();
      }
      return true;
    }
  }

  /** Builder builds AdaptiveInflightLimiter */
  public interface Builder {
    default Builder withLogEnabled(boolean logEnabled) {
      return this;
    }

    AdaptiveInflightLimiter build();
  }
}
