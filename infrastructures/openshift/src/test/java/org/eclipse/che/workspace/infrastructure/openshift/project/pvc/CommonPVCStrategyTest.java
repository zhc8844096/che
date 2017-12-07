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

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.eclipse.che.api.core.model.workspace.config.Volume;
import org.eclipse.che.api.workspace.server.model.impl.VolumeImpl;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.environment.InternalMachineConfig;
import org.eclipse.che.workspace.infrastructure.openshift.environment.OpenShiftEnvironment;
import org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftPersistentVolumeClaims;
import org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftProject;
import org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftProjectFactory;
import org.mockito.Mock;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

/**
 * Tests {@link CommonPVCStrategy}.
 *
 * @author Anton Korneta
 */
@Listeners(MockitoTestNGListener.class)
public class CommonPVCStrategyTest {

  private static final String WORKSPACE_ID = "workspace123";
  private static final String PVC_NAME = "che-claim";
  private static final String POD_NAME = "main";
  private static final String POD_NAME_2 = "second";
  private static final String CONTAINER_NAME = "app";
  private static final String CONTAINER_NAME_2 = "db";
  private static final String CONTAINER_NAME_3 = "app2";
  private static final String MACHINE_1_NAME = POD_NAME + '/' + CONTAINER_NAME;
  private static final String MACHINE_2_NAME = POD_NAME + '/' + CONTAINER_NAME_2;
  private static final String MACHINE_3_NAME = POD_NAME_2 + '/' + CONTAINER_NAME_3;
  private static final String PVC_QUANTITY = "10Gi";
  private static final String PVC_ACCESS_MODE = "RWO";
  private static final String VOLUME_1_NAME = "vol1";
  private static final String VOLUME_2_NAME = "vol2";

  @Mock private Pod pod;
  @Mock private Pod pod2;
  @Mock private PodSpec podSpec;
  @Mock private PodSpec podSpec2;
  @Mock private Container container;
  @Mock private Container container2;
  @Mock private Container container3;
  @Mock private OpenShiftEnvironment osEnv;
  @Mock private PVCSubPathHelper pvcSubPathHelper;
  @Mock private OpenShiftProjectFactory factory;
  @Mock private OpenShiftProject osProject;
  @Mock private OpenShiftPersistentVolumeClaims osPVCs;

  private CommonPVCStrategy commonPVCStrategy;

