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
package org.eclipse.che.plugin.svn.server;

import static org.eclipse.che.dto.server.DtoFactory.newDto;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.function.Supplier;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.UnauthorizedException;
import org.eclipse.che.api.core.model.workspace.config.SourceStorage;
import org.eclipse.che.api.core.util.LineConsumer;
import org.eclipse.che.api.fs.server.FsManager;
import org.eclipse.che.api.project.server.ProjectImporter;
import org.eclipse.che.plugin.svn.shared.CheckoutRequest;

/** Implementation of {@link ProjectImporter} for Subversion. */
@Singleton
public class SubversionProjectImporter implements ProjectImporter {

  public static final String ID = "subversion";

  private final SubversionApi subversionApi;
  private final FsManager fsManager;

  @Inject
  public SubversionProjectImporter(final SubversionApi subversionApi, FsManager fsManager) {
    this.subversionApi = subversionApi;
    this.fsManager = fsManager;
  }

  @Override
  public String getId() {
    return ID;
  }

  @Override
  public boolean isInternal() {
    return false;
  }

  @Override
  public String getDescription() {
    return "Import project from Subversion repository URL.";
  }

  @Override
  public void doImport(SourceStorage src, String dst)
      throws ForbiddenException, ConflictException, UnauthorizedException, IOException,
          ServerException, NotFoundException {
    doImport(src, dst, null);
  }

  @Override
  public void doImport(SourceStorage src, String dst, Supplier<LineConsumer> supplier)
      throws ForbiddenException, ConflictException, UnauthorizedException, IOException,
          ServerException, NotFoundException {
    if (supplier == null) {
      supplier = () -> LineConsumer.DEV_NULL;
    }

    if (!fsManager.isDirectory(dst)) {
      throw new IOException("Project cannot be imported into \"" + dst + "\". It is not a folder.");
    }

    this.subversionApi.setOutputLineConsumerFactory(supplier::get);
    subversionApi.checkout(
        newDto(CheckoutRequest.class)
            // TODO wtf?
            .withProjectPath("/projects" + dst)
            .withUrl(src.getLocation())
            .withUsername(src.getParameters().remove("username"))
            .withPassword(src.getParameters().remove("password")));
  }

  @Override
  public SourceCategory getSourceCategory() {
    return SourceCategory.VCS;
  }
}
