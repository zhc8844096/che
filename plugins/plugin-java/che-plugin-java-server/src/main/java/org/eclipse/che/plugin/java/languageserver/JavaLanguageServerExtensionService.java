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
package org.eclipse.che.plugin.java.languageserver;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.eclipse.che.api.languageserver.service.LanguageServiceUtils.prefixURI;
import static org.eclipse.che.api.languageserver.service.LanguageServiceUtils.removePrefixUri;
import static org.eclipse.che.ide.ext.java.shared.Constants.EFFECTIVE_POM_REQUEST_TIMEOUT;
import static org.eclipse.che.ide.ext.java.shared.Constants.FILE_STRUCTURE_REQUEST_TIMEOUT;
import static org.eclipse.che.ide.ext.java.shared.Constants.REIMPORT_MAVEN_PROJECTS_REQUEST_TIMEOUT;
import static org.eclipse.che.jdt.ls.extension.api.Commands.FILE_STRUCTURE_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.FIND_TESTS_FROM_ENTRY_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.FIND_TESTS_FROM_FOLDER_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.FIND_TESTS_FROM_PROJECT_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.FIND_TESTS_IN_FILE_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.FIND_TEST_BY_CURSOR_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.GET_EFFECTIVE_POM_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.GET_OUTPUT_DIR_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.REIMPORT_MAVEN_PROJECTS_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.RESOLVE_CLASSPATH_COMMAND;
import static org.eclipse.che.jdt.ls.extension.api.Commands.TEST_DETECT_COMMAND;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.eclipse.che.api.core.jsonrpc.commons.JsonRpcException;
import org.eclipse.che.api.core.jsonrpc.commons.RequestHandlerConfigurator;
import org.eclipse.che.api.languageserver.registry.LanguageServerRegistry;
import org.eclipse.che.api.languageserver.service.LanguageServiceUtils;
import org.eclipse.che.jdt.ls.extension.api.dto.ExtendedSymbolInformation;
import org.eclipse.che.jdt.ls.extension.api.dto.FileStructureCommandParameters;
import org.eclipse.che.jdt.ls.extension.api.dto.ReImportMavenProjectsCommandParameters;
import org.eclipse.che.jdt.ls.extension.api.dto.TestFindParameters;
import org.eclipse.che.jdt.ls.extension.api.dto.TestPosition;
import org.eclipse.che.jdt.ls.extension.api.dto.TestPositionParameters;
import org.eclipse.che.plugin.java.languageserver.dto.DtoServerImpls.ExtendedSymbolInformationDto;
import org.eclipse.che.plugin.java.languageserver.dto.DtoServerImpls.TestPositionDto;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.jsonrpc.json.adapters.CollectionTypeAdapterFactory;
import org.eclipse.lsp4j.jsonrpc.json.adapters.EitherTypeAdapterFactory;
import org.eclipse.lsp4j.jsonrpc.json.adapters.EnumTypeAdapterFactory;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This service makes custom commands in our jdt.ls extension available to clients.
 *
 * @author Thomas Mäder
 */
public class JavaLanguageServerExtensionService {
  private final Gson gson;
  private final LanguageServerRegistry registry;

  private static final Logger LOG =
      LoggerFactory.getLogger(JavaLanguageServerExtensionService.class);
  private final RequestHandlerConfigurator requestHandler;

  @Inject
  public JavaLanguageServerExtensionService(
      LanguageServerRegistry registry, RequestHandlerConfigurator requestHandler) {
    this.registry = registry;
    this.requestHandler = requestHandler;
    this.gson =
        new GsonBuilder()
            .registerTypeAdapterFactory(new CollectionTypeAdapterFactory())
            .registerTypeAdapterFactory(new EitherTypeAdapterFactory())
            .registerTypeAdapterFactory(new EnumTypeAdapterFactory())
            .create();
  }

  @PostConstruct
  public void configureMethods() {
    requestHandler
        .newConfiguration()
        .methodName("java/file-structure")
        .paramsAsDto(FileStructureCommandParameters.class)
        .resultAsListOfDto(ExtendedSymbolInformationDto.class)
        .withFunction(this::executeFileStructure);

    requestHandler
        .newConfiguration()
        .methodName("java/effective-pom")
        .paramsAsString()
        .resultAsString()
        .withFunction(this::getEffectivePom);

    requestHandler
        .newConfiguration()
        .methodName("java/reimport-maven-projects")
        .paramsAsDto(ReImportMavenProjectsCommandParameters.class)
        .resultAsListOfString()
        .withFunction(this::reImportMavenProjects);
  }