  @BeforeMethod
  public void setup() throws Exception {
    commonPVCStrategy =
        new CommonPVCStrategy(PVC_NAME, PVC_QUANTITY, PVC_ACCESS_MODE, pvcSubPathHelper);

    Map<String, InternalMachineConfig> machines = new HashMap<>();
    InternalMachineConfig machine1 = mock(InternalMachineConfig.class);
    Map<String, Volume> volumes1 = new HashMap<>();
    volumes1.put(VOLUME_1_NAME, new VolumeImpl().withPath("/path"));
    volumes1.put(VOLUME_2_NAME, new VolumeImpl().withPath("/path2"));
    when(machine1.getVolumes()).thenReturn(volumes1);
    machines.put(MACHINE_1_NAME, machine1);
    InternalMachineConfig machine2 = mock(InternalMachineConfig.class);
    Map<String, Volume> volumes2 = new HashMap<>();
    volumes2.put(VOLUME_2_NAME, new VolumeImpl().withPath("/path2"));
    when(machine2.getVolumes()).thenReturn(volumes2);
    machines.put(MACHINE_2_NAME, machine2);
    InternalMachineConfig machine3 = mock(InternalMachineConfig.class);
    Map<String, Volume> volumes3 = new HashMap<>();
    volumes3.put(VOLUME_1_NAME, new VolumeImpl().withPath("/path"));
    when(machine3.getVolumes()).thenReturn(volumes3);
    machines.put(MACHINE_3_NAME, machine3);
    when(osEnv.getMachines()).thenReturn(machines);

    Map<String, Pod> pods = new HashMap<>();
    pods.put(POD_NAME, pod);
    pods.put(POD_NAME_2, pod2);
    when(osEnv.getPods()).thenReturn(pods);

    when(pod.getSpec()).thenReturn(podSpec);
    when(pod2.getSpec()).thenReturn(podSpec2);
    when(podSpec.getContainers()).thenReturn(asList(container, container2));
    when(podSpec2.getContainers()).thenReturn(singletonList(container3));
    when(podSpec.getVolumes()).thenReturn(new ArrayList<>());
    when(podSpec2.getVolumes()).thenReturn(new ArrayList<>());
    when(container.getName()).thenReturn(CONTAINER_NAME);
    when(container2.getName()).thenReturn(CONTAINER_NAME_2);
    when(container3.getName()).thenReturn(CONTAINER_NAME_3);
    when(container.getVolumeMounts()).thenReturn(new ArrayList<>());
    when(container2.getVolumeMounts()).thenReturn(new ArrayList<>());
    when(container3.getVolumeMounts()).thenReturn(new ArrayList<>());

    doNothing().when(pvcSubPathHelper).execute(any(), any(), any());
    when(osEnv.getPersistentVolumeClaims()).thenReturn(new HashMap<>());
    when(pvcSubPathHelper.removeDirsAsync(anyString(), any(String.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    when(factory.create(WORKSPACE_ID)).thenReturn(osProject);
    when(osProject.persistentVolumeClaims()).thenReturn(osPVCs);

    mockName(pod, POD_NAME);
    mockName(pod2, POD_NAME_2);
  }

  @Test
  public void addPVCWithConfiguredNameToOsEnv() throws Exception {
    when(osEnv.getPersistentVolumeClaims()).thenReturn(new HashMap<>());

    commonPVCStrategy.prepare(osEnv, WORKSPACE_ID);

    // 2 volumes in machine1
    verify(container, times(2)).getVolumeMounts();
    verify(container2).getVolumeMounts();
    verify(container3).getVolumeMounts();
    // 1 addition + 3 checks because there are 3 volumes in pod
    verify(podSpec, times(4)).getVolumes();
    // 1 addition + 1 check
    verify(podSpec2, times(2)).getVolumes();
    verify(pvcSubPathHelper)
        .createDirs(
            WORKSPACE_ID, expectedVolumeDir(VOLUME_1_NAME), expectedVolumeDir(VOLUME_2_NAME));
    assertFalse(podSpec.getVolumes().isEmpty());
    assertFalse(podSpec2.getVolumes().isEmpty());
    assertFalse(container.getVolumeMounts().isEmpty());
    assertFalse(container2.getVolumeMounts().isEmpty());
    assertFalse(container3.getVolumeMounts().isEmpty());
    assertFalse(osEnv.getPersistentVolumeClaims().isEmpty());
    assertTrue(osEnv.getPersistentVolumeClaims().containsKey(PVC_NAME));
  }

  @Test
  public void testReplacePVCWhenItsAlreadyInOsEnvironment() throws Exception {
    final Map<String, PersistentVolumeClaim> claims = new HashMap<>();
    final PersistentVolumeClaim provisioned = mock(PersistentVolumeClaim.class);
    claims.put(PVC_NAME, provisioned);
    when(osEnv.getPersistentVolumeClaims()).thenReturn(claims);

    commonPVCStrategy.prepare(osEnv, WORKSPACE_ID);

    verify(factory).create(WORKSPACE_ID);
    assertNotEquals(osEnv.getPersistentVolumeClaims().get(PVC_NAME), provisioned);
  }

  @Test(expectedExceptions = InfrastructureException.class)
  public void throwInfrastructureExceptionWhenOsProjectCreationFailed() throws Exception {
    when(factory.create(any())).thenThrow(new InfrastructureException("Project creation failed"));

    commonPVCStrategy.prepare(osEnv, WORKSPACE_ID);
  }

  @Test(expectedExceptions = InfrastructureException.class)
  public void throwInfrastructureExceptionWhenPVCCreationFailed() throws Exception {
    doThrow(InfrastructureException.class).when(osPVCs).createIfNotExist(any());

    commonPVCStrategy.prepare(osEnv, WORKSPACE_ID);
  }

  @Test
  public void testDoNothingWhenNoMachineWithWsAgentFoundInOsEnvironment() throws Exception {
    final Pod pod = mock(Pod.class);
    final PodSpec podSpec = mock(PodSpec.class);
    final Container container = mock(Container.class);
    mockName(pod, "testPod");
    when(pod.getSpec()).thenReturn(podSpec);
    when(osEnv.getPods()).thenReturn(singletonMap("testPod", pod));
    when(podSpec.getContainers()).thenReturn(singletonList(container));
    when(container.getName()).thenReturn("container");

    commonPVCStrategy.prepare(osEnv, WORKSPACE_ID);

    verify(container, never()).getVolumeMounts();
    verify(podSpec, never()).getVolumes();
  }

  @Test
  public void testCleanup() throws Exception {
    commonPVCStrategy.cleanup(WORKSPACE_ID);

    verify(pvcSubPathHelper).removeDirsAsync(WORKSPACE_ID, WORKSPACE_ID);
  }

  static void mockName(HasMetadata obj, String name) {
    final ObjectMeta objectMeta = mock(ObjectMeta.class);
    when(obj.getMetadata()).thenReturn(objectMeta);
    when(objectMeta.getName()).thenReturn(name);
  }

  private String expectedVolumeDir(String volumeName) {
    return WORKSPACE_ID + '/' + volumeName;
  }
}
