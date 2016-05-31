package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see	nachos.vm.VMProcess
 * @see	nachos.network.NetProcess
 */
public class UserProcess {
    /**
     * Allocate a new process.
     */
    public UserProcess() {
	UserKernel.processLock.acquire();

	processID = UserKernel.nextProcessID++;

	UserKernel.processLock.release();

	fileTable[0] = UserKernel.console.openForReading();
	fileTable[1] = UserKernel.console.openForWriting();

	for (int i=2; i<maxFiles; i++)
	    fileTable[i] = null;
    }
    
    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return	a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
	return (UserProcess)Lib.constructObject(Machine.getProcessClassName());
    }

    /**
     * Get the process ID for this process.
     *
     * @return	the process ID for this process.
     */
    public int processID() {
	return processID;
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
	if (!load(name, args))
	    return false;
	
	UserKernel.numRunningProcesses++;
	
	new UThread(this).setName(name).fork();

	return true;
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
	Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param	vaddr	the starting virtual address of the null-terminated
     *			string.
     * @param	maxLength	the maximum number of characters in the string,
     *				not including the null terminator.
     * @return	the string read, or <tt>null</tt> if no null terminator was
     *		found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
	Lib.assertTrue(maxLength >= 0);

	byte[] bytes = new byte[maxLength+1];

	int bytesRead = readVirtualMemory(vaddr, bytes);

	for (int length=0; length<bytesRead; length++) {
	    if (bytes[length] == 0)
		return new String(bytes, 0, length);
	}

	return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
	return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @param	offset	the first byte to write in the array.
     * @param	length	the number of bytes to transfer from virtual memory to
     *			the array.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset,
				 int length) {
	Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

	byte[] memory = Machine.processor().getMemory();
	
	int amount = 0;

	while (length > 0) {
	    int vpn = Processor.pageFromAddress(vaddr);
	    int off = Processor.offsetFromAddress(vaddr);

	    int transfer = Math.min(length, pageSize-off);

	    int ppn = pinVirtualPage(vpn, false);
	    if (ppn == -1)
		break;

	    System.arraycopy(memory, ppn*pageSize + off, data, offset,
			     transfer);

	    unpinVirtualPage(vpn);
	    
	    vaddr += transfer;
	    offset += transfer;
	    amount += transfer;
	    length -= transfer;	    
	}

	return amount;
    }

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
	return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @param	offset	the first byte to transfer from the array.
     * @param	length	the number of bytes to transfer from the array to
     *			virtual memory.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
				  int length) {
	Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

	byte[] memory = Machine.processor().getMemory();
	
	int amount = 0;

	while (length > 0) {
	    int vpn = Processor.pageFromAddress(vaddr);
	    int off = Processor.offsetFromAddress(vaddr);

	    int transfer = Math.min(length, pageSize-off);

	    int ppn = pinVirtualPage(vpn, true);
	    if (ppn == -1)
		break;

	    System.arraycopy(data, offset, memory, ppn*pageSize + off,
			     transfer);
	    
	    unpinVirtualPage(vpn);
	    
	    vaddr += transfer;
	    offset += transfer;
	    amount += transfer;
	    length -= transfer;	    
	}

	return amount;
    }
    protected int pinVirtualPage(int vpn, boolean isUserWrite) {
	if (vpn < 0 || vpn >= pageTable.length)
	    return -1;

	TranslationEntry entry = pageTable[vpn];
	if (!entry.valid || entry.vpn != vpn)
	    return -1;

	if (isUserWrite) {
	    if (entry.readOnly)
		return -1;
	    entry.dirty = true;
	}

	entry.used = true;

	return entry.ppn;
    }
    
    protected void unpinVirtualPage(int vpn) {
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
	Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");
	
	OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
	if (executable == null) {
	    Lib.debug(dbgProcess, "\topen failed");
	    return false;
	}

	try {
	    coff = new Coff(executable);
	}
	catch (EOFException e) {
	    executable.close();
	    Lib.debug(dbgProcess, "\tcoff load failed");
	    return false;
	}

	// make sure the sections are contiguous and start at page 0
	numPages = 0;
	for (int s=0; s<coff.getNumSections(); s++) {
	    CoffSection section = coff.getSection(s);
	    if (section.getFirstVPN() != numPages) {
		coff.close();
		Lib.debug(dbgProcess, "\tfragmented executable");
		return false;
	    }
	    numPages += section.getLength();
	}

	// make sure the argv array will fit in one page
	byte[][] argv = new byte[args.length][];
	int argsSize = 0;
	for (int i=0; i<args.length; i++) {
	    argv[i] = args[i].getBytes();
	    // 4 bytes for argv[] pointer; then string plus one for null byte
	    argsSize += 4 + argv[i].length + 1;
	}
	if (argsSize > pageSize) {
	    coff.close();
	    Lib.debug(dbgProcess, "\targuments too long");
	    return false;
	}

	// program counter initially points at the program entry point
	initialPC = coff.getEntryPoint();	

	// next comes the stack; stack pointer initially points to top of it
	numPages += stackPages;
	initialSP = numPages*pageSize;

	// and finally reserve 1 page for arguments
	numPages++;

	if (!loadSections())
	    return false;

	// store arguments in last page
	int entryOffset = (numPages-1)*pageSize;
	int stringOffset = entryOffset + args.length*4;

	this.argc = args.length;
	this.argv = entryOffset;
	
	for (int i=0; i<argv.length; i++) {
	    byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
	    Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
	    entryOffset += 4;
	    Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
		       argv[i].length);
	    stringOffset += argv[i].length;
	    Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
	    stringOffset += 1;
	}

	return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return	<tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {
	// allocate memory
	UserKernel.memoryLock.acquire();

	if (UserKernel.freePages.size() < numPages) {
	    UserKernel.memoryLock.release();
	    coff.close();
	    Lib.debug(dbgProcess, "\tinsufficient physical memory");
	    return false;
	}

	pageTable = new TranslationEntry[numPages];

	for (int vpn=0; vpn<numPages; vpn++) {
	    int ppn = ((Integer)UserKernel.freePages.removeFirst()).intValue();

	    pageTable[vpn] = new TranslationEntry(vpn, ppn,
						  true, false, false, false);
	}
	
	UserKernel.memoryLock.release();

	// load sections
	for (int s=0; s<coff.getNumSections(); s++) {
	    CoffSection section = coff.getSection(s);
	    
	    Lib.debug(dbgProcess, "\tinitializing " + section.getName()
		      + " section (" + section.getLength() + " pages)");

	    for (int i=0; i<section.getLength(); i++) {
		int vpn = section.getFirstVPN()+i;

		pageTable[vpn].readOnly = section.isReadOnly();
		section.loadPage(i, pinVirtualPage(vpn, false));
	    }
	}
	
	return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
        for (int vpn=0; vpn<pageTable.length; vpn++)
	    UserKernel.freePages.add(new Integer(pageTable[vpn].ppn));
    }    

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
	Processor processor = Machine.processor();

	// by default, everything's 0
	for (int i=0; i<processor.numUserRegisters; i++)
	    processor.writeRegister(i, 0);

	// initialize PC and SP according
	processor.writeRegister(Processor.regPC, initialPC);
	processor.writeRegister(Processor.regSP, initialSP);

	// initialize the first two argument registers to argc and argv
	processor.writeRegister(Processor.regA0, argc);
	processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle the halt() system call. 
     */
    private int handleHalt() {
	if (processID != 0)
	    return -1;

	Machine.halt();
	
	Lib.assertNotReached("Machine.halt() did not halt machine!");
	return 0;
    }

    protected int handleExit(int status) {
	for (int i=0; i<maxFiles; i++)
	    handleClose(i);

	UserKernel.memoryLock.acquire();

	unloadSections();

	UserKernel.memoryLock.release();

	coff.close();
	
	UserKernel.processLock.acquire();

	if (parentProcess != null) {
	    Integer value = abnormalTermination ? null : new Integer(status);
	    parentProcess.exitStatusTable.put(new Integer(processID), value);
	    parentProcess.childFinished.wake();
	}

	if (--UserKernel.numRunningProcesses == 0)
	    Kernel.kernel.terminate();

	UserKernel.processLock.release();
	KThread.finish();

	Lib.assertNotReached("KThread.finish() did not finish thread!");
	return 0;
    }

    private int handleExec(int vaddrExecutableName, int argc, int vaddrArgv) {
	String executableName = readVirtualMemoryString(vaddrExecutableName,
							256);
	if (executableName == null)
	    return -1;

	if (argc<0 || argc>16)
	    return -1;

	byte[] bytesArgv = new byte[argc*4];

	if (readVirtualMemory(vaddrArgv, bytesArgv) < bytesArgv.length)
	    return -1;

	String[] args = new String[argc];
	for (int i=0; i<argc; i++) {
	    args[i] = readVirtualMemoryString(Lib.bytesToInt(bytesArgv, i*4),
					      256);
	    if (args[i] == null)
		return -1;
	}

	int result = -1;

	UserProcess childProcess = newUserProcess();
	childProcess.parentProcess = this;

	UserKernel.processLock.acquire();

	if (childProcess.execute(executableName, args)) {
	    result = childProcess.processID;

	    childProcesses.add(new Integer(result));
	}

	UserKernel.processLock.release();

	return result;
    }

    private int handleJoin(int childID, int vaddrStatus) {
	Integer integerChildID = new Integer(childID);
	Integer status;
	int result;
	
	if (!childProcesses.contains(integerChildID))
	    return -1;
	
	UserKernel.processLock.acquire();

	while (!exitStatusTable.containsKey(integerChildID))
	    childFinished.sleep();

	status = (Integer) exitStatusTable.get(integerChildID);

	if (status != null) {
	    result = 1;
	    writeVirtualMemory(vaddrStatus,
			       Lib.bytesFromInt(status.intValue()));
	}
	else {
	    result = 0;
	}

	UserKernel.processLock.release();

	return result;
    }
    
    private int handleCreateOpen(int vaddrFileName, boolean create) {
	String fileName = readVirtualMemoryString(vaddrFileName, 256);
	if (fileName == null)
	    return -1;

	int fileDescriptor = -1;

	for (int i=0; i<maxFiles; i++) {
	    if (fileTable[i] == null) {
		fileDescriptor = i;
		break;
	    }
	}
	if (fileDescriptor == -1)
	    return -1;

	OpenFile of = ThreadedKernel.fileSystem.open(fileName, create);
	if (of == null)
	    return -1;

	fileTable[fileDescriptor] = of;
	return fileDescriptor;
    }

    private int handleRead(int fileDescriptor, int vaddrBuffer, int length) {
	if (fileDescriptor<0 || fileDescriptor>=maxFiles)
	    return -1;
	if (length<0)
	    return -1;

	OpenFile of = fileTable[fileDescriptor];
	if (of == null)
	    return -1;

	int total = 0;
	while (length > 0) {
	    int transfer = Math.min(length, ioBufferSize);

	    int actual = of.read(ioBuffer, 0, transfer);
	    if (actual == -1) {
		total = -1;
		break;
	    }

	    actual = writeVirtualMemory(vaddrBuffer, ioBuffer, 0, actual);

	    vaddrBuffer += actual;
	    length -= actual;
	    total += actual;

	    if (actual < transfer)
		break;
	}

	return total;
    }

    private int handleWrite(int fileDescriptor, int vaddrBuffer, int length) {
	if (fileDescriptor<0 || fileDescriptor>=maxFiles)
	    return -1;
	if (length<0)
	    return -1;

	OpenFile of = fileTable[fileDescriptor];
	if (of == null)
	    return -1;

	int total = 0;
	while (length > 0) {
	    int transfer = Math.min(length, ioBufferSize);

	    int actual = readVirtualMemory(vaddrBuffer, ioBuffer, 0, transfer);

	    actual = of.write(ioBuffer, 0, actual);
	    if (actual == -1) {
		if (total == 0)
		    total = -1;
		break;
	    }

	    vaddrBuffer += actual;
	    length -= actual;
	    total += actual;

	    if (actual < transfer)
		break;
	}

	return total;
    }

    private int handleClose(int fileDescriptor) {
	if (fileDescriptor<0 || fileDescriptor>=maxFiles)
	    return -1;

	OpenFile of = fileTable[fileDescriptor];
	if (of == null)
	    return -1;

	of.close();
	fileTable[fileDescriptor] = null;

	return 0;
    }

    private int handleUnlink(int vaddrFileName) {
	String fileName = readVirtualMemoryString(vaddrFileName, 256);
	if (fileName == null)
	    return -1;

	if (!ThreadedKernel.fileSystem.remove(fileName))
	    return -1;

	return 0;
    }    

    private static final int
        syscallHalt = 0,
	syscallExit = 1,
	syscallExec = 2,
	syscallJoin = 3,
	syscallCreate = 4,
	syscallOpen = 5,
	syscallRead = 6,
	syscallWrite = 7,
	syscallClose = 8,
	syscallUnlink = 9;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
     * </table>
     * 
     * @param	syscall	the syscall number.
     * @param	a0	the first syscall argument.
     * @param	a1	the second syscall argument.
     * @param	a2	the third syscall argument.
     * @param	a3	the fourth syscall argument.
     * @return	the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
	switch (syscall) {
	case syscallHalt:
	    return handleHalt();

	case syscallOpen:
	    return handleCreateOpen(a0, false);

	case syscallCreate:
	    return handleCreateOpen(a0, true);

	case syscallRead:
	    return handleRead(a0, a1, a2);

	case syscallWrite:
	    return handleWrite(a0, a1, a2);

	case syscallClose:
	    return handleClose(a0);

	case syscallUnlink:
	    return handleUnlink(a0);

	case syscallExit:
	    return handleExit(a0);

	case syscallExec:
	    return handleExec(a0, a1, a2);

	case syscallJoin:
	    return handleJoin(a0, a1);

	default:
	    handleExit(1);
	    Lib.debug(dbgProcess, "Unknown syscall " + syscall);
	    Lib.assertNotReached("Unknown system call!");
	}
	return 0;
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param	cause	the user exception that occurred.
     */
    public void handleException(int cause) {
	Processor processor = Machine.processor();

	switch (cause) {
	case Processor.exceptionSyscall:
	    int result = handleSyscall(processor.readRegister(Processor.regV0),
				       processor.readRegister(Processor.regA0),
				       processor.readRegister(Processor.regA1),
				       processor.readRegister(Processor.regA2),
				       processor.readRegister(Processor.regA3)
				       );
	    processor.writeRegister(Processor.regV0, result);
	    processor.advancePC();
	    break;				       
				       
	default:
	    Lib.debug(dbgProcess, "Unexpected exception: " +
		      Processor.exceptionNames[cause]);
	    abnormalTermination = true;
	    handleExit(0);
	    Lib.assertNotReached("Unexpected exception");
	}
    }

    /** The program being run by this process. */
    protected Coff coff;

    /** This process's page table. */
    protected TranslationEntry[] pageTable;
    /** The number of contiguous pages occupied by the program. */
    protected int numPages;

    /** The number of pages in the program's stack. */
    protected final int stackPages = 8;
    
    private int initialPC, initialSP;
    private int argc, argv;
    
    private UserProcess parentProcess = null;
    private int processID;
    
    private HashSet childProcesses = new HashSet();
    private HashMap exitStatusTable = new HashMap();
    private Condition childFinished = new Condition(UserKernel.processLock);
    private boolean abnormalTermination = false;
    
    private byte[] ioBuffer = new byte[ioBufferSize];
    private static final int ioBufferSize = 1024;
    
    protected OpenFile[] fileTable = new OpenFile[maxFiles];
    protected static final int maxFiles = 16;
	
    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
}
