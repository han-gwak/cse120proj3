package nachos.threads;

import nachos.machine.*;
import java.util.Comparator;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Iterator;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {

  // use with iterator to sort by wakeTime
  public Queue<AlarmThread> queue;

	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
    queue = new LinkedList<AlarmThread>();
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {

    // sort queue by wakeTime of each thread
/*    sort(queue, new Comparator<AlarmThread>() {
      @Override
      public int compare(AlarmThread left, AlarmThread right) {
        return (left.wakeTime < right.wakeTime) ? 1 : -1;
      }
    });
*/
    Iterator<AlarmThread> it = queue.iterator();
    long currentTime = Machine.timer().getTime();

    // check each of queued threads have slept long enough
    while (it.hasNext()) {
      AlarmThread at = it.next(); // does not necessarily remove

      // put into ready queue if past waitTime; otherwise skip
      if(at.wakeTime <= currentTime) {
        it.remove();
        at.kt.ready();
      }
    }

    // force context switch if another thread should be run
		KThread.currentThread().yield();
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
    boolean intStatus = Machine.interrupt().disable();

    // save thread and time to use in queue
    KThread currentThread = KThread.currentThread();
    long wakeTime = Machine.timer().getTime() + x;

    // queue current thread, put to sleep
    queue.add(new AlarmThread(currentThread, wakeTime));
    currentThread.sleep();
    Machine.interrupt().restore(intStatus);
	}

  public static void selfTest() {
    KThread t1 = new KThread(new Runnable() {
      public void run() {
        long time1 = Machine.timer().getTime();
        int waitTime = 10000;
        System.out.println("Thread calling wait at time:" + time1);
        ThreadedKernel.alarm.waitUntil(waitTime);
        System.out.println("Thread woken up after:" + (Machine.timer().getTime() - time1));
        Lib.assertTrue((Machine.timer().getTime() - time1) >= waitTime, " thread woke up too early.");
      }
    });
    t1.setName("T1");
    t1.fork();
    t1.join();
  }

  // Class used for queue in Alarm
  class AlarmThread {
    KThread kt;
    long wakeTime;

    public AlarmThread() {
      kt = null;
      wakeTime = 0;
    }

    public AlarmThread(KThread thread, long l) {
      kt = thread;
      wakeTime = l;
    }
  }

}
