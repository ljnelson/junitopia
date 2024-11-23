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

import java.lang.annotation.Annotation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import java.util.function.Supplier;

import jakarta.enterprise.context.spi.AlterableContext;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;

public class TestContext implements AlterableContext {

  private final Store store;

  public TestContext(final Store store) {
    super();
    this.store = Objects.requireNonNull(store, "store");
  }

  private final Store store() {
    return this.store;
  }
  
  @Override // AlterableContext
  public final void destroy(final Contextual<?> c) {
    this.destroy0(c);
  }

  private final <T> void destroy0(final Contextual<T> c) {
    @SuppressWarnings("unchecked")
    final CI<T> ci = (CI<T>)this.store().remove(c);
    try {
      c.destroy(ci.i, ci.cc);
    } finally {
      ci.cc.release();
    }
  }

  @Override // AlterableContext (Context)
  public final <T> T get(final Contextual<T> c) {
    return this.get(c, null);
  }

  @Override // AlterableContext (Context)
  @SuppressWarnings("unchecked")
  public final <T> T get(final Contextual<T> c, final CreationalContext<T> cc) {
    if (cc == null) {
      final CI<T> ci = (CI<T>)this.store().get(c);
      return ci == null ? null : ci.i;
    }
    return ((CI<T>)this.store().getOrComputeIfAbsent(c, bean -> new CI<>(bean.create(cc), cc))).i;
  }

  @Override // AlterableContext (Context)
  public final Class<? extends Annotation> getScope() {
    return TestScoped.class;
  }

  @Override // AlterableContext (Context)
  public final boolean isActive() {
    return true;
  }

  private static final class CI<T> {

    private final T i;

    private final CreationalContext<T> cc;
    
    private CI(final T i, final CreationalContext<T> cc) {
      super();
      this.i = i;
      this.cc = cc;
    }

    @Override
    public int hashCode() {
      int hashCode = 17;
      int c = this.i == null ? 0 : this.i.hashCode();
      hashCode = 37 * hashCode + c;
      c = this.cc == null ? 0 : this.cc.hashCode();
      hashCode = 37 * hashCode + c;
      return hashCode;
    }

    @Override
    public boolean equals(final Object other) {
      if (other == this) {
        return true;
      } else if (other != null && other.getClass() == this.getClass()) {
        final CI<?> her = (CI<?>)other;
        return Objects.equals(this.i, her.i) &&
          Objects.equals(this.cc, her.cc);
      } else {
        return false;
      }
    }
  }

}
