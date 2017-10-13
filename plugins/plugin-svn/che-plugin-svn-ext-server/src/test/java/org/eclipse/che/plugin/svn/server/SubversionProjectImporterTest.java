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

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createTempDirectory;
import static java.util.Arrays.asList;
import static java.util.regex.Pattern.matches;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.multibindings.Multibinder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.che.api.core.model.workspace.config.SourceStorage;
import org.eclipse.che.api.core.util.LineConsumer;
import org.eclipse.che.api.fs.server.FsManager;
import org.eclipse.che.api.fs.server.PathTransformer;
import org.eclipse.che.api.project.server.ProjectImporter.SourceCategory;
import org.eclipse.che.api.project.server.type.ProjectTypeDef;
import org.eclipse.che.api.project.server.type.ValueProviderFactory;
import org.eclipse.che.api.user.server.spi.ProfileDao;
import org.eclipse.che.commons.lang.NameGenerator;
import org.eclipse.che.plugin.ssh.key.script.SshKeyProvider;
import org.eclipse.che.plugin.svn.server.repository.RepositoryUrlProvider;
import org.eclipse.che.plugin.svn.server.utils.TestUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SubversionProjectImporterTest {

  private static final String WS_PATH = "/ws/path";

  @Mock
  private Supplier<LineConsumer> lineConsumerSupplier;
  @Mock
  private LineConsumer lineConsumer;
  @Mock
  private ProfileDao userProfileDao;
  @Mock
  private RepositoryUrlProvider repositoryUrlProvider;
  @Mock
  private SourceStorage sourceStorage;
  @Mock
  private SshKeyProvider sshKeyProvider;
  @Mock
  private FsManager fsManager;
  @Mock
  private PathTransformer pathTransformer;
  private File repoRoot;
  private File root;
  private SubversionProjectImporter projectImporter;

  @Before
  public void setUp() throws Exception {
    when(lineConsumerSupplier.get()).thenReturn(lineConsumer);
    // Bind components
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                Multibinder.newSetBinder(
                    binder(), org.eclipse.che.api.project.server.ProjectImporter.class)
                    .addBinding()
                    .to(SubversionProjectImporter.class);
                Multibinder.newSetBinder(binder(), ProjectTypeDef.class)
                    .addBinding()
                    .to(SubversionProjectType.class);
                Multibinder.newSetBinder(binder(), ValueProviderFactory.class)
                    .addBinding()
                    .to(SubversionValueProviderFactory.class);

                bind(SshKeyProvider.class).toInstance(sshKeyProvider);
                bind(FsManager.class).toInstance(fsManager);
                bind(PathTransformer.class).toInstance(pathTransformer);
                bind(ProfileDao.class).toInstance(userProfileDao);
                bind(RepositoryUrlProvider.class).toInstance(repositoryUrlProvider);
              }
            });

    // Init virtual file system
    root = createTempDirectory(SubversionProjectImporterTest.class.getSimpleName()).toFile();

    // Create the test user
    TestUtils.createTestUser(userProfileDao);

    // Create the Subversion repository
    repoRoot = TestUtils.createGreekTreeRepository();

    projectImporter = injector.getInstance(SubversionProjectImporter.class);
  }

  /**
   * Test for {@link SubversionProjectImporter#getSourceCategory()}.
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testGetCategory() throws Exception {
    assertEquals(projectImporter.getSourceCategory(), SourceCategory.VCS);
  }

  /**
   * Test for {@link SubversionProjectImporter#getDescription()}.
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testGetDescription() throws Exception {
    assertEquals(
        projectImporter.getDescription(), "Import project from Subversion repository URL.");
  }

  /**
   * Test for {@link SubversionProjectImporter#getId()}
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testGetId() throws Exception {
    assertEquals(projectImporter.getId(), "subversion");
  }

  /**
   * Test for {@link SubversionProjectImporter#isInternal()}.
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testIsInternal() throws Exception {
    assertEquals(projectImporter.isInternal(), false);
  }

  /**
   * Test for {@link SubversionProjectImporter#doImport(SourceStorage, String, Supplier)} invalid
   * url.
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testInvalidImportSources() throws Exception {
    final String projectName = NameGenerator.generate("project-", 3);
    Path projectFsPath = createDirectories(root.toPath().resolve(projectName));

    when(pathTransformer.transform(projectFsPath)).thenReturn(WS_PATH);
    when(pathTransformer.transform(WS_PATH)).thenReturn(projectFsPath);
    when(fsManager.existsAsDir(WS_PATH)).thenReturn(true);

    try {
      String fakeUrl = Paths.get(repoRoot.getAbsolutePath()).toUri() + "fake";
      when(sourceStorage.getLocation()).thenReturn(fakeUrl);
      projectImporter.doImport(sourceStorage, WS_PATH, lineConsumerSupplier);

      fail("The code above should had failed");
    } catch (SubversionException e) {
      final String message = e.getMessage();
      List<String> lines = asList(e.getMessage().split("\n"));

      boolean assertBoolean = false;
      for (String line : lines) {
        if (matches("svn: (E[0-9]{6}: )?URL 'file://.*/fake' doesn't exist\n?", line.trim())) {
          assertBoolean = true;
        }
      }

      assertTrue(message, assertBoolean);
    }
  }

  /**
   * Test for {@link SubversionProjectImporter#doImport(SourceStorage, String, Supplier)} with a
   * valid url.
   *
   * @throws Exception if anything goes wrong
   */
  @Test
  public void testValidImportSources() throws Exception {
    final String projectName = NameGenerator.generate("project-", 3);
    Path projectFsPath = createDirectories(root.toPath().resolve(projectName));

    when(pathTransformer.transform(projectFsPath)).thenReturn(WS_PATH);
    when(pathTransformer.transform(WS_PATH)).thenReturn(projectFsPath);
    when(fsManager.existsAsDir(WS_PATH)).thenReturn(true);

    String repoUrl = Paths.get(repoRoot.getAbsolutePath()).toUri().toString();
    when(sourceStorage.getLocation()).thenReturn(repoUrl);
    projectImporter.doImport(sourceStorage, WS_PATH, lineConsumerSupplier);

    assertTrue(Files.exists(projectFsPath.resolve(".svn")));
    assertTrue(Files.exists(projectFsPath.resolve("trunk")));
    assertTrue(Files.exists(projectFsPath.resolve("trunk").resolve("A")));
    assertTrue(Files.exists(projectFsPath.resolve("trunk").resolve("A").resolve("mu")));
  }
}
