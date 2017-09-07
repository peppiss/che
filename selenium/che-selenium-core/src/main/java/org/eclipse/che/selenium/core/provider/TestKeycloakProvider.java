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
package org.eclipse.che.selenium.core.provider;

import com.google.inject.Provider;
import org.keycloak.admin.client.Keycloak;

/**
 * Keycloak admin client.
 *
 * @author Dmytro Nochevnov
 */
public interface TestKeycloakProvider extends Provider<Keycloak> {}