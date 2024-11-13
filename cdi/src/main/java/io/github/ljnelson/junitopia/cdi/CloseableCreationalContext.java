/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2024 Laird Nelson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ljnelson.junitopia.cdi;

import java.lang.System.Logger;

import jakarta.enterprise.context.spi.CreationalContext;

import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

import static java.lang.System.getLogger;

import static java.lang.System.Logger.Level.TRACE;

final class CloseableCreationalContext<T> implements CreationalContext<T>, AutoCloseable, CloseableResource {

  private static final Logger LOGGER = getLogger(CloseableCreationalContext.class.getName());
  
  private final CreationalContext<T> cc;
  
  CloseableCreationalContext(final CreationalContext<T> cc) {
    super();
    this.cc = cc;
  }

  @Override // AutoCloseable, CloseableResource
  public final void close() {
    if (LOGGER.isLoggable(TRACE)) {
      LOGGER.log(TRACE, "Closing");
    }
    this.release();
  }

  @Override // CreationalContext<T>
  public final void push(final T t) {
    if (this.cc != null) {
      this.cc.push(t);
    }
  }

  @Override // CreationalContext<T>
  public final void release() {
    if (LOGGER.isLoggable(TRACE)) {
      LOGGER.log(TRACE, "Releasing");
    }
    if (this.cc != null) {
      this.cc.release();
    }
  }
  
}
