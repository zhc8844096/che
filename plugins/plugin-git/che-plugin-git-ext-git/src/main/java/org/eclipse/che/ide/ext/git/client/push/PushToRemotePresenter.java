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
package org.eclipse.che.ide.ext.git.client.push;

import static java.util.Collections.singletonList;
import static org.eclipse.che.api.core.ErrorCodes.UNAUTHORIZED_GIT_OPERATION;
import static org.eclipse.che.api.git.shared.BranchListMode.LIST_LOCAL;
import static org.eclipse.che.api.git.shared.BranchListMode.LIST_REMOTE;
import static org.eclipse.che.api.git.shared.ProviderInfo.PROVIDER_NAME;
import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.PROGRESS;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.SUCCESS;
import static org.eclipse.che.ide.ext.git.client.compare.branchlist.BranchListPresenter.BRANCH_LIST_COMMAND_NAME;
import static org.eclipse.che.ide.ext.git.client.remote.RemotePresenter.REMOTE_REPO_COMMAND_NAME;
import static org.eclipse.che.ide.util.ExceptionUtils.getAttributes;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotNull;
import org.eclipse.che.api.core.rest.shared.dto.ServiceError;
import org.eclipse.che.api.git.shared.Branch;
import org.eclipse.che.api.git.shared.BranchListMode;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.auth.OAuthServiceClient;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.notification.StatusNotification;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.commons.exception.UnauthorizedException;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.git.client.BranchFilterByRemote;
import org.eclipse.che.ide.ext.git.client.BranchSearcher;
import org.eclipse.che.ide.ext.git.client.GitLocalizationConstant;
import org.eclipse.che.ide.ext.git.client.GitServiceClient;
import org.eclipse.che.ide.ext.git.client.outputconsole.GitOutputConsole;
import org.eclipse.che.ide.ext.git.client.outputconsole.GitOutputConsoleFactory;
import org.eclipse.che.ide.processes.panel.ProcessesPanelPresenter;
import org.eclipse.che.ide.util.ExceptionUtils;
import org.eclipse.che.ide.util.StringUtils;

/**
 * Presenter for pushing changes to remote repository.
 *
 * @author Ann Zhuleva
 * @author Sergii Leschenko
 * @author Vlad Zhukovskyi
 */
@Singleton
public class PushToRemotePresenter implements PushToRemoteView.ActionDelegate {
  public static final String PUSH_COMMAND_NAME = "Git push";
  public static final String CONFIG_COMMAND_NAME = "Git config";

  private final GitOutputConsoleFactory gitOutputConsoleFactory;
  private final ProcessesPanelPresenter processesPanelPresenter;
  private final DtoFactory dtoFactory;
  private final BranchSearcher branchSearcher;
  private final PushToRemoteView view;
  private final GitServiceClient service;
  private final AppContext appContext;
  private final GitLocalizationConstant constant;
  private final NotificationManager notificationManager;
  private Project project;
  private OAuthServiceClient oAuthServiceClient;

  @Inject
  public PushToRemotePresenter(
      DtoFactory dtoFactory,
      PushToRemoteView view,
      GitServiceClient service,
      AppContext appContext,
      GitLocalizationConstant constant,
      NotificationManager notificationManager,
      BranchSearcher branchSearcher,
      GitOutputConsoleFactory gitOutputConsoleFactory,
      ProcessesPanelPresenter processesPanelPresenter,
      OAuthServiceClient oAuthServiceClient) {
    this.dtoFactory = dtoFactory;
    this.branchSearcher = branchSearcher;
    this.view = view;
    this.oAuthServiceClient = oAuthServiceClient;
    this.view.setDelegate(this);
    this.service = service;
    this.appContext = appContext;
    this.constant = constant;
    this.notificationManager = notificationManager;
    this.gitOutputConsoleFactory = gitOutputConsoleFactory;
    this.processesPanelPresenter = processesPanelPresenter;
  }

  public void showDialog(Project project) {
    this.project = project;

    updateRemotes();
  }

  /**
   * Get the list of remote repositories for local one. If remote repositories are found, then get
   * the list of branches (remote and local).
   */
  void updateRemotes() {
    service
        .remoteList(project.getLocation(), null, true)
        .then(
            remotes -> {
              updateLocalBranches();
              view.setRepositories(remotes);
              view.setEnablePushButton(!remotes.isEmpty());
              view.setSelectedForcePushCheckBox(false);
              view.showDialog();
            })
        .catchError(
            error -> {
              String errorMessage =
                  error.getMessage() != null ? error.getMessage() : constant.remoteListFailed();
              GitOutputConsole console = gitOutputConsoleFactory.create(REMOTE_REPO_COMMAND_NAME);
              console.printError(errorMessage);
              processesPanelPresenter.addCommandOutput(console);
              notificationManager.notify(constant.remoteListFailed(), FAIL, FLOAT_MODE);
              view.setEnablePushButton(false);
            });
  }

