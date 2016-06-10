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
   * Restore the state of this process after a context switch. Called by
   * <tt>UThread.restoreState()</tt>.
   */
  public void restoreState() {
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
        pageTable[tlbe.vpn].valid = false; // memory mapping no longer valid
      }
    }
  }

  /**
   * Reads specified swap page from file if not in freeSwapPages list.
   * Puts swap page index back into freeSwapPages list if successful.
   * Returns byte array of swap file data.
   */
  private byte[] swapRead(int spn) {
    return 0; // TODO
  }
  
  /**
   * Write specified ppn of current page table to swap file.
   * Returns swap page number.
   */
  private int swapWrite(int ppn) {

    // no free pages; wait for one to appear
    if(VMKernel.freeSwapPages.size() == 0) {
      // TODO: use condition variable here
    }

    // write to free swap page
    int spn = (int) VMKernel.freeSwapPages.remove();
    int result = VMKernel.swapFile.write(spn*pageSize, 
      Machine.processor().getMemory(), ppn*pageSize, pageSize);
    
    // update inverted page table entry if written successfully
    if(result >= 0) {
      VMKernel.vpnSwapMap.put(vpn, spn);
      VMKernel.spnProcMap.put(spn, VMKernel.invTable[ppn].proc);
      return spn;
    }

    return result;
  }

  /**
   * Allocate physical memory and set pageTable and invTable values
   * for entry at vpn/ppn.
   */
  private void allocateFrame(int vpn, int ppn) {
    TranslationEntry pte = pageTable[vpn];
    boolean readonly = false;
    boolean used = false;
    boolean dirty = false;
    boolean pinned = false;

    // swap out already handled in handlePageFault
    
    // begin allocating physical memory based on pte bits; dirty = swap in
    if(pageTable[vpn].dirty && VMKernel.vpnSwapMap.containsKey((Integer) vpn)) {
      spn = (int) VMKernel.vpnSwapMap.get((Integer) vpn);

      // write in from swap page if spn valid and same process
      if(VMKernel.spnProcMap.containsKey(spn) && 
        VMKernel.spnProcMap.get((Integer) spn) == this) {
        // TODO: call swapRead with result of map.get(vpn)
      }
    }

    // not found in swap file - allocate normally
    else {
      // check which section it is
      // set readonly bits and used/dirty bits
      // set pinned bit
    }

    // pte.ppn = ppn;
    // pte.valid = true;

    // check whether this should be read only in physical memory
    // pte.readonly = false;
    // pte.dirty = ?
    // pte.used = ?

    VMKernel.invTable[ppn].vpn = vpn;
    VMKernel.invTable[ppn].proc = this;
    // VMKernel.invTable[ppn].pinned = ?
  }

  private void handlePageFault(int vpn) {
    int ppn;
    int spn;
    TranslationEntry pte = pageTable[vpn];

    // chooses ppn from free frames, allocates memory, updates table entries
    if(UserKernel.freePages.size() > 0) {
      ppn = ((Integer)UserKernel.freePages.removeFirst()).intValue();
      allocateFrame(vpn, ppn); // possibly swaps in memory if found
    }

    // chooses ppn through eviction, update evicted page if necessary
    else {
      syncEntries(false);
      do {
        ppn = clockReplacement();
      } while (VMKernel.invTable[ppn].pinned);

      int evictedVpn = VMKernel.invTable[ppn].vpn;

      // do not write evicted ppn to swap unless second time (valid set)
      if(pageTable[evictedVpn].readonly) {
        if(pageTable[evictedVpn].valid) {
          // write out to swap?
          // load from coff or swap to stolen frame
        }
        else {
          // do not write out to swap
          // set to valid
        }
      }

      // invalidate old frame owner's pte pointing to this frame
      // reassign frame to this process
      // copy contents of page to stolen frame
      if(!pageTable[evictedVpn].dirty) {
      }

      // write to swap page if dirty; saves spn and proc in hashmaps
      if(pageTable[evictedVpn].dirty) {
        int writeResult = swapWrite(ppn);
      }

      invalidateVictimPage(ppn);

      // allocate physical memory, updates table entries
      allocateFrame(vpn, ppn);
    }

    // check if invTable is valid mapping to pte by checking if processes match up

    // invTable's ppn is its index; ppn in TE is actually spn if TE is valid
    VMKernel.PhysicalPage physPage = VMKernel.invTable[tlbe.ppn];
    physPage.vpn = vpn;
    physPage.proc = this;
    //physPage.pinned
  }

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
      handlePageFault(vpn);
    }

    // choose tlb entry
    boolean tlbFull = true;
    int teIndex = 0;
    for(; teIndex < Machine.processor().getTLBSize(); teIndex++) {
      if(!Machine.processor().readTLBEntry(i).valid) {
        tlbFull = false;
        break;
      }
    }

    // evict and sync TLB entry
    if(tlbFull) {
      teIndex = Lib.random(Machine.processor().getTLBSize());
      syncEntries(false);
    }

    // update TLB entry
    TranslationEntry tlbe = new TranslationEntry(pte);
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

  private static int clockVictim = 0;

  private static final char dbgProcess = 'a';

  private static final char dbgVM = 'v';
}
