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
package org.eclipse.che.api.languageserver.service;

import static org.eclipse.che.api.languageserver.service.LanguageServiceUtils.prefixURI;
import static org.eclipse.che.api.languageserver.service.LanguageServiceUtils.removePrefixUri;
import static org.eclipse.che.api.languageserver.service.LanguageServiceUtils.truish;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.jsonrpc.commons.JsonRpcException;
import org.eclipse.che.api.core.jsonrpc.commons.RequestHandlerConfigurator;
import org.eclipse.che.api.languageserver.exception.LanguageServerException;
import org.eclipse.che.api.languageserver.registry.InitializedLanguageServer;
import org.eclipse.che.api.languageserver.registry.LanguageServerRegistry;
import org.eclipse.che.api.languageserver.registry.LanguageServerRegistryImpl;
import org.eclipse.che.api.languageserver.server.dto.DtoServerImpls.SymbolInformationDto;
import org.eclipse.che.api.languageserver.server.dto.DtoServerImpls.TextEditDto;
import org.eclipse.che.api.languageserver.shared.model.ExtendedWorkspaceSymbolParams;
import org.eclipse.che.api.languageserver.shared.model.FileEditParams;
import org.eclipse.che.api.languageserver.shared.util.CharStreamEditor;
import org.eclipse.che.api.languageserver.util.LSOperation;
import org.eclipse.che.api.languageserver.util.OperationUtil;
import org.eclipse.che.api.project.server.ProjectManager;
import org.eclipse.che.api.project.server.VirtualFileEntry;
import org.eclipse.che.api.vfs.VirtualFile;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API for the workspace/* services defined in
 * https://github.com/Microsoft/vscode-languageserver-protocol Dispatches onto the {@link
 * LanguageServerRegistryImpl}.
 *
 * @author Evgen Vidolob
 */
@Singleton
public class WorkspaceService {
  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceService.class);
  private LanguageServerRegistry registry;
  private ProjectManager projectManager;
  private RequestHandlerConfigurator requestHandler;

  @Inject
  public WorkspaceService(
      LanguageServerRegistry registry,
      ProjectManager projectManager,
      RequestHandlerConfigurator requestHandler) {
    this.registry = registry;
    this.projectManager = projectManager;
    this.requestHandler = requestHandler;
  }

  @PostConstruct
  public void configureMethods() {
    requestHandler
        .newConfiguration()
        .methodName("workspace/symbol")
        .paramsAsDto(ExtendedWorkspaceSymbolParams.class)
        .resultAsListOfDto(SymbolInformationDto.class)
        .withFunction(this::symbol);
    requestHandler
        .newConfiguration()
        .methodName("workspace/editFile")
        .paramsAsDto(FileEditParams.class)
        .resultAsListOfDto(TextEditDto.class)
        .withFunction(this::editFile);
  }

  /**
   * Apply a list of text edits to a workspace file
   *
   * @param params the edit to be effected
   * @return a list of text edits that will undo the effected change
   */
  @SuppressWarnings("deprecation")
  private List<TextEditDto> editFile(FileEditParams params) {
    try {
      VirtualFileEntry child =
          projectManager
              .getProjectsRoot()
              .getChild(LanguageServiceUtils.removePrefixUri(params.getUri()));
      if (child != null) {
        VirtualFile vf = child.getVirtualFile();
        List<TextEdit> undo = new ArrayList<>();
        vf.modifyContent(
            new BiConsumer<InputStream, OutputStream>() {

              @Override
              public void accept(InputStream in, OutputStream out) {
                OutputStreamWriter w = new OutputStreamWriter(out);
                undo.addAll(
                    new CharStreamEditor(
                            params.getEdits(),
                            CharStreamEditor.forReader(new InputStreamReader(in)),
                            CharStreamEditor.forWriter(w))
                        .transform());
                try {
                  w.flush();
                } catch (IOException e) {
                  throw new RuntimeException("failed to write tranformed file", e);
                }
              }
            });
        return undo.stream().map(e -> new TextEditDto(e)).collect(Collectors.toList());
      } else {
        LOG.error("did not find file " + params.getUri());
        throw new JsonRpcException(-27000, "File not found for edit: " + params.getUri());
      }
    } catch (ServerException | ForbiddenException e) {
      LOG.error("error editing file", e);
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }

  private List<SymbolInformationDto> symbol(ExtendedWorkspaceSymbolParams workspaceSymbolParams) {
    List<SymbolInformationDto> result = new ArrayList<>();
    List<InitializedLanguageServer> servers;
    try {
      servers =
          registry
              .getApplicableLanguageServers(prefixURI(workspaceSymbolParams.getFileUri()))
              .stream()
              .flatMap(Collection::stream)
              .collect(Collectors.toList());
      OperationUtil.doInParallel(
          servers,
          new LSOperation<InitializedLanguageServer, List<? extends SymbolInformation>>() {

            @Override
            public boolean canDo(InitializedLanguageServer element) {
              return truish(
                  element.getInitializeResult().getCapabilities().getWorkspaceSymbolProvider());
            }

            @Override
            public CompletableFuture<List<? extends SymbolInformation>> start(
                InitializedLanguageServer element) {
              return element.getServer().getWorkspaceService().symbol(workspaceSymbolParams);
            }

            @Override
            public boolean handleResult(
                InitializedLanguageServer element, List<? extends SymbolInformation> locations) {
              locations.forEach(
                  o -> {
                    o.getLocation().setUri(removePrefixUri(o.getLocation().getUri()));
                    result.add(new SymbolInformationDto(o));
                  });
              return true;
            }
          },
          10000);
      return result;
    } catch (LanguageServerException e) {
      LOG.error("error getting symbol", e);
      throw new JsonRpcException(-27000, e.getMessage());
    }
  }
}
