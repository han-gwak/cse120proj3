package nachos.vm;


import java.util.Random;
import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
  /**
   * Allocate a new process.
   */
  public VMProcess() {
    super();
  }

  /**
   * Save the state of this process in preparation for a context switch.
   * Called by <tt>UThread.saveState()</tt>.
   */
  public void saveState() {
    // invalidate all TLB entries & update page table
    for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
      TranslationEntry tlbe = Machine.processor().readTLBEntry(i);
      TranslationEntry pte = pageTable[tlbe.vpn];

      // invalidate and sync all valid TLB entries 
      if(tlbe.valid) {
        tlbe.valid = false;  // TODO: find out if all valid should be set 
                             // to false in this method
        pte.used = tlbe.used;
        pte.dirty = tlbe.dirty;
      }
    }
  }

  /**
   * Restore the state of this process after a context switch. Called by
   * <tt>UThread.restoreState()</tt>.
   */
  public void restoreState() {
  }

  /**
   * Initializes page tables for this process so that the executable can be
   * demand-paged.
   * 
   * @return <tt>true</tt> if successful.
   */
  protected boolean loadSections() {
    // initialize pageTable with invalid readOnly entries with ppn -1
    pageTable = new TranslationEntry[numPages];
    int count = 0;
    while(count < numPages) {
      pageTable[count] = new TranslationEntry(count, -1,
          false, true, false, false)
        count++;
    }

    return true;
  }

  /**
   * Release any resources allocated by <tt>loadSections()</tt>.
   */
  protected void unloadSections() {
    super.unloadSections();
  }

  private void pageFaultHandler() {
    // allocate physical page if free page available
    if(!freePages.empty()) {
      int ppn = ((Integer)UserKernel.freePages.removeFirst()).intValue();
      pte.ppn = ppn;
      pte.valid = true;
    }

    // physical memory full; evict page to swap
    else {
      saveState(); // sync TLB entries TODO: update validity bits
      int victim = clockReplacement();
      // if(victim.dirty) -> swap out
      // invalidate pte and tlb entry of victim page (in IPT?)
    }
  }

  private int clockReplacement() {
    byte[] memory = Machine.processor().getMemory();
    int frames = Machine.processor().getNumPhysPages();
    // FIFO but skip pages with used bit set
    return 0;
  }

  private void handleTLBMiss() {
    boolean tlbFull = true;
    int teIndex = 0;

    // get/allocate page table entry from virtual address
    int vaddr = Machine.processor().readRegister(Processor.regBadVAddr);
    int vpn = Processor.pageFromAddress(vaddr);
    Lib.assertTrue(vpn >= 0 && vpn < numPages)
    TranslationEntry pte = pageTable[vpn];
    if(!pte.valid) {
      pageFaultHandler();
    }

    // allocate invalid/unused tlb entry
    for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
      if(!Machine.processor().readTLBEntry(i).valid) {
        tlbFull = false;
        teIndex = i;
        break;
      }
    }

    // evict TLB entry with Lib.random()
    if(tlbFull) {
      teIndex = Lib.random(Machine.processor().getTLBSize());
      saveState();
    }

    // update TLB entry
    TranslationEntry tlbe = new TranslationEntry(pte);
    tlbe.valid = true;
    Machine.processor().writeTLBEntry(teIndex, tlbe);
  }

  /**
   * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
   * . The <i>cause</i> argument identifies which exception occurred; see the
   * <tt>Processor.exceptionZZZ</tt> constants.
   * 
   * @param cause the user exception that occurred.
   */
  public void handleException(int cause) {
    Processor processor = Machine.processor();

    switch (cause) {
      case Processor.exceptionTLBMiss:
        handleTLBMiss();
        break;
      default:
        super.handleException(cause);
        break;
    }
  }

  private static final int pageSize = Processor.pageSize;

  private static final char dbgProcess = 'a';

  private static final char dbgVM = 'v';
}
