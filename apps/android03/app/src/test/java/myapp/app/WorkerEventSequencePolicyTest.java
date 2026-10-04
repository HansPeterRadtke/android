package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class WorkerEventSequencePolicyTest {
  @Test public void sequencesAreScopedToWorkerIdentity() {
    WorkerEventSequencePolicy policy = new WorkerEventSequencePolicy();
    assertTrue(policy.accept("worker-a", 7));
    assertFalse(policy.accept("worker-a", 7));
    assertFalse(policy.accept("worker-a", 6));
    assertTrue(policy.accept("worker-b", 1));
    assertTrue(policy.accept("worker-a", 8));
  }

  @Test public void unsequencedStatusMessagesRemainDeliverable() {
    WorkerEventSequencePolicy policy = new WorkerEventSequencePolicy();
    assertTrue(policy.accept("worker-a", 0));
    assertTrue(policy.accept("", 0));
  }
}
