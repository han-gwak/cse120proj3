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
      TranslationEntry tlbe = new TranslationEntry(Machine.processor().readTLBEntry(i));
      TranslationEntry pte = pageTable[tlbe.vpn];

      // sync all valid TLB entries 
      if(tlbe.valid) {
        pte.used = tlbe.used;
        pte.dirty = tlbe.dirty;

        if(invalidate)
          tlbe.valid = false;
      }
      Machine.processor().writeTLBEntry(i, tlbe);
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
    while(pageTable[VMKernel.invTable[clockVictim].vpn].used) {
      pageTable[VMKernel.invTable[clockVictim].vpn].used = false;
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
      TranslationEntry tlbe = new TranslationEntry(Machine.processor().readTLBEntry(i));
      if(tlbe.ppn == ppn) {
        tlbe.valid = false;
        pageTable[tlbe.vpn].valid = false; // memory mapping no longer valid
      }
      Machine.processor().writeTLBEntry(i, tlbe);
    }
  }

  /**
   * Reads specified swap page from file if not in freeSwapPages list.
   * Puts swap page index back into freeSwapPages list if successful.
   * Returns number of bytes read.
   */
  private int swapRead(int spn, int ppn) {
    if(VMKernel.freeSwapPages.indexOf((Integer) spn) != -1)
      return -1;

    int result = VMKernel.swapFile.read(spn*pageSize, 
      Machine.processor().getMemory(), ppn*pageSize, pageSize);
    if(result != -1) {
      // TODO: update condition variable for swapWrite
      VMKernel.freeSwapPages.add((Integer) spn);
    }

    return result;
  }
  
  /**
   * Write specified ppn of current page table to swap file.
   * Adds 
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
      VMKernel.vpnSwapMap.put(VMKernel.invTable[ppn].vpn, spn);
      VMKernel.spnProcMap.put(spn, VMKernel.invTable[ppn].proc);
      return spn;
    }

    return result;
  }

  /**
   * Handle swap write and PTE invalidations for evicted page
   * int vpn - VPN causing TLB miss
   * int ppn - PPN where we want to allocate memory
   */
  private void handleEviction(int vpn, int ppn) {
    int evictedVpn = VMKernel.invTable[ppn].vpn;  // previous vpn mapping to ppn
    TranslationEntry invalidPte = pageTable[evictedVpn];  // pte to invalidate

    // check if evictedVpn is part of coff section
    int coffResult = insideCoff(evictedVpn);
    boolean inCoff = (coffResult < 0) ? false : true;

    if(inCoff) {
      boolean readOnly = (coffResult == 1) ? true : false;

      // non-readOnly pages in exe evicted if dirty
      if(!readOnly && invalidPte.dirty) {
        swapWrite(ppn);
      }
    }

    // outside coff - write to swap if dirty
    else {
      if(invalidPte.dirty) {
        swapWrite(ppn);
      }
    }

    // set TLB and PT entries to invalid where evicted page was used
    invalidateVictimPage(ppn); // invalidates TLB entries where evicted page was
    invalidPte.valid = false;
    VMKernel.invTable[ppn].vpn = vpn;
    VMKernel.invTable[ppn].proc = this;
  }

  /**
   * Allocate physical memory and set pageTable and invTable values
   * for entry at vpn/ppn.
   * Input ppn is the location in physical memory we want to evict
   */
  private void allocateFrame(int vpn, int ppn, boolean evict) {
    TranslationEntry pte = pageTable[vpn];
    boolean allocated = false;
    boolean readOnly = false;

    // handle swap write and pte invalidations for evicted page
    if(evict)
      handleEviction(vpn, ppn); // ppn here is the new location where we are putting our pte
    
    // dirty - swap in from swap file; since dirty, can't be readOnly
    if(pte.dirty && VMKernel.vpnSwapMap.containsKey((Integer) vpn)) {
      Integer spn = VMKernel.vpnSwapMap.get((Integer) vpn);

      // write in from swap page if spn valid and same process
      if(VMKernel.spnProcMap.containsKey(spn) && 
        VMKernel.spnProcMap.get(spn) == this) {
        
        // memory successfully allocated; if not, allocate as normal
        if(swapRead(spn, ppn) > 0) {
          VMKernel.vpnSwapMap.remove((Integer) vpn);
          VMKernel.spnProcMap.remove((Integer) spn);
          swapRead((int) spn, ppn);
          allocated = true;
        }
      }
    }

    // not found in swap or hashMaps - allocate from coff
    if(!allocated) {
      int coffResult = insideCoff(vpn);
      readOnly = (coffResult == 1) ? true : false;

      // load page into memory when appropriate section found
      for (int s=0; s<coff.getNumSections(); s++) {
        CoffSection section = coff.getSection(s);
        if(vpn >= section.getFirstVPN() && vpn < (section.getFirstVPN() + section.getLength())) {
            section.loadPage(vpn - section.getFirstVPN(), ppn);
        }
      }
    }

    pte.ppn = ppn;
    pte.valid = true;
    pte.readOnly = readOnly;
    VMKernel.invTable[ppn].vpn = vpn;
    VMKernel.invTable[ppn].proc = this;
    // TODO: set invTable.pinned
  }


  /**
   * Returns -1 if specified vpn not in coff section.
   * Returns 0 if specified vpn in coff section but not read only
   * Returns 1 if specified vpn in coffSection and readOnly
   */
  private int insideCoff(int vpn) {
    boolean readOnly = false;
    for (int s=0; s<coff.getNumSections(); s++) {
      CoffSection section = coff.getSection(s);

      // if vpn is in coff exit out of loop
      if(vpn >= section.getFirstVPN() && vpn < (section.getFirstVPN() + section.getLength())) {
          readOnly = section.isReadOnly();
          return (readOnly) ? 1 : 0;
      }
    }
    return -1;
  }


  private void handlePageFault(int vpn) {
    int ppn;
    int spn;
    TranslationEntry pte = pageTable[vpn];

    // chooses ppn from free frames, allocates memory, updates table entries
    if(UserKernel.freePages.size() > 0) {
      ppn = ((Integer)UserKernel.freePages.removeFirst()).intValue();
      allocateFrame(vpn, ppn, false); // allocate without evicting
    }

    // chooses ppn through eviction, allocate physical page
    else {
      syncEntries(false);
      do {
        ppn = clockReplacement();
      } while (VMKernel.invTable[ppn].pinned); // skip this entry if pinned
      allocateFrame(vpn, ppn, true); // allocate with eviction
    }
  }

  private void handleTLBMiss() {
    int ppn;
    int spn;

    // get/allocate page table entry from virtual address
    int vaddr = Machine.processor().readRegister(Processor.regBadVAddr);
    int vpn = Processor.pageFromAddress(vaddr);
    Lib.assertTrue(vpn >= 0 && vpn < numPages);
    TranslationEntry pte = pageTable[vpn];

    // page fault; allocate memory and page table entry
    if(!pte.valid) {
      handlePageFault(vpn);
    }

    // choose tlb entry
    boolean tlbFull = true;
    int teIndex = 0;
    for(; teIndex < Machine.processor().getTLBSize(); teIndex++) {
      if(!Machine.processor().readTLBEntry(teIndex).valid) {
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
