package org.bds.task;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bds.Config;
import org.bds.cluster.host.HostResources;
import org.bds.executioner.Executioner;
import org.bds.lang.BdsNode;
import org.bds.run.BdsThread;
import org.bds.util.Gpr;
import org.bds.util.Timer;

/**
 * A task to be executed by an Executioner
 *
 * @author pcingola
 */
public class Task implements Serializable {

	private static final long serialVersionUID = 3377646684108052191L;

	public static final String CHECKSUM_LINE_START = "# Checksum: ";
	public static final String EXIT_STR_TIMEOUT = "Time out";
	public static final String EXIT_STR_KILLED = "Signal received";

	public static final int MAX_HINT_LEN = 150;

	protected boolean allowEmpty; // Allow empty output file/s
	protected boolean canFail; // Allow execution to fail
	protected boolean debug;
	protected boolean dependency; // This is a 'dependency' task. Run only if required
	protected boolean verbose;
	protected int bdsLineNum; // Program's line number that created this task (used for reporting errors)
	protected int exitValue; // Exit (error) code
	protected int failCount, maxFailCount; // Number of times that this task failed
	protected String id; // Task ID
	protected String bdsFileName; // Program file that created this task (used for reporting errors)
	protected String currentDir; // Program's 'current directoy' (cd)
	protected String pid; // PID (if any)
	protected String programFileDir; // Program file's dir
	protected String programFileName; // Program file name
	protected String programTxt; // Program's text (program's code)
	protected String node; // Preferred execution node (or hostname)
	protected String queue; // Preferred execution queue
	protected String stdoutFile, stderrFile, exitCodeFile; // STDOUT, STDERR & exit code Files
	protected String errorMsg; // Error messages
	protected String postMortemInfo; // Error information about task that failed
	protected String taskName = ""; // Task name (can be set by programmer)
	protected Date runningStartTime, runningEndTime;
	protected TaskState taskState;
	protected HostResources resources; // Resources to be consumes when executing this task
	TaskDependency taskDependency;

	public Task() {
		this(null, null, null, null, -1);
	}

	public Task(String id) {
		this(id, null, null, null, -1);
	}

	public Task(String id, BdsNode bdsNode, String programFileName, String programTxt) {
		this.id = id;
		this.programFileName = programFileName;
		this.programTxt = programTxt;
		if (bdsNode != null) {
			bdsFileName = bdsNode.getFileName();
			bdsLineNum = bdsNode.getLineNum();
		} else {
			bdsFileName = "";
			bdsLineNum = -1;
		}
		taskDependency = new TaskDependency(bdsNode);
		resources = new HostResources();
		reset();
	}

	public Task(String id, String programFileName, String programTxt, String bdsFileName, int bdsLineNum) {
		this.id = id;
		this.programFileName = programFileName;
		this.programTxt = programTxt;
		this.bdsFileName = bdsFileName;
		this.bdsLineNum = bdsLineNum;
		taskDependency = new TaskDependency();
		resources = new HostResources();
		reset();
	}

	/**
	 * Add a dependency task (i.e. taskDep must finish before this task starts)
	 */
	public void addDependency(Task taskDep) {
		taskDependency.add(taskDep);
	}

	/**
	 * Can a task change state to 'newState'?
	 */
	public boolean canChangeState(TaskState newState) {
		if (newState == null) return false;
		if (newState == taskState) return true; // OK to change to same state

		switch (newState) {
		case SCHEDULED:
			if (taskState == TaskState.NONE) return true;
			return false;

		case STARTED:
			if (taskState == TaskState.SCHEDULED) return true;
			return false;

		case START_FAILED:
			if (taskState == TaskState.SCHEDULED || taskState == TaskState.KILLED) return true;
			return false;

		case RUNNING:
			if (taskState == TaskState.STARTED) return true;
			return false;

		case ERROR:
			return true;

		case ERROR_TIMEOUT:
		case FINISHED:
			if (taskState == TaskState.RUNNING) return true;
			return false;

		case KILLED:
			if ((taskState == TaskState.RUNNING) // A task can be killed while running...
					|| (taskState == TaskState.STARTED) // or right after it started
					|| (taskState == TaskState.SCHEDULED) // or even if it was not started
					|| (taskState == TaskState.NONE) // or even if it was not scheduled
			) return true;
			return false;

		default:
			return false;
		}
	}

	public boolean canRetry() {
		return failCount < maxFailCount;
	}