  /** Update list of local and remote branches on view. */
  void updateLocalBranches() {
    // getting local branches
    getBranchesForCurrentProject(
        LIST_LOCAL,
        new AsyncCallback<List<Branch>>() {
          @Override
          public void onSuccess(List<Branch> result) {
            List<String> localBranches = branchSearcher.getLocalBranchesToDisplay(result);
            view.setLocalBranches(localBranches);

            for (Branch branch : result) {
              if (branch.isActive()) {
                view.selectLocalBranch(branch.getDisplayName());
                break;
              }
            }

            // getting remote branch only after selecting current local branch
            updateRemoteBranches();
          }

          @Override
          public void onFailure(Throwable exception) {
            String errorMessage =
                exception.getMessage() != null
                    ? exception.getMessage()
                    : constant.localBranchesListFailed();
            GitOutputConsole console = gitOutputConsoleFactory.create(BRANCH_LIST_COMMAND_NAME);
            console.printError(errorMessage);
            processesPanelPresenter.addCommandOutput(console);
            notificationManager.notify(constant.localBranchesListFailed(), FAIL, FLOAT_MODE);
            view.setEnablePushButton(false);
          }
        });
  }

  /** Update list of remote branches on view. */
  void updateRemoteBranches() {
    getBranchesForCurrentProject(
        LIST_REMOTE,
        new AsyncCallback<List<Branch>>() {
          @Override
          public void onSuccess(final List<Branch> result) {
            // Need to add the upstream of local branch in the list of remote branches
            // to be able to push changes to the remote upstream branch
            getUpstreamBranch(
                new AsyncCallback<Branch>() {
                  @Override
                  public void onSuccess(Branch upstream) {
                    BranchFilterByRemote remoteRefsHandler =
                        new BranchFilterByRemote(view.getRepository());

                    final List<String> remoteBranches =
                        branchSearcher.getRemoteBranchesToDisplay(remoteRefsHandler, result);

                    String selectedRemoteBranch = null;
                    if (upstream != null
                        && upstream.isRemote()
                        && remoteRefsHandler.isLinkedTo(upstream)) {
                      String simpleUpstreamName =
                          remoteRefsHandler.getBranchNameWithoutRefs(upstream);
                      if (!remoteBranches.contains(simpleUpstreamName)) {
                        remoteBranches.add(simpleUpstreamName);
                      }
                      selectedRemoteBranch = simpleUpstreamName;
                    }

                    // Need to add the current local branch in the list of remote branches
                    // to be able to push changes to the remote branch  with same name
                    final String currentBranch = view.getLocalBranch();
                    if (!remoteBranches.contains(currentBranch)) {
                      remoteBranches.add(currentBranch);
                    }
                    if (selectedRemoteBranch == null) {
                      selectedRemoteBranch = currentBranch;
                    }

                    view.setRemoteBranches(remoteBranches);
                    view.selectRemoteBranch(selectedRemoteBranch);
                  }

                  @Override
                  public void onFailure(Throwable caught) {
                    GitOutputConsole console = gitOutputConsoleFactory.create(CONFIG_COMMAND_NAME);
                    console.printError(constant.failedGettingConfig());
                    processesPanelPresenter.addCommandOutput(console);
                    notificationManager.notify(constant.failedGettingConfig(), FAIL, FLOAT_MODE);
                  }
                });
          }

          @Override
          public void onFailure(Throwable exception) {
            String errorMessage =
                exception.getMessage() != null
                    ? exception.getMessage()
                    : constant.remoteBranchesListFailed();
            GitOutputConsole console = gitOutputConsoleFactory.create(BRANCH_LIST_COMMAND_NAME);
            console.printError(errorMessage);
            processesPanelPresenter.addCommandOutput(console);
            notificationManager.notify(constant.remoteBranchesListFailed(), FAIL, FLOAT_MODE);
            view.setEnablePushButton(false);
          }
        });
  }

  /**
   * Get upstream branch for selected local branch. Can invoke {@code onSuccess(null)} if upstream
   * branch isn't set
   */
  private void getUpstreamBranch(final AsyncCallback<Branch> result) {

    final String configBranchRemote = "branch." + view.getLocalBranch() + ".remote";
    final String configUpstreamBranch = "branch." + view.getLocalBranch() + ".merge";
    service
        .config(project.getLocation(), Arrays.asList(configUpstreamBranch, configBranchRemote))
        .then(
            configs -> {
              if (configs.containsKey(configBranchRemote)
                  && configs.containsKey(configUpstreamBranch)) {
                String displayName =
                    configs.get(configBranchRemote) + "/" + configs.get(configUpstreamBranch);
                Branch upstream =
                    dtoFactory
                        .createDto(Branch.class)
                        .withActive(false)
                        .withRemote(true)
                        .withDisplayName(displayName)
                        .withName("refs/remotes/" + displayName);

                result.onSuccess(upstream);
              } else {
                result.onSuccess(null);
              }
            })
        .catchError(
            error -> {
              result.onFailure(error.getCause());
            });
  }

