package org.bds.executioner;

import org.bds.Config;

/**
 * Check that tasks are still running.
 * Use a 'ps' command
 *
 * @author pcingola
 */
public class CheckTasksRunningLocal extends CheckTasksRunning {

	public CheckTasksRunningLocal(Config config, Executioner executioner) {
		super(config, executioner);
		defaultCmdArgs = ExecutionerLocal.LOCAL_STAT_COMMAND;
	}

}