	/**
	 * Can this task run?
	 * I.e.: It has been scheduled, but not started.
	 */
	public boolean canRun() {
		return taskState == TaskState.SCHEDULED;
	}

	public String checkOutputFiles() {
		return taskDependency.checkOutputFiles(this);
	}

	/**
	 * Create a line that contains a checksum (for integrity)
	 *
	 * e.g.:
	 *     #!/bin/sh
	 *     echo hi
	 *     # Checksum: 65AF9234
	 *
	 * Note: We use Bernstein's hash function
	 * Reference: http://burtleburtle.net/bob/hash/doobs.html
	 *
	 * @return
	 */
	protected String checkSumLine(String program) {
		int checksum = 0;
		for (char c : program.toCharArray()) {
			checksum = checksum * 33 + (c);
		}
		return CHECKSUM_LINE_START + Integer.toHexString(checksum) + "\n";
	}

	/**
	 * Create a program file
	 */
	public void createProgramFile() {
		if (debug) Timer.showStdErr("Task: Saving file '" + programFileName + "'");

		// Create dir
		try {
			File dir = new File(programFileName);
			dir = dir.getCanonicalFile().getParentFile();
			if (dir != null) {
				dir.mkdirs();
				programFileDir = dir.getCanonicalPath();
			}
		} catch (IOException e) {
			// Nothing to do
		}

		// Create file
		String shell = Config.get().getTaskShell();
		shell = "#!" + shell + "\n\n" // Shell to use
				+ "cd '" + currentDir + "'\n" // Add 'cd' to current dir
		;

		// Save file and make it executable
		String program = shell + programTxt;
		program += "\n" + checkSumLine(program);
		Gpr.toFile(programFileName, program);
		(new File(programFileName)).setExecutable(true);

		// Set default file names
		String base = Gpr.removeExt(programFileName);
		if (stdoutFile == null) stdoutFile = base + ".stdout";
		if (stderrFile == null) stderrFile = base + ".stderr";
		if (exitCodeFile == null) exitCodeFile = base + ".exitCode";
	}

	/**
	 * Remove tmp files on exit
	 */
	public void deleteOnExit() {
		if (programFileDir != null) (new File(programFileDir)).deleteOnExit(); // Files are deleted in reverse order. So dir has to be first to make sure it is empty when deleted (otherwise it will not be deleted)
		if (stdoutFile != null) (new File(stdoutFile)).deleteOnExit();
		if (stderrFile != null) (new File(stderrFile)).deleteOnExit();
		if (exitCodeFile != null) (new File(exitCodeFile)).deleteOnExit();
		if (programFileName != null) (new File(programFileName)).deleteOnExit();
	}

	public void deleteOutputFilesOnExit() {
		taskDependency.deleteOutputFilesOnExit();
	}

	public DependencyState dependencyState() {
		HashSet<Task> tasks = new HashSet<>();
		return dependencyState(tasks);
	}

	/**
	 * Are dependencies satisfied? (i.e. can we execute this task?)
	 * @return true if all dependencies are satisfied
	 */
	protected synchronized DependencyState dependencyState(Set<Task> tasksVisited) {
		// Task already finished?
		if (isDone()) {
			if (isCanFail() || isDoneOk()) return DependencyState.OK;
			return DependencyState.ERROR;
		}
		if (isStarted()) return DependencyState.WAIT; // Already started but not finished? => Then you should wait;
		if (!taskDependency.hasTasks()) return DependencyState.OK; // No dependencies? => we are ready to execute

		// Ignore if already visited
		if (!tasksVisited.contains(this)) {
			tasksVisited.add(this);

			// Check that all dependencies are OK
			for (Task task : taskDependency.getTasks()) {
				// Analyze dependent task
				DependencyState dep = task.dependencyState(tasksVisited);
				if (dep != DependencyState.OK) return dep; // Propagate non-OK states (i.e. error or wait)
				if (!task.isDone()) return DependencyState.WAIT; // Dependency OK, but not finished? => Wait for it
			}
		}

		// Only if all dependent tasks are OK, we can say that we are ready
		return DependencyState.OK;
	}

	/**
	 * Elapsed number of seconds this task has been executing
	 */
	public int elapsedSecs() {
		if (runningStartTime == null) return -1; // Not started?
		if (getResources() == null) return -1; // No resources?

		// Calculate elapsed processing time
		long end = (runningEndTime != null ? runningEndTime : new Date()).getTime();
		long start = runningStartTime.getTime();
		int elapsedSecs = (int) ((end - start) / 1000);
		return elapsedSecs;
	}

