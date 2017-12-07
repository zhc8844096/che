/*
 * Copyright (c) 2012-2017 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.openshift.project.pvc;

import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftObjectUtil.newVolume;
import static org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftObjectUtil.newVolumeMount;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.commons.lang.concurrent.LoggingUncaughtExceptionHandler;
import org.eclipse.che.commons.lang.concurrent.ThreadLocalPropagateContext;
import org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftPods;
import org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftProjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helps to execute commands needed for workspace PVC preparation and cleanup.
 *
 * <p>Creates a short-lived Pod based on CentOS image which mounts a specified PVC and executes a
 * command (either {@code mkdir -p <path>} or {@code rm -rf <path>}). Reports back whether the pod
 * succeeded or failed. Supports multiple paths for one command.
 *
 * <p>Note that the commands execution is needed only for {@link CommonPVCStrategy}.
 *
 * @author amisevsk
 * @author Anton Korneta
 */
@Singleton
public class PVCSubPathHelper {

  private static final Logger LOG = LoggerFactory.getLogger(PVCSubPathHelper.class);
  private static final JobFinishedPredicate POD_PREDICATE = new JobFinishedPredicate();

  static final int COUNT_THREADS = 4;
  static final int WAIT_POD_TIMEOUT_MIN = 5;

  static final String[] RM_COMMAND_BASE = new String[] {"rm", "-rf"};
  static final String[] MKDIR_COMMAND_BASE = new String[] {"mkdir", "-p"};

  static final String IMAGE_PULL_POLICY = "IfNotPresent";
  static final String POD_RESTART_POLICY = "Never";
  static final String POD_PHASE_SUCCEEDED = "Succeeded";
  static final String POD_PHASE_FAILED = "Failed";
  static final String JOB_MOUNT_PATH = "/tmp/job_mount";

  private final String pvcName;
  private final String jobImage;
  private final String jobMemoryLimit;
  private final OpenShiftProjectFactory factory;
  private final ExecutorService executor;

  @Inject
  PVCSubPathHelper(
      @Named("che.infra.openshift.pvc.name") String pvcName,
      @Named("che.infra.openshift.pvc.jobs.memorylimit") String jobMemoryLimit,
      @Named("che.infra.openshift.pvc.jobs.image") String jobImage,
      OpenShiftProjectFactory factory) {
    this.pvcName = pvcName;
    this.jobMemoryLimit = jobMemoryLimit;
    this.jobImage = jobImage;
    this.factory = factory;
    this.executor =
        Executors.newFixedThreadPool(
            COUNT_THREADS,
            new ThreadFactoryBuilder()
                .setNameFormat("PVCSubPathHelper-ThreadPool-%d")
                .setUncaughtExceptionHandler(LoggingUncaughtExceptionHandler.getInstance())
                .setDaemon(false)
                .build());
  }

  /**
   * Performs create workspace directories job by given paths and waits until it finished.
   *
   * @param workspaceId workspace identifier
   * @param dirs workspace directories to create
   */
  public void createDirs(String workspaceId, String... dirs) {
    execute(workspaceId, MKDIR_COMMAND_BASE, dirs);
  }

  /**
   * Asynchronously starts a job for removing workspace directories by given paths.
   *
   * @param workspaceId workspace identifier
   * @param dirs workspace directories to remove
   */
  CompletableFuture<Void> removeDirsAsync(String workspaceId, String... dirs) {
    return CompletableFuture.runAsync(
        ThreadLocalPropagateContext.wrap(() -> execute(workspaceId, RM_COMMAND_BASE, dirs)),
        executor);
  }

  /**
   * Executes the job with the specified arguments.
   *
   * @param commandBase the command base to execute
   * @param arguments the list of arguments for the specified job
   */
  @VisibleForTesting
  void execute(String workspaceId, String[] commandBase, String... arguments) {
    final String jobName = commandBase[0];
    final String podName = jobName + '-' + workspaceId;
    final String[] command = buildCommand(commandBase, arguments);
    final Pod pod = newPod(podName, command);
    OpenShiftPods pods = null;
    try {
      pods = factory.create(workspaceId).pods();
      pods.create(pod);
      final Pod finished = pods.wait(podName, WAIT_POD_TIMEOUT_MIN, POD_PREDICATE::apply);
      if (POD_PHASE_FAILED.equals(finished.getStatus().getPhase())) {
        LOG.error("Job command '%s' execution is failed.", Arrays.toString(command));
      }
    } catch (InfrastructureException ex) {
      LOG.error(
          "Unable to perform '{}' command for the workspace '{}' cause: '{}'",
          Arrays.toString(command),
          workspaceId,
          ex.getMessage());
    } finally {
      if (pods != null) {
        try {
          pods.delete(podName);
        } catch (InfrastructureException ignored) {
        }
      }
    }
  }

  /**
   * Builds the command by given base and paths.
   *
   * <p>Command is consists of base(e.g. rm -rf) and list of directories which are modified with
   * mount path.
   *
   * @param base command base
   * @param dirs the paths which are used as arguments for the command base
   * @return complete command with given arguments
   */
  @VisibleForTesting
  String[] buildCommand(String[] base, String... dirs) {
    return Stream.concat(
            Arrays.stream(base),
            Arrays.stream(dirs)
                .map(dir -> JOB_MOUNT_PATH + (dir.startsWith("/") ? dir : '/' + dir)))
        .toArray(String[]::new);
  }

  @PreDestroy
  void shutdown() {
    if (!executor.isShutdown()) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(30, SECONDS)) {
          executor.shutdownNow();
          if (!executor.awaitTermination(60, SECONDS))
            LOG.error("Couldn't shutdown PVCSubPathHelper thread pool");
        }
      } catch (InterruptedException ignored) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
      LOG.info("PVCSubPathHelper thread pool is terminated");
    }
  }

  /** Returns new instance of {@link Pod} with given name and command. */
  private Pod newPod(String podName, String[] command) {
    final Container container =
        new ContainerBuilder()
            .withName(podName)
            .withImage(jobImage)
            .withImagePullPolicy(IMAGE_PULL_POLICY)
            .withCommand(command)
            .withVolumeMounts(newVolumeMount(pvcName, JOB_MOUNT_PATH, null))
            .withNewResources()
            .withLimits(singletonMap("memory", new Quantity(jobMemoryLimit)))
            .endResources()
            .build();
    return new PodBuilder()
        .withNewMetadata()
        .withName(podName)
        .endMetadata()
        .withNewSpec()
        .withContainers(container)
        .withVolumes(newVolume(pvcName, pvcName))
        .withRestartPolicy(POD_RESTART_POLICY)
        .endSpec()
        .build();
  }

  /** Checks whether pod is Failed or Successfully finished command execution */
  static class JobFinishedPredicate implements Predicate<Pod> {
    @Override
    public boolean apply(Pod pod) {
      if (pod.getStatus() == null) {
        return false;
      }
      switch (pod.getStatus().getPhase()) {
        case POD_PHASE_FAILED:
          // fall through
        case POD_PHASE_SUCCEEDED:
          // job is finished.
          return true;
        default:
          // job is not finished.
          return false;
      }
    }
  }
}
