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

import java.lang.reflect.Method;

import java.util.Collection;
import java.util.Objects;

import java.util.function.Supplier;

import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;

import jakarta.enterprise.inject.se.SeContainer;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public class ClientProxyInvocationInterceptor implements InvocationInterceptor {

  public static final Namespace NAMESPACE = Namespace.create((Object[])Instance.class.getPackage().getName().split("\\."));

  private final Supplier<? extends Instance<Object>> fallback;
  
  public ClientProxyInvocationInterceptor() {
    this(CDI::current);
  }

  public ClientProxyInvocationInterceptor(final Supplier<? extends Instance<Object>> fallback) {
    super();
    this.fallback = Objects.requireNonNull(fallback, "fallback");
  }

  @Override
  public final void interceptTestMethod(final InvocationInterceptor.Invocation<Void> invocation,
                                        final ReflectiveInvocationContext<Method> invocationContext,
                                        final ExtensionContext extensionContext)
    throws Throwable {
    Instance<Object> i;
    try {
      i = i(extensionContext);
    } catch (final IllegalStateException e) {
      i = null;
    }
    if (i != null) {
      final Class<?> c = invocationContext.getTargetClass();
      final Instance<?> i2 = i.select(c, qs(c, bm(i)));
      if (!i2.isUnsatisfied() && !i2.isAmbiguous()) {
        final Method m = invocationContext.getExecutable();
        if (m.trySetAccessible()) {
          m.invoke(i2.get(), invocationContext.getArguments().toArray(new Object[0]));
          invocation.skip();
          return;
        }
      }
    }
    invocation.proceed();
  }

  private static final BeanManager bm(final Instance<Object> i) {
    return
      i instanceof SeContainer ? ((SeContainer)i).getBeanManager() :
      i instanceof CDI ? ((CDI)i).getBeanManager() :
      i.select(BeanManager.class).get();
  }

  private static final <T> Annotation[] qs(final Class<?> c, final BeanManager bm) {
    final Collection<? extends Annotation> qualifiers = bm.createBeanAttributes(bm.createAnnotatedType(c)).getQualifiers();
    return qualifiers.isEmpty() ? new Annotation[] { Default.Literal.INSTANCE } : qualifiers.toArray(new Annotation[0]);
  }
  
  private final Instance<Object> i(final ExtensionContext extensionContext) {
    return i(extensionContext.getStore(NAMESPACE));
  }

  @SuppressWarnings("unchecked")
  private final Instance<Object> i(final Store store) {
    final Instance<Object> i = (Instance<Object>)store.get(Instance.class);
    return i == null ? this.fallback.get() : i;
  }

}