	/**
	 * Execute a task in from a bdsThread on a given executioner
	 */
	public synchronized void execute(BdsThread bdsThread, Executioner executioner) {
		// Make sure the task in in initial state
		reset();

		// Queue exec
		if (bdsThread.getConfig().isDryRun()) {
			// Dry run: Don't run the task, just show what would be run
			Timer.showStdErr("Dry run task:\n" + toString(true, true));
			state(TaskState.SCHEDULED);
			state(TaskState.STARTED);
			state(TaskState.RUNNING);
			state(TaskState.FINISHED);
			setExitValue(0);
			bdsThread.add(this);
		} else {
			bdsThread.add(this);
			executioner.add(this);
		}
	}

	public List<Task> getDependencies() {
		return taskDependency.getTasks();
	}

	public String getErrorMsg() {
		return errorMsg;
	}

	public String getExitCodeFile() {
		return exitCodeFile;
	}

	public synchronized int getExitValue() {
		if (!checkOutputFiles().isEmpty()) return 1; // Any output file failed?
		return exitValue;
	}

	public int getFailCount() {
		return failCount;
	}

	public String getId() {
		return id;
	}

	public List<String> getInputs() {
		return taskDependency.getInputs();
	}

	public int getMaxFailCount() {
		return maxFailCount;
	}

	public String getName() {
		if (taskName != null && !taskName.isEmpty()) return taskName;
		return Gpr.baseName(id);
	}

	public String getNode() {
		return node;
	}

	public List<String> getOutputs() {
		return taskDependency.getOutputs();
	}

	public synchronized String getPid() {
		return pid;
	}

	public String getPostMortemInfo() {
		return postMortemInfo;
	}

	public String getProgramFileName() {
		return programFileName;
	}

	/**
	 * A short text describing the task (extracted from program text)
	 */
	public String getProgramHint() {
		if (programTxt == null) return "";

		int maxHintLen = Config.get().getTaskMaxHintLen();
		ArrayList<String> filterOutTaskHint = Config.get().getFilterOutTaskHint();

		StringBuilder hint = new StringBuilder();
		for (String line : programTxt.split("\n"))
			if (!line.startsWith("#")) {
				line = line.replace('\'', ' ').replace('\t', ' ').replace('\n', ' ').replace('\\', ' ').trim(); // Replace some chars

				// Skip empty lines
				boolean showLine = !line.isEmpty();

				// Skip lines containing any of the strings in 'filterOutTaskHint'
				if (showLine) {
					for (String fo : filterOutTaskHint)
						showLine &= !line.contains(fo);
				}

				if (showLine) {
					if (hint.length() > 0) hint.append("; "); // Append using semicolon
					hint.append(line);
				}
			}

		if (hint.length() < maxHintLen) return hint.toString();
		return hint.toString().substring(0, maxHintLen);
	}

	public String getProgramTxt() {
		return programTxt;
	}

	public String getQueue() {
		return queue;
	}

	public HostResources getResources() {
		return resources;
	}

	public Date getRunningEndTime() {
		return runningEndTime;
	}

	public Date getRunningStartTime() {
		return runningStartTime;
	}

	public String getStderrFile() {
		return stderrFile;
	}

	public String getStdoutFile() {
		return stdoutFile;
	}

	public String getTaskName() {
		return taskName;
	}

	public TaskState getTaskState() {
		return taskState;
	}

	public boolean isAllowEmpty() {
		return allowEmpty;
	}

	public boolean isCanFail() {
		return canFail;
	}

	public boolean isDependency() {
		return dependency;
	}

	/**
	 * Has this task finished? Either finished OK or finished because of errors.
	 */
	public synchronized boolean isDone() {
		return isStateError() || isStateFinished();
	}

	/**
	 * Has this task been executed successfully?
	 * The task has finished, exit code is zero and all output files have been created
	 */
	public synchronized boolean isDoneOk() {
		return isStateFinished() && (exitValue == 0) && checkOutputFiles().isEmpty();
	}

	/**
	 * Has this task been executed and failed?
	 *
	 * This is true if:
	 * 		- The task has finished execution and it is in an error state
	 * 		- OR exitValue is non-zero
	 * 		- OR any of the output files was not created
	 */
	public synchronized boolean isFailed() {
		return isStateError() || (exitValue != 0) || !checkOutputFiles().isEmpty();
	}

