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
package org.eclipse.che.ide.ext.java.client.service;

import static org.eclipse.che.ide.api.jsonrpc.Constants.WS_AGENT_JSON_RPC_ENDPOINT_ID;
import static org.eclipse.che.ide.ext.java.shared.Constants.EFFECTIVE_POM_REQUEST_TIMEOUT;
import static org.eclipse.che.ide.ext.java.shared.Constants.FILE_STRUCTURE_REQUEST_TIMEOUT;
import static org.eclipse.che.ide.ext.java.shared.Constants.REIMPORT_MAVEN_PROJECTS_REQUEST_TIMEOUT;

import com.google.gwt.jsonp.client.TimeoutException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import org.eclipse.che.api.core.jsonrpc.commons.RequestTransmitter;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.promises.client.js.JsPromiseError;
import org.eclipse.che.api.promises.client.js.Promises;
import org.eclipse.che.jdt.ls.extension.api.dto.ExtendedSymbolInformation;
import org.eclipse.che.jdt.ls.extension.api.dto.FileStructureCommandParameters;
import org.eclipse.che.jdt.ls.extension.api.dto.ReImportMavenProjectsCommandParameters;
import org.eclipse.che.plugin.languageserver.ide.service.ServiceUtil;

@Singleton
public class JavaLanguageExtensionServiceClient {
  private final RequestTransmitter requestTransmitter;

  @Inject
  public JavaLanguageExtensionServiceClient(RequestTransmitter requestTransmitter) {
    this.requestTransmitter = requestTransmitter;
  }

  public Promise<List<ExtendedSymbolInformation>> fileStructure(
      FileStructureCommandParameters params) {
    return Promises.create(
        (resolve, reject) -> {
          requestTransmitter
              .newRequest()
              .endpointId(WS_AGENT_JSON_RPC_ENDPOINT_ID)
              .methodName("java/file-structure")
              .paramsAsDto(params)
              .sendAndReceiveResultAsListOfDto(
                  ExtendedSymbolInformation.class, FILE_STRUCTURE_REQUEST_TIMEOUT)
              .onSuccess(resolve::apply)
              .onTimeout(() -> reject.apply(JsPromiseError.create(new TimeoutException("Timeout"))))
              .onFailure(error -> reject.apply(ServiceUtil.getPromiseError(error)));
        });
  }

  /**
   * Gets effective pom for maven project.
   *
   * @param pathToProject path to project relatively to projects root (e.g. /projects)
   * @return effective pom
   */
  public Promise<String> effectivePom(String pathToProject) {
    return Promises.create(
        (resolve, reject) ->
            requestTransmitter
                .newRequest()
                .endpointId(WS_AGENT_JSON_RPC_ENDPOINT_ID)
                .methodName("java/effective-pom")
                .paramsAsString(pathToProject)
                .sendAndReceiveResultAsString(EFFECTIVE_POM_REQUEST_TIMEOUT)
                .onSuccess(resolve::apply)
                .onTimeout(
                    () ->
                        reject.apply(
                            JsPromiseError.create(
                                new TimeoutException("Timeout while getting effective pom."))))
                .onFailure(error -> reject.apply(ServiceUtil.getPromiseError(error))));
  }

  /**
   * Updates specified maven projects.
   *
   * @param params contains list of paths to projects which should be reimported
   * @return list of paths to updated projects
   */
  public Promise<List<String>> reImportMavenProjects(
      ReImportMavenProjectsCommandParameters params) {
    return Promises.create(
        (resolve, reject) ->
            requestTransmitter
                .newRequest()
                .endpointId(WS_AGENT_JSON_RPC_ENDPOINT_ID)
                .methodName("java/reimport-maven-projects")
                .paramsAsDto(params)
                .sendAndReceiveResultAsListOfString(REIMPORT_MAVEN_PROJECTS_REQUEST_TIMEOUT)
                .onSuccess(resolve::apply)
                .onTimeout(
                    () ->
                        reject.apply(
                            JsPromiseError.create(
                                new TimeoutException("Failed to update maven project."))))
                .onFailure(error -> reject.apply(ServiceUtil.getPromiseError(error))));
  }
}
