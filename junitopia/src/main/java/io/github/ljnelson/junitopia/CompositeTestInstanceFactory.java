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
package io.github.ljnelson.junitopia;

import java.lang.annotation.Annotation;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;

public class CompositeTestInstanceFactory implements TestInstanceFactory {

  private final Set<TestInstanceFactory> delegates;
  
  public CompositeTestInstanceFactory(final TestInstanceFactory... delegates)
    throws IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {
    super();
    final LinkedHashSet<TestInstanceFactory> ds = new LinkedHashSet<>();
    if (delegates != null && delegates.length > 0) {
      ds.addAll(Arrays.asList(delegates));
    }
    processAnnotations(this.getClass(), ds);
    this.delegates = Set.copyOf(ds);
  }

  @Override // TestInstanceFactory
  public final Object createTestInstance(final TestInstanceFactoryContext factoryContext,
                                         final ExtensionContext extensionContext) {
    TestInstantiationException t = null;
    for (final TestInstanceFactory delegate : this.delegates) {
      try {
        return delegate.createTestInstance(factoryContext, extensionContext);
      } catch (final TestInstantiationException e) {
        if (t == null) {
          t = e;
        } else {
          t.addSuppressed(e);
        }
      }
    }
    throw t;
  }

  private static final void processAnnotations(final AnnotatedElement ae, final LinkedHashSet<TestInstanceFactory> delegates)
    throws IllegalAccessException, InstantiationException, InvocationTargetException, NoSuchMethodException {
    for (final ExtendWith ew : ae.getAnnotationsByType(ExtendWith.class)) {
      for (final Class<? extends Extension> c : ew.value()) {
        if (TestInstanceFactory.class.isAssignableFrom(c)) {
          delegates.add((TestInstanceFactory)c.getDeclaredConstructor().newInstance());
          processAnnotations(c, delegates);
        }
      }
    }
  }
  
}