	/**
	 * Has this task been scheduled to be started?
	 */
	public boolean isScheduled() {
		return taskState != TaskState.NONE;
	}

	/**
	 * Has the task been started?
	 */
	public boolean isStarted() {
		return taskState != TaskState.NONE && taskState != TaskState.SCHEDULED;
	}

	/**
	 * Is this task in any error or killed state?
	 */
	public boolean isStateError() {
		return taskState.isError();
	}

	public boolean isStateFinished() {
		return taskState.isFinished();
	}

	public boolean isStateRunning() {
		return taskState.isRunning();
	}

	public synchronized boolean isStateStarted() {
		return taskState.isStarted();
	}

	/**
	 * Has this task run out of time?
	 */
	public boolean isTimedOut() {
		int elapsedSecs = elapsedSecs();
		if (elapsedSecs < 0) return false;

		// Run out of time?
		// Note: We use wall-timeout instead of timeout, because we don't really know
		//       how long the task is being executed (the cluster scheduler can have
		//       the task in a queue for a long time).
		int timeout = (int) getResources().getWallTimeout();

		// Timeout equal or less than zero means 'no limit'
		if (timeout <= 0) return false;

		if (debug && (elapsedSecs > timeout)) Timer.showStdErr("Task.isTimedOut(): Task timed out '" + getId() + "', elapsed: " + elapsedSecs + ", wall-timeout: " + timeout);
		return elapsedSecs > timeout;
	}

	/**
	 * Reset parameters and allow a task to be re-executed
	 */
	public synchronized void reset() {
		taskState = TaskState.NONE;
		exitValue = 0;
		runningStartTime = null;
		runningEndTime = null;
		postMortemInfo = null;
		errorMsg = null;
	}

	public void setAllowEmpty(boolean allowEmpty) {
		this.allowEmpty = allowEmpty;
	}

	public void setCanFail(boolean canFail) {
		this.canFail = canFail;
	}

