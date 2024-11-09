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

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import java.lang.reflect.Method;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;

import jakarta.enterprise.event.Observes;

import jakarta.enterprise.inject.Instance;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;

import jakarta.enterprise.util.AnnotationLiteral;

import jakarta.inject.Qualifier;

import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;

import static java.lang.annotation.ElementType.PARAMETER;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static org.junit.platform.commons.support.ReflectionSupport.newInstance;

public final class CdiSeContainerStarter implements BeforeTestExecutionCallback, TestInstanceFactory {

  public static final Namespace NAMESPACE = Namespace.create((Object[])Instance.class.getPackage().getName().split("\\."));

  private final Supplier<? extends SeContainerInitializer> s;

  public CdiSeContainerStarter() {
    this(SeContainerInitializer::newInstance);
  }

  public CdiSeContainerStarter(final Supplier<? extends SeContainerInitializer> s) {
    super();
    this.s = Objects.requireNonNull(s, "s");
  }

  @Override // TestInstanceFactory
  public final Object createTestInstance(final TestInstanceFactoryContext factoryContext,
                                         final ExtensionContext extensionContext) {
    return newInstance(factoryContext.getTestClass());
  }

  @Override // BeforeTestExecutionCallback
  public final void beforeTestExecution(final ExtensionContext extensionContext) throws Exception {
    final Store store = this.findStore(extensionContext);
    store.getOrComputeIfAbsent("SeContainerCloser", n -> (CloseableResource)() -> {
        @SuppressWarnings("unchecked")
        final Instance<Object> i = (Instance<Object>)store.get(Instance.class);
        if (i instanceof SeContainer) {
          ((SeContainer)i).close();
        }
      });
    if (store.get(Instance.class) == null) {
      final Class<?> testClass = extensionContext.getRequiredTestClass();
      final SeContainerInitializer sci = this.s.get()
      .disableDiscovery()
      .addBeanClasses(testClass)
      .addExtensions(new Extension() {
          private final void afterBeanDiscovery(@Observes final AfterBeanDiscovery event,
                                                final BeanManager bm) {
            event.addBean()
              .addTransitiveTypeClosure(extensionContext.getClass())
              .scope(Dependent.class)
              .createWith(cc -> extensionContext);
            event.addBean()
              .types(TestInfo.class, Object.class)
              .scope(Dependent.class)
              .produceWith(i -> {
                  final ExtensionContext ec = i.select(ExtensionContext.class).get();
                  return new TestInfo() {
                    @Override
                    public final String getDisplayName() {
                      return ec.getDisplayName();
                    }
                    @Override
                    public final Set<String> getTags() {
                      return ec.getTags();
                    }
                    @Override
                    public final Optional<Class<?>> getTestClass() {
                      return ec.getTestClass();
                    }
                    @Override
                    public final Optional<Method> getTestMethod() {
                      return ec.getTestMethod();
                    }
                  };
                });
            event.<TestReporter>addBean()
              .types(TestReporter.class, Object.class)
              .scope(Dependent.class)
              .produceWith(i -> i.select(ExtensionContext.class).get()::publishReportEntry);
            event.addBean()
              .read(bm.createBeanAttributes(event.getAnnotatedType(testClass, null)))
              .scope(Dependent.class)
              .addQualifier(Original.Literal.INSTANCE)
              .createWith(cc -> extensionContext.getRequiredTestInstance());
          }
          private final <T> void forceTestClassScopeToApplicationScoped(@Observes final ProcessBeanAttributes<T> event) {
            final Object a = event.getAnnotated();
            if (a instanceof AnnotatedType && ((AnnotatedType<?>)a).getJavaClass() == testClass) {
              // If we were being very picky we'd also check for the absence of @Original on the class, but as of
              // this writing @Original cannot be placed declaratively on anything other than a parameter, so we
              // can skip that step here.
              event.configureBeanAttributes()
                .scope(ApplicationScoped.class);
            }
          }
        });
      store.getOrComputeIfAbsent(Instance.class, c -> sci.initialize());
    }
  }

  private final Store findStore(ExtensionContext ec) {
    switch (ec.getTestInstanceLifecycle().orElseThrow()) {
    case PER_METHOD:
      // We're looking at the method-level ec TODO: we could see if there's configuration to say look higher somehow,
      // or to say no, I really want a container at the method level
      ec = ec.getParent().orElse(ec);
      break;
    case PER_CLASS:
      // We're looking at the class-level ec already
      break;
    }
    return ec.getStore(NAMESPACE);
  }

  @Qualifier
  @Retention(RUNTIME)
  @Target(PARAMETER)
  public static @interface Original {

    public static final class Literal extends AnnotationLiteral<Original> implements Original {

      private static final long serialVersionUID = 1L;

      public static final Literal INSTANCE = new Literal();

      private Literal() {
        super();
      }

    }

  }

}