  /**
   * Compute output directory of the project.
   *
   * @param projectUri project URI
   * @return output directory
   */
  public String getOutputDir(String projectUri) {
    CompletableFuture<Object> result =
        executeCommand(GET_OUTPUT_DIR_COMMAND, singletonList(projectUri));
    Type targetClassType = new TypeToken<String>() {}.getType();
    try {
      return gson.fromJson(gson.toJson(result.get(10, TimeUnit.SECONDS)), targetClassType);
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  /**
   * Detects test method by cursor position.
   *
   * @param fileUri file URI
   * @param testAnnotation test method annotation
   * @param cursorOffset cursor position
   * @return test position {@link TestPosition}
   */
  public List<TestPositionDto> detectTest(String fileUri, String testAnnotation, int cursorOffset) {
    TestPositionParameters parameters =
        new TestPositionParameters(fileUri, testAnnotation, cursorOffset);
    CompletableFuture<Object> result =
        executeCommand(TEST_DETECT_COMMAND, singletonList(parameters));
    Type targetClassType = new TypeToken<ArrayList<TestPosition>>() {}.getType();
    try {
      List<TestPosition> positions =
          gson.fromJson(gson.toJson(result.get(10, TimeUnit.SECONDS)), targetClassType);
      return positions.stream().map(TestPositionDto::new).collect(Collectors.toList());
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  /**
   * Compute resolved classpath of the project.
   *
   * @param projectUri project URI
   * @return resolved classpath
   */
  public List<String> getResolvedClasspath(String projectUri) {
    CompletableFuture<Object> result =
        executeCommand(RESOLVE_CLASSPATH_COMMAND, singletonList(projectUri));
    Type targetClassType = new TypeToken<ArrayList<String>>() {}.getType();
    try {
      return gson.fromJson(gson.toJson(result.get(10, TimeUnit.SECONDS)), targetClassType);
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  /**
   * Finds tests in the class.
   *
   * @param fileUri file URI
   * @param methodAnnotation test method annotation
   * @param classAnnotation test class runner annotation
   * @return fqn of the class if it contains tests
   */
  public List<String> findTestsInFile(
      String fileUri, String methodAnnotation, String classAnnotation) {
    return executeFindTestsCommand(
        FIND_TESTS_IN_FILE_COMMAND, fileUri, methodAnnotation, classAnnotation, 0, emptyList());
  }

  /**
   * Finds tests in the project.
   *
   * @param projectUri project folder URI
   * @param methodAnnotation test method annotation
   * @param classAnnotation test class runner annotation
   * @return list of fqns of the classes if they contain tests
   */
  public List<String> findTestsFromProject(
      String projectUri, String methodAnnotation, String classAnnotation) {
    return executeFindTestsCommand(
        FIND_TESTS_FROM_PROJECT_COMMAND,
        projectUri,
        methodAnnotation,
        classAnnotation,
        0,
        emptyList());
  }

  /**
   * Finds tests in the folder.
   *
   * @param folderUri folder URI
   * @param methodAnnotation test method annotation
   * @param classAnnotation test class runner annotation
   * @return list of fqns of the classes if they contain tests
   */
  public List<String> findTestsFromFolder(
      String folderUri, String methodAnnotation, String classAnnotation) {
    return executeFindTestsCommand(
        FIND_TESTS_FROM_FOLDER_COMMAND,
        folderUri,
        methodAnnotation,
        classAnnotation,
        0,
        emptyList());
  }

  /**
   * Finds test by cursor position.
   *
   * @param fileUri URI of active file
   * @param methodAnnotation test method annotation
   * @param classAnnotation test class runner annotation
   * @param offset cursor offset
   * @return fqn of the classes if contains tests and cursor is outside of test method or returns
   *     fqn#methodName if a method is test and cursor is in this method otherwise returns empty
   *     list
   */
  public List<String> findTestsByCursorPosition(
      String fileUri, String methodAnnotation, String classAnnotation, int offset) {
    return executeFindTestsCommand(
        FIND_TEST_BY_CURSOR_COMMAND,
        fileUri,
        methodAnnotation,
        classAnnotation,
        offset,
        emptyList());
  }

  /**
   * Finds fqns of test classes.
   *
   * @param methodAnnotation test method annotation
   * @param classAnnotation test class runner annotation
   * @param entry list of URI of test classes
   * @return fqns of test classes
   */
  public List<String> findTestsFromSet(
      String methodAnnotation, String classAnnotation, List<String> entry) {
    return executeFindTestsCommand(
        FIND_TESTS_FROM_ENTRY_COMMAND, "", methodAnnotation, classAnnotation, 0, entry);
  }

  /**
   * Compute a file structure tree.
   *
   * @param params command parameters {@link FileStructureCommandParameters}
   * @return file structure tree
   */
  public List<ExtendedSymbolInformationDto> executeFileStructure(
      FileStructureCommandParameters params) {
    LOG.info("Requesting files structure for {}", params);
    params.setUri(prefixURI(params.getUri()));
    CompletableFuture<Object> result =
        executeCommand(FILE_STRUCTURE_COMMAND, singletonList(params));
    Type targetClassType = new TypeToken<ArrayList<ExtendedSymbolInformation>>() {}.getType();
    try {
      List<ExtendedSymbolInformation> symbols =
          gson.fromJson(gson.toJson(result.get(10, TimeUnit.SECONDS)), targetClassType);
      return symbols
          .stream()
          .map(
              symbol -> {
                fixLocation(symbol);
                return symbol;
              })
          .map(ExtendedSymbolInformationDto::new)
          .collect(Collectors.toList());
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  /**
   * Retrieves effective pom for specified project.
   *
   * @param projectPath path to project relatively to projects root (e.g. /projects)
   * @return effective pom for given project
   */
  public String getEffectivePom(String projectPath) {
    final String projectUri = prefixURI(projectPath);

    CompletableFuture<Object> result =
        executeCommand(GET_EFFECTIVE_POM_COMMAND, singletonList(projectUri));

    Type targetClassType = new TypeToken<String>() {}.getType();
    try {
      return gson.fromJson(
          gson.toJson(result.get(EFFECTIVE_POM_REQUEST_TIMEOUT, TimeUnit.SECONDS)),
          targetClassType);
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  /**
   * Updates given maven projects.
   *
   * @param parameters dto with list of paths to projects (relatively to projects root (e.g.
   *     /projects)) which should be re-imported.
   * @return list of paths (relatively to projects root) to projects which were updated.
   */
  public List<String> reImportMavenProjects(ReImportMavenProjectsCommandParameters parameters) {
    final List<String> projectsToReImport = parameters.getProjectsToUpdate();
    if (projectsToReImport.isEmpty()) {
      return emptyList();
    }

    ListIterator<String> iterator = projectsToReImport.listIterator();
    while (iterator.hasNext()) {
      iterator.set(prefixURI(iterator.next()));
    }

    CompletableFuture<Object> requestResult =
        executeCommand(REIMPORT_MAVEN_PROJECTS_COMMAND, singletonList(parameters));

    final List<String> result;
    Type targetClassType = new TypeToken<ArrayList<String>>() {}.getType();
    try {
      result =
          gson.fromJson(
              gson.toJson(
                  requestResult.get(REIMPORT_MAVEN_PROJECTS_REQUEST_TIMEOUT, TimeUnit.SECONDS)),
              targetClassType);
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }

    iterator = result.listIterator();
    while (iterator.hasNext()) {
      iterator.set(removePrefixUri(iterator.next()));
    }
    return result;
  }

  private List<String> executeFindTestsCommand(
      String commandId,
      String fileUri,
      String methodAnnotation,
      String projectAnnotation,
      int offset,
      List<String> classes) {
    TestFindParameters parameters =
        new TestFindParameters(fileUri, methodAnnotation, projectAnnotation, offset, classes);
    CompletableFuture<Object> result = executeCommand(commandId, singletonList(parameters));
    Type targetClassType = new TypeToken<ArrayList<String>>() {}.getType();
    try {
      return gson.fromJson(
          gson.toJson(result.get(FILE_STRUCTURE_REQUEST_TIMEOUT, TimeUnit.SECONDS)),
          targetClassType);
    } catch (JsonSyntaxException | InterruptedException | ExecutionException | TimeoutException e) {
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  private LanguageServer getLanguageServer() {
    return registry
        .findServer(server -> (server.getLauncher() instanceof JavaLanguageServerLauncher))
        .get()
        .getServer();
  }

  private CompletableFuture<Object> executeCommand(String commandId, List<Object> parameters) {
    ExecuteCommandParams params = new ExecuteCommandParams(commandId, parameters);
    return getLanguageServer().getWorkspaceService().executeCommand(params);
  }

  private void fixLocation(ExtendedSymbolInformation symbol) {
    LanguageServiceUtils.fixLocation(symbol.getInfo().getLocation());
    for (ExtendedSymbolInformation child : symbol.getChildren()) {
      fixLocation(child);
    }
  }
}
