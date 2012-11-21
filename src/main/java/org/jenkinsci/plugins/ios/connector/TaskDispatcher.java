// Copyright © 2012 iosphere GmbH
package org.jenkinsci.plugins.ios.connector;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Project;
import hudson.model.Queue;
import hudson.model.Queue.BuildableItem;
import hudson.model.Queue.Executable;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.CauseOfBlockage;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;


@Extension
public class TaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canRun(Item item) {
        final String desiredUdid = getUdidForTask(item.task);
        if (desiredUdid == null) {
            // If the given task doesn't intend to deploy to an iOS device, we don't care
            return null;
        }

        // Check for builds in the queue which have the same UDID as this task
        Queue queue = Jenkins.getInstance().getQueue();
        for (BuildableItem queuedItem : queue.getBuildableItems()) {
            if (item == queuedItem) {
                continue;
            }

            // If build with matching UDID is about to start (is "pending"), hold off for a moment
            if (queue.isPending(queuedItem.task)) {
                String queuedTaskUdid = getUdidForTask(queuedItem.task);
                if (desiredUdid.equals(queuedTaskUdid)) {
                    return CauseOfBlockage.fromMessage(Messages._TaskDispatcher_WaitingForDevice(desiredUdid));
                }
            }
        }

        // Check whether any build on any Jenkins executor is currently using this UDID
        for (Computer computer : Jenkins.getInstance().getComputers()) {
            for (Executor e : computer.getExecutors()) {
                Executable executable = e.getCurrentExecutable();
                if (executable == null) {
                    continue;
                }

                String udid = getUdidForTask(executable.getParent().getOwnerTask());
                if (desiredUdid.equals(udid)) {
                    return CauseOfBlockage.fromMessage(Messages._TaskDispatcher_WaitingForDevice(desiredUdid));
                }
            }
        }

        // Nope, no conflicting builds
        return null;
    }

    /**
     * Determines the UDID for the given task, if any.
     *
     * @param task The task whose configured UDID should be determined.
     * @return The UDID for the task, or {@code null} if the given task is not configured to deploy
     *         to an iOS device.
     */
    private static String getUdidForTask(Task task) {
        // Fetch the builders that we want to use
        DescribableList<Builder,Descriptor<Builder>> builders;
        MatrixConfiguration matrixBuild = null;
        if (task instanceof MatrixConfiguration) {
            matrixBuild = (MatrixConfiguration) task;
            builders = matrixBuild.getBuildersList();
        } else if (task instanceof Project) {
            builders = ((Project<?, ?>) task).getBuildersList();
        } else {
            return null;
        }

        // If we aren't one of the builders for this job, we don't care
        DeployBuilder builder = builders.get(DeployBuilder.class);
        if (builder == null) {
            return null;
        }

        if (matrixBuild != null) {
            // If this is a matrix sub-build, substitute in the build variables
            return builder.resolveUdid(matrixBuild.getCombination());
        }
        return builder.resolveUdid();
    }

}
