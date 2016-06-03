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
    syncEntries(true); // sync TLB bits and invalidate entries
  }

  /**
   * Syncs bits of all valid TLB entries to page table.
   * Invalidates all TLB entries if invalidate = true
   */
  public void syncEntries(boolean invalidate) {
    // update page table
    for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
      TranslationEntry tlbe = Machine.processor().readTLBEntry(i);
      TranslationEntry pte = pageTable[tlbe.vpn];

      // sync all valid TLB entries 
      if(tlbe.valid) {
        pte.used = tlbe.used;
        pte.dirty = tlbe.dirty;

        if(invalidate)
          tlbe.valid = false;
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
          false, true, false, false);
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

  /**
   * Chooses page to evict when no free pages. Returns ppn.
   * FIFO but skips pages where used == true.
   * Note: used is equivalent to recently used
   */
  private int clockReplacement() {
    // FIFO but skip pages with used bit set
    while(pageTable[VMKernel.invTable[clockVictim].te.vpn].used) {
      pageTable[VMKernel.invTable[clockVictim].te.vpn].used = false;
      clockVictim = (clockVictim + 1) % VMKernel.invTable.length;
    }

    int result = clockVictim;
    clockVictim = (clockVictim + 1) % VMKernel.invTable.length;    
    return result;
  }

  /**
   * Loops through TLB/page table to see if any entries 
   * have mapping to specified ppn; invalidates them if so.
   */
  private void invalidateVictimPage(int ppn) {
    for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
      TranslationEntry tlbe = Machine.processor().readTLBEntry(i);
      if(tlbe.ppn == ppn) {
        tlbe.valid = false;
        pageTable[tlbe.vpn].valid = false; // indicates page swapped out
      }
    }
  }

  private boolean swapRead(int spn, int ppn);
  private boolean swapWrite(int spn, int ppn);

  private void handleTLBMiss() {
    int ppn;
    int spn;

    // get/allocate page table entry from virtual address
    int vaddr = Machine.processor().readRegister(Processor.regBadVAddr);
    int vpn = Processor.pageFromAddress(vaddr);
    Lib.assertTrue(vpn >= 0 && vpn < numPages);
    TranslationEntry pte = pageTable[vpn];

    // page fault
    if(!pte.valid) {
    
      // allocate PTE and inverted PTE if free page available
      if(UserKernel.freePages.size() > 0) {
        ppn = ((Integer)UserKernel.freePages.removeFirst()).intValue();
      }

      else {
        // sync entries and choose victim
        syncEntries(false);
        ppn = clockReplacement();

        // swap out if page written to; insert page position into list
        if(pageTable[VMKernel.invTable[ppn].te.vpn].dirty) {
          // TODO: check for race condition; maybe put in separate helper method
          // TODO: if freeSwapPages.size() < 0, have to wait
          // TODO: implement in methods swapRead/swapWrite
          spn = (int) VMKernel.freeSwapPages.remove();
          VMKernel.swapFile.write(spn*pageSize, Machine.processor().getMemory(), 
            ppn*pageSize, pageSize);
        }
        invalidateVictimPage(ppn);
        // TODO: save spn as translationentry.ppn for the pageTable entry that was invalidated
        // TODO: how to record relation between vpn and spn? maybe keep spn as var in PhysicalPage
      }

      // update PTE; check read-only section
      pte.ppn = ppn;
      pte.valid = true;
      // pte.readonly = 

      // update invTable; check if pinned section
      VMKernel.invTable[ppn].te = pte;
      VMKernel.invTable[ppn].proc = this;
      // invTable[ppn].pinned = 
    }

    boolean tlbFull = true;
    int teIndex = 0;

    // allocate invalid/unused tlb entry
    for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
      if(!Machine.processor().readTLBEntry(i).valid) {
        tlbFull = false;
        teIndex = i;
        break;
      }
    }

    // evict and sync TLB entry but don't invalidate all
    if(tlbFull) {
      teIndex = Lib.random(Machine.processor().getTLBSize());
      syncEntries(false);
    }

    // update TLB entry
    TranslationEntry tlbe = new TranslationEntry(pte);
    tlbe.valid = true;
    Machine.processor().writeTLBEntry(teIndex, tlbe);

    // initialize inverted page table entry for page
    VMKernel.PhysicalPage physPage = VMKernel.invTable[tlbe.ppn];
    physPage.vpn = vpn;;
    physPage.proc = this;
    //physPage.pinned
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

  private static int clockVictim = 0;

  private static final char dbgProcess = 'a';

  private static final char dbgVM = 'v';
}
