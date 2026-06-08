/*
 * Title:        CloudSimSDN
 * Description:  SDN extension for CloudSim
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2017, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.sdn;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.ResCloudlet;

public class CloudletSchedulerTimeSharedMonitor extends CloudletSchedulerTimeShared implements CloudletSchedulerMonitor {
	private double timeoutLimit = Double.POSITIVE_INFINITY;
	private double prevMonitoredTime = 0;
	private double vmMips = 0;
	
	private long[] perPeProcessedMIs;
	private double[] perPeGivenMIs;
	private int numPes = -1;
	private double lastPerPeCapacity = 0.0;
	private double lastTimeSpent = 0.0;
	
	public CloudletSchedulerTimeSharedMonitor(long vmMipsPerPE, double timeout) {
		vmMips = vmMipsPerPE;
		timeoutLimit = timeout;
	}
	
	public long getTotalProcessingPreviousTime(double currentTime, List<Double> mipsShare) {
		long totalProcessedMIs = 0;
		double timeSpent = currentTime - prevMonitoredTime;
		double capacity = getCapacity(mipsShare);
		
		if (perPeProcessedMIs == null || perPeProcessedMIs.length != mipsShare.size()) {
			perPeProcessedMIs = new long[mipsShare.size()];
			perPeGivenMIs = new double[mipsShare.size()];
			numPes = mipsShare.size();
		}
		
		for (int i = 0; i < numPes; i++) {
			perPeProcessedMIs[i] = 0;
			perPeGivenMIs[i] = 0;
		}
		
		int peOffset = 0;
		for (ResCloudlet rcl : getCloudletExecList()) {
			int pes = rcl.getNumberOfPes();
			double cloudletTotalMIs = capacity * timeSpent * pes * Consts.MILLION;
			
			for (int i = 0; i < pes; i++) {
				int peIdx = peOffset + i;
				if (peIdx < numPes) {
					perPeProcessedMIs[peIdx] += (long) cloudletTotalMIs / pes;
					perPeGivenMIs[peIdx] = capacity * timeSpent * Consts.MILLION;
				}
			}
			
			peOffset += pes;
			totalProcessedMIs += (long) cloudletTotalMIs;
		}
		
		lastTimeSpent = timeSpent;
		lastPerPeCapacity = capacity;
		prevMonitoredTime = currentTime;
		return totalProcessedMIs;
	}
	
	@Override
	public double getPerPeUserPercentage(int peIndex) {
		if (peIndex < 0 || peIndex >= numPes || lastTimeSpent <= 0 || lastPerPeCapacity <= 0) {
			return 0.0;
		}
		double util = perPeProcessedMIs[peIndex] / (lastPerPeCapacity * lastTimeSpent * Consts.MILLION);
		util = Math.min(1.0, Math.max(0.0, util));
		double userFraction = util * (0.6 + 0.1 * peIndex);
		return Math.min(1.0, userFraction);
	}
	
	@Override
	public double getPerPeSystemPercentage(int peIndex) {
		if (peIndex < 0 || peIndex >= numPes || lastTimeSpent <= 0 || lastPerPeCapacity <= 0) {
			return 0.0;
		}
		double util = perPeProcessedMIs[peIndex] / (lastPerPeCapacity * lastTimeSpent * Consts.MILLION);
		util = Math.min(1.0, Math.max(0.0, util));
		double userFraction = util * (0.6 + 0.1 * peIndex);
		double systemFraction = Math.max(0.0, util - userFraction);
		return Math.min(1.0, systemFraction);
	}
	
	@Override
	public double getPerPeIdlePercentage(int peIndex) {
		if (peIndex < 0 || peIndex >= numPes || lastTimeSpent <= 0 || lastPerPeCapacity <= 0) {
			return 1.0;
		}
		double user = getPerPeUserPercentage(peIndex);
		double sys = getPerPeSystemPercentage(peIndex);
		return Math.max(0.0, 1.0 - user - sys);
	}
	
	public int getNumActiveCores() {
		if (perPeProcessedMIs == null || lastTimeSpent <= 0 || lastPerPeCapacity <= 0) {
			return 0;
		}
		int active = 0;
		for (int i = 0; i < numPes; i++) {
			if (perPeProcessedMIs[i] > 0) {
				active++;
			}
		}
		return active;
	}
	
	@Override
	public double getTimeSpentPreviousMonitoredTime(double currentTime) {
		double timeSpent = currentTime - prevMonitoredTime;
		return timeSpent;
	}
	
	@Override
	public boolean isVmIdle() {
		if(runningCloudlets() > 0)
			return false;
		return true;
	}
	
	@Override
	public double getCapacity(List<Double> mipsShare) {
		double capacity = super.getCapacity(mipsShare);
		double maxPeCapacityPerCloudlet = vmMips * Configuration.CPU_REQUIRED_MIPS_PER_WORKLOAD_PERCENT;
		if(capacity > maxPeCapacityPerCloudlet) {
			capacity = maxPeCapacityPerCloudlet;
		}
		return capacity;
	}
	
	@Override
	public int getCloudletTotalPesRequested() {
		int pesInUse = 0;
		for (ResCloudlet rcl : getCloudletExecList()) {
			pesInUse += rcl.getNumberOfPes();
		}
		return pesInUse;
	}
	
	@Override
	public double updateVmProcessing(double currentTime, List<Double> mipsShare) {
		double ret = super.updateVmProcessing(currentTime, mipsShare);
		processTimeout(currentTime);
		return ret;
	}
	
	@Override
	public List<Cloudlet> getFailedCloudlet() {
		List<Cloudlet> failed = new ArrayList<Cloudlet>();
		for(ResCloudlet cl:getCloudletFailedList()) {
			failed.add(cl.getCloudlet());
		}
		getCloudletFailedList().clear();
		return failed;
	}
	
	protected void processTimeout(double currentTime) {
		if(timeoutLimit > 0 && Double.isFinite(timeoutLimit)) {
			double timeout = currentTime - this.timeoutLimit;
			List<ResCloudlet> timeoutCloudlet = new ArrayList<ResCloudlet>();
			
			for (ResCloudlet rcl : getCloudletExecList()) {
				if(rcl.getCloudletArrivalTime() < timeout) {
					timeoutCloudlet.add(rcl);
				}
			}
			getCloudletFailedList().addAll(timeoutCloudlet);
			getCloudletExecList().removeAll(timeoutCloudlet);			
		}
		
	}
}
