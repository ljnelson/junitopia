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

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;

import jakarta.enterprise.event.Observes;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;

import jakarta.enterprise.util.AnnotationLiteral;

import jakarta.inject.Inject;
import jakarta.inject.Qualifier;

import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;

import static java.lang.System.getLogger;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

import static java.lang.annotation.ElementType.PARAMETER;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static org.junit.platform.commons.support.ReflectionSupport.newInstance;

/**
 * A {@link TestInstanceFactory} and a {@link BeforeTestExecutionCallback} that arranges for an {@link SeContainer} to
 * be {@linkplain SeContainerInitializer#initialize() created}, and for the current {@linkplain
 * ExtensionContext#getRequiredTestClass() test class} to be added to it as a CDI bean, permitting dependency injection
 * of the resulting test instance.
 *
 * <p>A notable feature of this extension is that it will function properly regardless of {@linkplain
 * ExtensionContext#getTestInstanceLifecycle() test instance lifecycle}, and regardless of the presence or absence of
 * other JUnit extensions.</p>
 *
 * @author <a href="https://about.me/lairdnelson" target="_top">Laird Nelson</a>
 */
public final class CdiSeContainerStarter implements BeforeTestExecutionCallback, TestInstanceFactory {

  private static final Logger LOGGER = getLogger(CdiSeContainerStarter.class.getName());

  private final Supplier<? extends SeContainerInitializer> s;

  /**
   * Creates a new {@link CdiSeContainerStarter}.
   */
  public CdiSeContainerStarter() {
    this(SeContainerInitializer::newInstance);
  }

  /**
   * Creates a new {@link CdiSeContainerStarter}.
   *
   * @param s a {@link Supplier} of a {@link SeContainerInitializer} instance; must not be {@code null}
   *
   * @exception NullPointerException if {@code s} is {@code null}
   */
  public CdiSeContainerStarter(final Supplier<? extends SeContainerInitializer> s) {
    super();
    this.s = Objects.requireNonNull(s, "s");
  }

  /**
   * Creates a new {@linkplain ExtensionContext#getRequiredTestInstance() <em>test instance</em>} and returns it.
   *
   * @param factoryContext a {@link TestInstanceFactoryContext}; must not be {@code null}
   *
   * @param extensionContext an {@link ExtensionContext}; must not be {@code null}
   *
   * @return a new test instance; never {@code null}
   *
   * @exception NullPointerException if either {@code factoryContext} or {@code extensionContext} is {@code null}
   */
  @Override // TestInstanceFactory
  public final Object createTestInstance(final TestInstanceFactoryContext factoryContext,
                                         final ExtensionContext extensionContext) {
    final Class<?> testClass = factoryContext.getTestClass();

    // First see if there's a CDI installation already set up. If so, ask it to get a contextual reference. This will
    // use the @Inject-annotated constructor if one exists. If one does not, that's a problem, so let it fail.
    @SuppressWarnings("unchecked")
    final Instance<Object> i =
      (Instance<Object>)extensionContext.getStore(AbstractCdiExtension.NAMESPACE).get(Instance.class);
    if (i != null) {
      try {
        return i.select(testClass, AbstractCdiExtension.qs(testClass, AbstractCdiExtension.bm(i))).get();
      } catch (final IllegalStateException | UnsatisfiedResolutionException e) {
        if (LOGGER.isLoggable(DEBUG)) {
          LOGGER.log(DEBUG, e.getMessage(), e);
        }
      }
    }

    // Otherwise proceed through the constructors, ordered from most number of parameters to least, and invoke each one,
    // stopping once a constructor returns successfully. JUnit's native argument resolution will be used.
    final List<Constructor<?>> cs = Arrays.asList(testClass.getDeclaredConstructors());
    Collections.sort(cs, Comparator.<Constructor<?>>comparingInt(Constructor::getParameterCount).reversed());
    TestInstantiationException t = null;
    for (final Constructor<?> c : cs) {
      try {
        return extensionContext.getExecutableInvoker().invoke(c, factoryContext.getOuterInstance().orElse(null));
      } catch (final ParameterResolutionException e) {
        if (t == null) {
          t = new TestInstantiationException(e.getMessage(), e);
        } else {
          t.addSuppressed(e);
        }
      }
    }
    throw t;
  }

  @Override // BeforeTestExecutionCallback
  public final void beforeTestExecution(final ExtensionContext extensionContext) throws Exception {
    final Store store = this.findStore(extensionContext);
    if (store.get(Instance.class) != null) {
      return;
    }
    store.getOrComputeIfAbsent("SeContainerCloser", n -> (CloseableResource)() -> {
        @SuppressWarnings("unchecked")
          final Instance<Object> i = (Instance<Object>)store.get(Instance.class);
        if (i instanceof SeContainer) {
          ((SeContainer)i).close();
        }
      });
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

  private final Store findStore(ExtensionContext ec) {
    switch (ec.getTestInstanceLifecycle().orElseThrow()) {
    case PER_METHOD:
      final String containerLifecycleString =
        ec.getConfigurationParameter(SeContainer.class.getName() + ".lifecycle").orElse("per_class");
      if ("per_class".equalsIgnoreCase(containerLifecycleString)) {
        ec = ec.getParent().orElseThrow();
      }
      break;
    }
    return ec.getStore(AbstractCdiExtension.NAMESPACE);
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
