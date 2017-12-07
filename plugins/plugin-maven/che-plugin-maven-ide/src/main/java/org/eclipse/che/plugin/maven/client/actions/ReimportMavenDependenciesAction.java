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
package org.eclipse.che.plugin.maven.client.actions;

import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.EMERGE_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;
import static org.eclipse.che.ide.part.perspectives.project.ProjectPerspective.PROJECT_PERSPECTIVE_ID;
import static org.eclipse.che.plugin.maven.shared.MavenAttributes.MAVEN_ID;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.validation.constraints.NotNull;
import org.eclipse.che.ide.Resources;
import org.eclipse.che.ide.api.action.AbstractPerspectiveAction;
import org.eclipse.che.ide.api.action.ActionEvent;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.service.JavaLanguageExtensionServiceClient;
import org.eclipse.che.ide.util.loging.Log;
import org.eclipse.che.jdt.ls.extension.api.dto.ReImportMavenProjectsCommandParameters;
import org.eclipse.che.plugin.maven.client.MavenLocalizationConstant;
import org.eclipse.che.plugin.maven.client.service.MavenServerServiceClient;

/**
 * Action for reimport maven dependencies.
 *
 * @author Roman Nikitenko
 */
@Singleton
public class ReimportMavenDependenciesAction extends AbstractPerspectiveAction {

  private final AppContext appContext;
  private final NotificationManager notificationManager;
  private final MavenServerServiceClient mavenServerServiceClient;
  private final JavaLanguageExtensionServiceClient javaLanguageExtensionServiceClient;
  private final DtoFactory dtoFactory;

  @Inject
  public ReimportMavenDependenciesAction(
      MavenLocalizationConstant constant,
      AppContext appContext,
      NotificationManager notificationManager,
      Resources resources,
      JavaLanguageExtensionServiceClient javaLanguageExtensionServiceClient,
      DtoFactory dtoFactory,
      MavenServerServiceClient mavenServerServiceClient) {
    super(
        Collections.singletonList(PROJECT_PERSPECTIVE_ID),
        constant.actionReimportDependenciesTitle(),
        constant.actionReimportDependenciesDescription(),
        resources.refresh());
    this.appContext = appContext;
    this.notificationManager = notificationManager;
    this.javaLanguageExtensionServiceClient = javaLanguageExtensionServiceClient;
    this.dtoFactory = dtoFactory;
    this.mavenServerServiceClient = mavenServerServiceClient;
  }

  @Override
  public void updateInPerspective(@NotNull ActionEvent event) {
    event.getPresentation().setEnabledAndVisible(isMavenProjectSelected());
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    ReImportMavenProjectsCommandParameters paramsDto =
        dtoFactory
            .createDto(ReImportMavenProjectsCommandParameters.class)
            .withProjectsToUpdate(getPathsToSelectedMavenProject());

    javaLanguageExtensionServiceClient
        .reImportMavenProjects(paramsDto)
        .then(
            projects -> {
              for (String s : projects) {
                Log.warn(getClass(), "----> " + s);
              }
            })
        .catchError(
            error -> {
              notificationManager.notify(
                  "Problem with reimporting maven dependencies",
                  error.getMessage(),
                  FAIL,
                  EMERGE_MODE);
            });
  }

  private boolean isMavenProjectSelected() {
    return !getPathsToSelectedMavenProject().isEmpty();
  }

  private List<String> getPathsToSelectedMavenProject() {

    final Resource[] resources = appContext.getResources();

    if (resources == null) {
      return Collections.emptyList();
    }

    Set<String> paths = new HashSet<>();

    for (Resource resource : resources) {
      final Optional<Project> project = resource.getRelatedProject();

      if (project.isPresent() && project.get().isTypeOf(MAVEN_ID)) {
        paths.add(project.get().getLocation().toString());
      }
    }

    return new ArrayList<>(paths);
  }
}
