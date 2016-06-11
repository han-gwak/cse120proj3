package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;
import java.util.LinkedList;
import java.util.HashMap;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
  /**
   * Allocate a new VM kernel.
   */
  public VMKernel() {
    super();
  }

  /**
   * Initialize this kernel.
   */
  public void initialize(String[] args) {
    super.initialize(args);
    
    invTable = new PhysicalPage[Machine.
      processor().getNumPhysPages()];
    for(int i = 0; i < Machine.processor().getNumPhysPages(); i++) {
      invTable[i] = new PhysicalPage();
    }
    swapFile = ThreadedKernel.fileSystem.open("swap.nachos", true);

    swapLock = new Lock();
    swapFull = new Condition(swapLock);
    
    // initialize linkedlist with 16 page numbers
    freeSwapPages = new LinkedList<Integer>();
    for(int i = 0; i < 16; i++) {
      freeSwapPages.add((Integer) i);
    }
    vpnSwapMap = new HashMap<Integer, Integer>();
    spnProcMap = new HashMap<Integer, UserProcess>();
  }

  /**
   * Test this kernel.
   */
  public void selfTest() {
    super.selfTest();
  }

  /**
   * Start running user programs.
   */
  public void run() {
    super.run();
  }

  /**
   * Terminate this kernel. Never returns.
   */
  public void terminate() {
    super.terminate();

    swapFile.close();
    ThreadedKernel.fileSystem.remove("swap.nachos");
  }

  // dummy variables to make javac smarter
  private static VMProcess dummy1 = null;
  private static final char dbgVM = 'v';

  // inverted page table; indexes are ppn
  public static PhysicalPage[] invTable;

  // file with swap data
  public static OpenFile swapFile;

  // list of page numbers in swap file
  public static LinkedList<Integer> freeSwapPages;
  public static HashMap<Integer, UserProcess> spnProcMap;
  public static HashMap<Integer, Integer> vpnSwapMap;
  public static Condition unpinnedPage;
  public static Lock swapLock;
  public static Condition swapFull;

  // data structure for a physical page
  public class PhysicalPage
  {
    public int vpn;
    public UserProcess proc;
    public boolean pinned = false;

    public PhysicalPage()
    {
    }
  }
}