	public void setCurrentDir(String currentDir) {
		this.currentDir = currentDir;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public void setDependency(boolean dependency) {
		this.dependency = dependency;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public synchronized void setExitValue(int exitValue) {
		this.exitValue = exitValue;
	}

	public void setMaxFailCount(int maxFailCount) {
		this.maxFailCount = maxFailCount;
	}

	public void setNode(String node) {
		this.node = node;
	}

	public void setPid(String pid) {
		this.pid = pid;
	}

	public void setPostMortemInfo(String postMortemInfo) {
		this.postMortemInfo = postMortemInfo;
	}

	public void setQueue(String queue) {
		this.queue = queue;
	}

	private void setState(TaskState taskState) {
		this.taskState = taskState;
	}

	public void setTaskDependency(TaskDependency taskDependency) {
		this.taskDependency = taskDependency;
	}

	public void setTaskName(String taskName) {
		this.taskName = taskName;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	/**
	 * Change state: Make sure state changes are valid
	 */
	public synchronized void state(TaskState newState) {
		if (newState == null) throw new RuntimeException("Cannot change to 'null' state.\n" + this);
		if (newState == taskState) return; // Same state, nothing to do

		switch (newState) {
		case SCHEDULED:
			if (taskState == TaskState.NONE) setState(newState);
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case STARTED:
			if (taskState == TaskState.SCHEDULED) setState(newState);
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case START_FAILED:
			if (taskState == TaskState.SCHEDULED) {
				setState(newState);
				runningStartTime = runningEndTime = new Date();
				failCount++;
			} else if (taskState == TaskState.KILLED) ; // OK, don't change state
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case RUNNING:
			if (taskState == TaskState.STARTED) {
				setState(newState);
				runningStartTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case ERROR:
			failCount++;
			setState(newState);
			runningEndTime = new Date();
			if (exitValue == 0) exitValue = BdsThread.EXITCODE_ERROR;
			break;

		case ERROR_TIMEOUT:
			failCount++;
			setState(newState);
			runningEndTime = new Date();
			if (exitValue == 0) exitValue = BdsThread.EXITCODE_ERROR;
			break;

		case FINISHED:
			if (taskState == TaskState.RUNNING) {
				setState(newState);
				runningEndTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case KILLED:
			if ((taskState == TaskState.RUNNING) // A task can be killed while running...
					|| (taskState == TaskState.STARTED) // or right after it started
					|| (taskState == TaskState.SCHEDULED) // or even if it was not started
					|| (taskState == TaskState.NONE) // or even if it was not scheduled
			) {
				if (exitValue == 0) exitValue = BdsThread.EXITCODE_KILLED;
				setState(newState);
				runningEndTime = new Date();
				failCount++;
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		default:
			throw new RuntimeException("Unimplemented state: '" + newState + "'");
		}

		// Finished OK? Check that output files are OK as well
		if (isStateFinished()) {
			// Update failCount if output files failed to be created
			if (!isCanFail() && !checkOutputFiles().isEmpty()) failCount++;
		}
	}

	/**
	 * Calculate task's state when the process finished
	 */
	public TaskState taskState() {
		if (exitCodeFile != null && Gpr.exists(exitCodeFile)) {
			// Use exit file
			String exitStr = Gpr.readFile(exitCodeFile).trim();
			switch (exitStr) {
			case EXIT_STR_TIMEOUT:
				return TaskState.ERROR_TIMEOUT;

			case EXIT_STR_KILLED:
				return TaskState.KILLED;

			default:
				// No information in exit file? Use exit code
				if (debug) Gpr.debug("Using exit file '" + exitCodeFile + "' failed, using exit code '" + getExitValue() + "'");
				return TaskState.exitCode2taskState(exitValue);
			}
		}

		// Use exit value
		return TaskState.exitCode2taskState(getExitValue());
	}

	@Override
	public String toString() {
		return toString(verbose, debug);
	}

	public String toString(boolean verbose) {
		return toString(verbose, debug);
	}

	public String toString(boolean verbose, boolean showCode) {
		StringBuilder sb = new StringBuilder();

		if (verbose) {
			sb.append("\tProgram & line     : '" + bdsFileName + "', line " + bdsLineNum + "\n");
			sb.append("\tTask Name          : '" + taskName + "'\n");
			sb.append("\tTask ID            : '" + id + "'\n");
			sb.append("\tTask PID           : '" + pid + "'\n");
			sb.append("\tTask hint          : '" + getProgramHint() + "'\n");
			sb.append("\tTask resources     : '" + getResources() + "'\n");
			sb.append("\tState              : '" + taskState + "'\n");
			sb.append("\tDependency state   : '" + dependencyState() + "'\n");
			sb.append("\tRetries available  : '" + failCount + "'\n");
			sb.append("\tInput files        : '" + taskDependency.getInputs() + "'\n");
			sb.append("\tOutput files       : '" + taskDependency.getOutputs() + "'\n");

			if (!getDependencies().isEmpty()) {
				sb.append("\tTask dependencies  : ");
				sb.append(" [ ");
				boolean comma = false;
				for (Task t : getDependencies()) {
					sb.append((comma ? ", " : "") + "'" + t.getId() + "'");
					comma = true;
				}
				sb.append(" ]\n");
			}

			sb.append("\tScript file        : '" + programFileName + "'\n");
			if (errorMsg != null) sb.append("\tError message      : '" + errorMsg + "'\n");
			sb.append("\tExit status        : '" + exitValue + "'\n");

			String ch = checkOutputFiles();
			if ((ch != null) && !ch.isEmpty()) sb.append("\tOutput file checks : '" + ch + "'");

			// Show code?
			showCode |= Config.get().isShowTaskCode();
			String prog = getProgramTxt();
			if (showCode && (prog != null) && !prog.isEmpty()) sb.append("\tProgram            : \n" + Gpr.prependEachLine("\t\t", getProgramTxt()));

			// Show StdErr
			String tailErr = TailFile.tail(stderrFile, Config.get().getTailLines());
			if ((tailErr != null) && !tailErr.isEmpty()) sb.append("\tStdErr (" + Config.get().getTailLines() + " lines)  :\n" + Gpr.prependEachLine("\t\t", tailErr));

			// Show StdOut
			String tailOut = TailFile.tail(stdoutFile, Config.get().getTailLines());
			if ((tailOut != null) && !tailOut.isEmpty()) sb.append("\tStdOut (" + Config.get().getTailLines() + " lines)  :\n" + Gpr.prependEachLine("\t\t", tailOut));

			// Show post-mortem info
			if (postMortemInfo != null) sb.append("\tPost-mortem info   : '" + postMortemInfo + "'\n");

		} else sb.append("'" + bdsFileName + "', line " + bdsLineNum);

		return sb.toString();
	}

}
