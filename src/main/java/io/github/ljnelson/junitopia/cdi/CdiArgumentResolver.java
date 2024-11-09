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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import java.util.function.Supplier;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;

import jakarta.enterprise.inject.se.SeContainer;

import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.InjectionPoint;

import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

public final class CdiArgumentResolver implements ParameterResolver {

  public static final Namespace NAMESPACE = Namespace.create((Object[])Instance.class.getPackage().getName().split("\\."));

  private static final Set<Class<?>> SPECIAL_CLASSES = Set.of(RepetitionInfo.class, TestInfo.class, TestReporter.class);
  
  private final Supplier<? extends Instance<Object>> fallback;

  public CdiArgumentResolver() {
    this(CDI::current);
  }

  public CdiArgumentResolver(final Supplier<? extends Instance<Object>> fallback) {
    super();
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  @Override
  public final boolean supportsParameter(final ParameterContext parameterContext,
                                         final ExtensionContext extensionContext) {
    final Parameter p = parameterContext.getParameter();
    if (SPECIAL_CLASSES.contains(p.getType())) {
      return false; // Let JUnit handle them natively
    }
    BeanManager bm;
    try {
      bm = bm(extensionContext);
    } catch (final IllegalStateException e) {
      bm = null;
    }
    return
      bm != null &&
      bm.resolve(bm.getBeans(p.getParameterizedType(),
                             qs(p.getDeclaringExecutable(),
                                parameterContext.getIndex(),
                                bm))) != null;
  }

  @Override
  public final Object resolveParameter(final ParameterContext parameterContext,
                                       final ExtensionContext extensionContext) {
    final BeanManager bm = bm(extensionContext);
    return
      bm.getInjectableReference(ip(parameterContext.getParameter().getDeclaringExecutable(),
                                   parameterContext.getIndex(),
                                   bm),
                                bm.createCreationalContext(null));
  }

  private final BeanManager bm(final ExtensionContext extensionContext) {
    final Instance<Object> i = i(extensionContext);
    return i == null ? null : bm(i);
  }

  private final Instance<Object> i(final ExtensionContext extensionContext) {
    return i(extensionContext.getStore(NAMESPACE));
  }

  @SuppressWarnings("unchecked")
  private final Instance<Object> i(final Store store) {
    final Instance<Object> i = (Instance<Object>)store.get(Instance.class);
    return i == null ? this.fallback.get() : i;
  }


  /*
   * Static methods.
   */


  private static final BeanManager bm(final Instance<Object> i) {
    return
      i instanceof SeContainer ? ((SeContainer)i).getBeanManager() :
      i instanceof CDI ? ((CDI)i).getBeanManager() :
      i.select(BeanManager.class).get();
  }

  private static final InjectionPoint ip(final Executable e, final int index, final BeanManager bm) {
    final AnnotatedType<?> t = bm.createAnnotatedType(e.getDeclaringClass());
    return (e instanceof Constructor<?> ? t.getConstructors() : t.getMethods()).stream()
      .filter(ac -> ac.getJavaMember().equals(e))
      .findAny()
      .map(ac -> bm.createInjectionPoint(ac.getParameters().get(index)))
      .orElseThrow();
  }

  private static final <T> Annotation[] qs(final Executable e, final int index, final BeanManager bm) {
    final Collection<? extends Annotation> qualifiers = ip(e, index, bm).getQualifiers();
    return qualifiers.isEmpty() ? new Annotation[] { Default.Literal.INSTANCE } : qualifiers.toArray(Annotation[]::new);
  }

}