  /**
   * Get the list of branches.
   *
   * @param remoteMode is a remote mode
   */
  void getBranchesForCurrentProject(
      @NotNull final BranchListMode remoteMode, final AsyncCallback<List<Branch>> asyncResult) {
    service
        .branchList(project.getLocation(), remoteMode)
        .then(
            branches -> {
              asyncResult.onSuccess(branches);
            })
        .catchError(
            error -> {
              asyncResult.onFailure(error.getCause());
            });
  }

  /** {@inheritDoc} */
  @Override
  public void onPushClicked() {
    final StatusNotification notification =
        notificationManager.notify(constant.pushProcess(), PROGRESS, FLOAT_MODE);

    final String repository = view.getRepository();
    final GitOutputConsole console = gitOutputConsoleFactory.create(PUSH_COMMAND_NAME);
    service
        .push(project.getLocation(), getRefs(), repository, view.isForcePushSelected())
        .then(
            response -> {
              console.print(response.getCommandOutput());
              processesPanelPresenter.addCommandOutput(console);
              notification.setStatus(SUCCESS);
              if (response.getCommandOutput().contains("Everything up-to-date")) {
                notification.setTitle(constant.pushUpToDate());
              } else {
                notification.setTitle(constant.pushSuccess(repository));
              }
            })
        .catchError(
            error -> {
              if (ExceptionUtils.getErrorCode(error.getCause()) == UNAUTHORIZED_GIT_OPERATION) {
                Map<String, String> attributes = getAttributes(error.getCause());
                String providerName = attributes.get(PROVIDER_NAME);
                if (!StringUtils.isNullOrEmpty(providerName)) {
                  pushAuthenticated(repository,  providerName, console, notification);
                }
              } else {
                handleError(error.getCause(), notification, console);
                processesPanelPresenter.addCommandOutput(console);
              }
            });
    view.close();
  }

  protected void pushAuthenticated(final String repository, final String providerName,
      GitOutputConsole console, StatusNotification notification) {
        oAuthServiceClient.getToken(providerName)
        .thenPromise(token -> service
            .push(
                project.getLocation(),
                getRefs(),
                repository,
                view.isForcePushSelected(),
                token.getToken(),
                token.getToken()))
        .then(
            response -> {
              console.print(response.getCommandOutput());
              processesPanelPresenter.addCommandOutput(console);
              notification.setStatus(SUCCESS);
              if (response.getCommandOutput().contains("Everything up-to-date")) {
                notification.setTitle(constant.pushUpToDate());
              } else {
                notification.setTitle(constant.pushSuccess(repository));
              }
            })
        .catchError(error -> {
          handleError(error.getCause(), notification, console);
          processesPanelPresenter.addCommandOutput(console);
        });
  }

  /** @return list of refs to push */
  @NotNull
  private List<String> getRefs() {
    String localBranch = view.getLocalBranch();
    String remoteBranch = view.getRemoteBranch();
    return singletonList(localBranch + ":" + remoteBranch);
  }

  /** {@inheritDoc} */
  @Override
  public void onCancelClicked() {
    view.close();
  }

  /** {@inheritDoc} */
  @Override
  public void onLocalBranchChanged() {
    view.addRemoteBranch(view.getLocalBranch());
    view.selectRemoteBranch(view.getLocalBranch());
  }

  @Override
  public void onRepositoryChanged() {
    updateRemoteBranches();
  }

  /**
   * Handler some action whether some exception happened.
   *
   * @param throwable exception what happened
   */
  void handleError(
      @NotNull Throwable throwable, StatusNotification notification, GitOutputConsole console) {
    notification.setStatus(FAIL);
    if (throwable instanceof UnauthorizedException) {
      console.printError(constant.messagesNotAuthorizedTitle());
      console.print(constant.messagesNotAuthorizedContent());
      notification.setTitle(constant.messagesNotAuthorizedTitle());
      notification.setContent(constant.messagesNotAuthorizedContent());
      return;
    }

    String errorMessage = throwable.getMessage();
    if (errorMessage == null) {
      console.printError(constant.pushFail());
      notification.setTitle(constant.pushFail());
      return;
    }

    try {
      errorMessage = dtoFactory.createDtoFromJson(errorMessage, ServiceError.class).getMessage();
      if (errorMessage.equals("Unable get private ssh key")) {
        console.printError(constant.messagesUnableGetSshKey());
        notification.setTitle(constant.messagesUnableGetSshKey());
        return;
      }
      console.printError(errorMessage);
      notification.setTitle(errorMessage);
    } catch (Exception e) {
      console.printError(errorMessage);
      notification.setTitle(errorMessage);
    }
  }
}
