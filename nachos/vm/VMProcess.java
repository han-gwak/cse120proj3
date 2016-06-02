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
			TranslationEntry tlbe = new TranslationEntry(Machine.
				processor().readTLBEntry(i));

			// sync valid TLB entries back to pageTable
			if(tlbe.valid) {
				tlbe.valid = false;
				pageTable[tlbe.vpn] = tlbe;
				Machine.processor().writeTLBEntry(i, tlbe);
			}

			// physical page evicted; update in page table
			else if(tlbe.ppn != pageTable[tlbe.vpn].ppn) {
				pageTable[tlbe.vpn].ppn = tlbe.ppn;
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

	private void handleTLBMiss() {
		boolean tlbFull = true;
		int teIndex = 0;

		// get page table entry from virtual address
		int vaddr = Machine.processor().readRegister(Processor.regBadVAddr);
		int vpn = Processor.pageFromAddress(vaddr);
		Lib.assertTrue(vpn >= 0 && vpn < numPages)

		TranslationEntry pte = pageTable[vpn]; // reference to pageTable[vpn]
		TranslationEntry tlbe = new TranslationEntry(pte);

		// allocate physical page if unallocated or invalid
		if(pte.ppn == -1 || !pte.valid) {
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
