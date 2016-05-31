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
		super.saveState();
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
	     
            pageTable = new TranslationEntry[numPages];
            int count = 0;             
            //initializing pageTable
            while(count < numPages){
			pageTable[count] = new TranslationEntry(count, -1, false, false, false,
					false);
            }
            return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
	}
      /*
        public int  handleExit(int exit) {
       
          return  super.handleExit(exit);
       }
     */

	private void handleTLBMiss() {
		boolean evict = true;
		int unusedTLBEntry = 0;

		// TODO: invalid page table entry -> page fault
//		if(!pte.valid) {
//		}
		
		// allocate invalid/unused tlb entry
		for(int i = 0; i < Machine.processor().getTLBSize(); i++) {
			if(!Machine.processor().readTLBEntry(i).valid) {
				evict = false;
				unusedTLBEntry = i;
				break;
			}
		}

                // if entry was not found above
                if(unusedTLBEntry == 0){
                   int size = Machine.processor().getTLBSize();
                   unusedTLBEntry = rand.nextInt(size);
                }


		// evict tlb entry with Lib.random()
		if(evict) {
			// TODO: sync victim's page table entry
			
		}

		// update tlb entry with page table entry
		int vaddr = Machine.processor().readRegister(Processor.regBadVAddr);
		int vpn = Processor.pageFromAddress(vaddr); // TODO: add range check
		TranslationEntry pte = pageTable[vpn];
		Machine.processor().writeTLBEntry(unusedTLBEntry, pte);
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
       

        private static final Random rand = new Random();
	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
