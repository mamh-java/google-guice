/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.internal;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.inject.Asserts.assertContains;
import static com.google.inject.internal.SpiUtils.assertOptionalVisitor;
import static com.google.inject.internal.SpiUtils.instance;
import static com.google.inject.internal.SpiUtils.linked;
import static com.google.inject.internal.SpiUtils.providerInstance;
import static com.google.inject.internal.SpiUtils.providerKey;
import static com.google.inject.name.Names.named;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.AbstractModule;
import com.google.inject.Asserts;
import com.google.inject.Binding;
import com.google.inject.BindingAnnotation;
import com.google.inject.CreationException;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.internal.SpiUtils.VisitType;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.spi.Dependency;
import com.google.inject.spi.Elements;
import com.google.inject.spi.HasDependencies;
import com.google.inject.spi.InstanceBinding;
import com.google.inject.util.Modules;
import com.google.inject.util.Providers;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import junit.framework.TestCase;

/** @author sameb@google.com (Sam Berlin) */
public class OptionalBinderTest extends TestCase {

  final Key<String> stringKey = Key.get(String.class);
  final TypeLiteral<Optional<String>> optionalOfString = new TypeLiteral<Optional<String>>() {};
  final TypeLiteral<java.util.Optional<String>> javaOptionalOfString =
      new TypeLiteral<java.util.Optional<String>>() {};
  final TypeLiteral<Optional<Provider<String>>> optionalOfProviderString =
      new TypeLiteral<Optional<Provider<String>>>() {};
  final TypeLiteral<java.util.Optional<Provider<String>>> javaOptionalOfProviderString =
      new TypeLiteral<java.util.Optional<Provider<String>>>() {};
  final TypeLiteral<Optional<jakarta.inject.Provider<String>>> optionalOfJakartaProviderString =
      new TypeLiteral<Optional<jakarta.inject.Provider<String>>>() {};
  final TypeLiteral<java.util.Optional<jakarta.inject.Provider<String>>>
      javaOptionalOfJakartaProviderString =
          new TypeLiteral<java.util.Optional<jakarta.inject.Provider<String>>>() {};

  final Key<Integer> intKey = Key.get(Integer.class);
  final TypeLiteral<Optional<Integer>> optionalOfInteger = new TypeLiteral<Optional<Integer>>() {};
  final TypeLiteral<java.util.Optional<Integer>> javaOptionalOfInteger =
      new TypeLiteral<java.util.Optional<Integer>>() {};
  final TypeLiteral<Optional<Provider<Integer>>> optionalOfProviderInteger =
      new TypeLiteral<Optional<Provider<Integer>>>() {};
  final TypeLiteral<java.util.Optional<Provider<Integer>>> javaOptionalOfProviderInteger =
      new TypeLiteral<java.util.Optional<Provider<Integer>>>() {};
  final TypeLiteral<Optional<jakarta.inject.Provider<Integer>>> optionalOfJakartaProviderInteger =
      new TypeLiteral<Optional<jakarta.inject.Provider<Integer>>>() {};
  final TypeLiteral<java.util.Optional<jakarta.inject.Provider<Integer>>>
      javaOptionalOfJakartaProviderInteger =
          new TypeLiteral<java.util.Optional<jakarta.inject.Provider<Integer>>>() {};

  final TypeLiteral<List<String>> listOfStrings = new TypeLiteral<List<String>>() {};

  public void testTypeNotBoundByDefault() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class);
            requireBinding(new Key<Optional<String>>() {}); // the above specifies this.
            requireBinding(String.class); // but it doesn't specify this.
            binder().requireExplicitBindings(); // need to do this, otherwise String will JIT
            requireBinding(Key.get(javaOptionalOfString));
          }
        };

    try {
      Guice.createInjector(module);
      fail();
    } catch (CreationException ce) {
      assertContains(
          ce.getMessage(), "Explicit bindings are required and String is not explicitly bound.");
      assertEquals(1, ce.getErrorMessages().size());
    }
  }

  public void testLinkedTypeSameAsBaseType() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), MyClass.class)
                .setBinding()
                .to(MyClass.class);
          }
        };

    CreationException ce =
        assertThrows(CreationException.class, () -> Guice.createInjector(module));
    assertContains(ce.getMessage(), "Binding points to itself. Key: OptionalBinderTest$MyClass");
  }

  public void testLinkedAndBaseTypeHaveDifferentAnnotations() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), Key.get(MyClass.class, Names.named("foo")))
                .setBinding()
                .to(Key.get(MyClass.class, Names.named("moo")));
          }

          @Provides
          @Named("moo")
          MyClass provideString() {
            return new MyClass();
          }
        };
    Injector injector = Guice.createInjector(module);
    assertNotNull(injector);
  }

  enum ExpectedValueState {
    PRESENT,
    EMPTY
  }

  enum ExpectedProviderState {
    PRESENT,
    EMPTY
  }

  private static <T> Key<T> keyFor(TypeLiteral<T> typeLiteral, Annotation maybeAnnotation) {
    if (maybeAnnotation != null) {
      return Key.get(typeLiteral, maybeAnnotation);
    } else {
      return Key.get(typeLiteral);
    }
  }

  private void assertOptionalState(
      Injector injector,
      ExpectedValueState valueState,
      ExpectedProviderState providerState,
      String expectedValueIfPresent) {
    assertOptionalState(
        injector, /* annotation= */ null, valueState, providerState, expectedValueIfPresent);
  }

  private void assertOptionalState(
      Injector injector,
      Annotation annotation,
      ExpectedValueState valueState,
      ExpectedProviderState providerState,
      String expectedValueIfPresent) {
    boolean expectedPresent = valueState == ExpectedValueState.PRESENT;
    Optional<String> optional = injector.getInstance(keyFor(optionalOfString, annotation));
    assertThat(optional.isPresent()).isEqualTo(expectedPresent);
    optional =
        Optional.fromJavaUtil(injector.getInstance(keyFor(javaOptionalOfString, annotation)));
    assertThat(optional.isPresent()).isEqualTo(expectedPresent);
    if (expectedPresent) {
      assertThat(optional.get()).isEqualTo(expectedValueIfPresent);
    }

    expectedPresent = providerState == ExpectedProviderState.PRESENT;
    Optional<Provider<String>> optionalP =
        injector.getInstance(keyFor(optionalOfProviderString, annotation));
    assertThat(optionalP.isPresent()).isEqualTo(expectedPresent);
    if (expectedPresent) {
      assertThat(optionalP.get().get()).isEqualTo(expectedValueIfPresent);
    }
    optionalP =
        Optional.fromJavaUtil(
            injector.getInstance(keyFor(javaOptionalOfProviderString, annotation)));
    assertThat(optionalP.isPresent()).isEqualTo(expectedPresent);
    if (expectedPresent) {
      assertThat(optionalP.get().get()).isEqualTo(expectedValueIfPresent);
    }

    Optional<jakarta.inject.Provider<String>> optionalJkP =
        injector.getInstance(keyFor(optionalOfJakartaProviderString, annotation));
    assertThat(optionalJkP.isPresent()).isEqualTo(expectedPresent);
    if (expectedPresent) {
      assertThat(optionalJkP.get().get()).isEqualTo(expectedValueIfPresent);
    }
    optionalJkP =
        Optional.fromJavaUtil(
            injector.getInstance(keyFor(javaOptionalOfJakartaProviderString, annotation)));
    assertThat(optionalJkP.isPresent()).isEqualTo(expectedPresent);
    if (expectedPresent) {
      assertThat(optionalJkP.get().get()).isEqualTo(expectedValueIfPresent);
    }
  }

  public void testOptionalIsAbsentByDefault() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class);
          }
        };
    Injector injector = Guice.createInjector(module);
    assertOptionalState(injector, ExpectedValueState.EMPTY, ExpectedProviderState.EMPTY, null);
    assertOptionalVisitor(stringKey, setOf(module), VisitType.BOTH, 0, null, null, null);
  }

  public void testUsesUserBoundValue() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class);
          }

          @Provides
          String provideString() {
            return "foo";
          }
        };

    Injector injector = Guice.createInjector(module);
    assertEquals("foo", injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "foo");
    assertOptionalVisitor(
        stringKey, setOf(module), VisitType.BOTH, 0, null, null, providerInstance("foo"));
  }

  public void testUsesUserBoundValueNullProvidersMakeAbsent() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class);
          }

          @Provides
          String provideString() {
            return null;
          }
        };

    Injector injector = Guice.createInjector(module);
    assertEquals(null, injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.EMPTY, ExpectedProviderState.PRESENT, null);
    assertOptionalVisitor(
        stringKey, setOf(module), VisitType.BOTH, 0, null, null, providerInstance(null));
  }

  private static class JitBinding {
    @Inject
    JitBinding() {}
  }

  private static class DependsOnJitBinding {
    @Inject
    DependsOnJitBinding(JitBinding jitBinding) {}
  }

  // A previous version of OptionalBinder would fail to find jit dependendencies that were created
  // by other bindings
  public void testOptionalBinderDependsOnJitBinding() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), JitBinding.class);
          }
        };

    // Everything should be absent since nothing triggered discovery of the jit binding
    Injector injector = Guice.createInjector(module);
    assertFalse(injector.getInstance(optionalKey(JitBinding.class)).isPresent());
    assertNull(injector.getExistingBinding(Key.get(JitBinding.class)));

    // in this case, because jit bindings are allowed in this injector, the DependsOnJitBinding
    // binding will get initialized and create jit bindings for its dependency. The optionalbinder
    // should then pick it up
    module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), JitBinding.class);
            bind(DependsOnJitBinding.class);
          }
        };
    injector = Guice.createInjector(module);
    assertTrue(injector.getInstance(optionalKey(JitBinding.class)).isPresent());
    assertNotNull(injector.getExistingBinding(Key.get(JitBinding.class)));

    // in this case, because the jit binding is discovered dynamically, the optionalbinder won't
    // find it.  In prior implementations of OptionalBinder this would depend on the exact
    // sequencing of the installation of OptionalBinder vs. these injection points that trigger
    // dynamic injection.  In the current implementation it will consistently not find it.
    module =
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(Object.class)
                .toProvider(
                    new Provider<Object>() {
                      @Inject
                      void setter(Injector injector) {
                        injector.getInstance(JitBinding.class);
                      }

                      @Override
                      public Object get() {
                        return null;
                      }
                    });
            OptionalBinder.newOptionalBinder(binder(), JitBinding.class);
          }
        };
    injector = Guice.createInjector(module);
    assertFalse(injector.getInstance(optionalKey(JitBinding.class)).isPresent());
    assertNotNull(injector.getExistingBinding(Key.get(JitBinding.class)));
  }

  public <T> Key<Optional<T>> optionalKey(Class<T> type) {
    return Key.get(RealOptionalBinder.optionalOf(TypeLiteral.get(type)));
  }

  public void testSetDefault() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("a");
          }
        };
    Injector injector = Guice.createInjector(module);
    assertEquals("a", injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "a");
    assertOptionalVisitor(stringKey, setOf(module), VisitType.BOTH, 0, instance("a"), null, null);
  }

  public void testSetBinding() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setBinding().toInstance("a");
          }
        };
    Injector injector = Guice.createInjector(module);
    assertEquals("a", injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "a");
    assertOptionalVisitor(stringKey, setOf(module), VisitType.BOTH, 0, null, instance("a"), null);
  }

  public void testSetBindingOverridesDefault() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<String> optionalBinder =
                OptionalBinder.newOptionalBinder(binder(), String.class);
            optionalBinder.setDefault().toInstance("a");
            optionalBinder.setBinding().toInstance("b");
          }
        };
    Injector injector = Guice.createInjector(module);
    assertEquals("b", injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "b");
    assertOptionalVisitor(
        stringKey, setOf(module), VisitType.BOTH, 0, instance("a"), instance("b"), null);
  }

  public void testSpreadAcrossModules() throws Exception {
    Module module1 =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class);
          }
        };
    Module module2 =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("a");
          }
        };
    Module module3 =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setBinding().toInstance("b");
          }
        };

    Injector injector = Guice.createInjector(module1, module2, module3);

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "b");
    assertOptionalVisitor(
        stringKey,
        setOf(module1, module2, module3),
        VisitType.BOTH,
        0,
        instance("a"),
        instance("b"),
        null);
  }

  public void testExactSameBindingCollapses_defaults() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setDefault()
                .toInstance(new String("a")); // using new String to ensure .equals is checked.
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setDefault()
                .toInstance(new String("a"));
          }
        };
    Injector injector = Guice.createInjector(module);
    assertEquals("a", injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "a");
    assertOptionalVisitor(stringKey, setOf(module), VisitType.BOTH, 0, instance("a"), null, null);
  }

  public void testExactSameBindingCollapses_actual() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setBinding()
                .toInstance(new String("a")); // using new String to ensure .equals is checked.
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setBinding()
                .toInstance(new String("a"));
          }
        };
    Injector injector = Guice.createInjector(module);
    assertEquals("a", injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.PRESENT, ExpectedProviderState.PRESENT, "a");
    assertOptionalVisitor(stringKey, setOf(module), VisitType.BOTH, 0, null, instance("a"), null);
  }

  public void testDifferentBindingsFail_defaults() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("a");
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("b");
          }
        };
    try {
      Guice.createInjector(module);
      fail();
    } catch (CreationException ce) {
      assertEquals(ce.getMessage(), 1, ce.getErrorMessages().size());
      assertContains(
          ce.getMessage(),
          "String annotated with @RealOptionalBinder$Default was bound multiple times.");
    }
  }

  public void testDifferentBindingsFail_actual() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setBinding().toInstance("a");
            OptionalBinder.newOptionalBinder(binder(), String.class).setBinding().toInstance("b");
          }
        };
    try {
      Guice.createInjector(module);
      fail();
    } catch (CreationException ce) {
      assertEquals(ce.getMessage(), 1, ce.getErrorMessages().size());
      assertContains(
          ce.getMessage(),
          "String annotated with @RealOptionalBinder$Actual was bound multiple times.",
          "1  : " + getShortName(module) + ".configure",
          "2  : " + getShortName(module) + ".configure");
    }
  }

  public void testDifferentBindingsFail_both() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("a");
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("b");
            OptionalBinder.newOptionalBinder(binder(), String.class).setBinding().toInstance("b");
            OptionalBinder.newOptionalBinder(binder(), String.class).setBinding().toInstance("c");
          }
        };
    try {
      Guice.createInjector(module);
      fail();
    } catch (CreationException ce) {
      assertEquals(ce.getMessage(), 2, ce.getErrorMessages().size());
      assertContains(
          ce.getMessage(),
          "String annotated with @RealOptionalBinder$Default was bound multiple times.",
          "String annotated with @RealOptionalBinder$Actual was bound multiple times.");
    }
  }

  public void testQualifiedAggregatesTogether() throws Exception {
    Module module1 =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), Key.get(String.class, Names.named("foo")));
          }
        };
    Module module2 =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), Key.get(String.class, Names.named("foo")))
                .setDefault()
                .toInstance("a");
          }
        };
    Module module3 =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), Key.get(String.class, Names.named("foo")))
                .setBinding()
                .toInstance("b");
          }
        };

    Injector injector = Guice.createInjector(module1, module2, module3);
    assertEquals("b", injector.getInstance(Key.get(String.class, Names.named("foo"))));

    assertOptionalState(
        injector,
        Names.named("foo"),
        ExpectedValueState.PRESENT,
        ExpectedProviderState.PRESENT,
        "b");
    assertOptionalVisitor(
        Key.get(String.class, Names.named("foo")),
        setOf(module1, module2, module3),
        VisitType.BOTH,
        0,
        instance("a"),
        instance("b"),
        null);
  }

  public void testMultipleDifferentOptionals() {
    final Key<String> bKey = Key.get(String.class, named("b"));
    final Key<String> cKey = Key.get(String.class, named("c"));
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("a");
            OptionalBinder.newOptionalBinder(binder(), Integer.class).setDefault().toInstance(1);

            OptionalBinder.newOptionalBinder(binder(), bKey).setDefault().toInstance("b");
            OptionalBinder.newOptionalBinder(binder(), cKey).setDefault().toInstance("c");
          }
        };
    Injector injector = Guice.createInjector(module);
    assertEquals("a", injector.getInstance(String.class));
    assertEquals(1, injector.getInstance(Integer.class).intValue());
    assertEquals("b", injector.getInstance(bKey));
    assertEquals("c", injector.getInstance(cKey));

    assertOptionalVisitor(stringKey, setOf(module), VisitType.BOTH, 3, instance("a"), null, null);
    assertOptionalVisitor(intKey, setOf(module), VisitType.BOTH, 3, instance(1), null, null);
    assertOptionalVisitor(bKey, setOf(module), VisitType.BOTH, 3, instance("b"), null, null);
    assertOptionalVisitor(cKey, setOf(module), VisitType.BOTH, 3, instance("c"), null, null);
  }

  public void testOptionalIsAppropriatelyLazy() throws Exception {
    Module module =
        new AbstractModule() {
          int nextValue = 1;

          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), Integer.class)
                .setDefault()
                .to(Key.get(Integer.class, Names.named("foo")));
          }

          @Provides
          @Named("foo")
          int provideInt() {
            return nextValue++;
          }
        };
    Injector injector = Guice.createInjector(module);

    Optional<Provider<Integer>> optionalP =
        injector.getInstance(Key.get(optionalOfProviderInteger));
    Optional<jakarta.inject.Provider<Integer>> optionalJkP =
        injector.getInstance(Key.get(optionalOfJakartaProviderInteger));

    assertEquals(1, injector.getInstance(Integer.class).intValue());
    assertEquals(2, injector.getInstance(Integer.class).intValue());

    // Calling .get() on an Optional<Integer> multiple times will keep giving the same thing
    Optional<Integer> optional = injector.getInstance(Key.get(optionalOfInteger));
    assertEquals(3, optional.get().intValue());
    assertEquals(3, optional.get().intValue());
    // But getting another Optional<Integer> will give a new one.
    assertEquals(4, injector.getInstance(Key.get(optionalOfInteger)).get().intValue());

    // and same rules with java.util.Optional
    optional = Optional.fromJavaUtil(injector.getInstance(Key.get(javaOptionalOfInteger)));
    assertEquals(5, optional.get().intValue());
    assertEquals(5, optional.get().intValue());
    optional = Optional.fromJavaUtil(injector.getInstance(Key.get(javaOptionalOfInteger)));
    assertEquals(6, optional.get().intValue());

    // And the Optional<Provider> will return a provider that gives a new value each time.
    // (both for guava & java.util Optional)
    assertEquals(7, optionalP.get().get().intValue());
    assertEquals(8, optionalP.get().get().intValue());
    optionalP = Optional.fromJavaUtil(injector.getInstance(Key.get(javaOptionalOfProviderInteger)));
    assertEquals(9, optionalP.get().get().intValue());
    assertEquals(10, optionalP.get().get().intValue());

    // Same with jakarta provider.
    assertEquals(11, optionalJkP.get().get().intValue());
    assertEquals(12, optionalJkP.get().get().intValue());
    optionalJkP =
        Optional.fromJavaUtil(injector.getInstance(Key.get(javaOptionalOfJakartaProviderInteger)));
    assertEquals(13, optionalJkP.get().get().intValue());
    assertEquals(14, optionalJkP.get().get().intValue());
  }

  public void testLinkedToNullProvidersMakeAbsentValuesAndPresentProviders_default()
      throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setDefault()
                .toProvider(Providers.<String>of(null));
          }
        };
    Injector injector = Guice.createInjector(module);
    assertNull(injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.EMPTY, ExpectedProviderState.PRESENT, null);
    assertOptionalVisitor(
        stringKey,
        setOf(module),
        VisitType.BOTH,
        0,
        SpiUtils.<String>providerInstance(null),
        null,
        null);
  }

  public void testLinkedToNullProvidersMakeAbsentValuesAndPresentProviders_actual()
      throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setBinding()
                .toProvider(Providers.<String>of(null));
          }
        };
    Injector injector = Guice.createInjector(module);
    assertNull(injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.EMPTY, ExpectedProviderState.PRESENT, null);
    assertOptionalVisitor(
        stringKey,
        setOf(module),
        VisitType.BOTH,
        0,
        null,
        SpiUtils.<String>providerInstance(null),
        null);
  }

  // TODO(sameb): Maybe change this?
  public void testLinkedToNullActualDoesntFallbackToDefault() throws Exception {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), String.class).setDefault().toInstance("a");
            OptionalBinder.newOptionalBinder(binder(), String.class)
                .setBinding()
                .toProvider(Providers.<String>of(null));
          }
        };
    Injector injector = Guice.createInjector(module);
    assertNull(injector.getInstance(String.class));

    assertOptionalState(injector, ExpectedValueState.EMPTY, ExpectedProviderState.PRESENT, null);
    assertOptionalVisitor(
        stringKey,
        setOf(module),
        VisitType.BOTH,
        0,
        instance("a"),
        SpiUtils.<String>providerInstance(null),
        null);
  }

  public void testSourceLinesInException() {
    Module module =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder.newOptionalBinder(binder(), Integer.class).setDefault();
          }
        };
    try {
      Guice.createInjector(module);
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "No implementation for Integer",
          "Requested by:",
          getShortName(module) + ".configure");
    }
  }

  public void testDependencies_both() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                OptionalBinder<String> optionalbinder =
                    OptionalBinder.newOptionalBinder(binder(), String.class);
                optionalbinder.setDefault().toInstance("A");
                optionalbinder.setBinding().to(Key.get(String.class, Names.named("b")));
                bindConstant().annotatedWith(Names.named("b")).to("B");
              }
            });

    Binding<String> binding = injector.getBinding(Key.get(String.class));
    HasDependencies withDependencies = (HasDependencies) binding;
    Set<String> elements = Sets.newHashSet();
    elements.addAll(recurseForDependencies(injector, withDependencies));
    assertEquals(ImmutableSet.of("B"), elements);
  }

  public void testDependencies_actual() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                OptionalBinder<String> optionalbinder =
                    OptionalBinder.newOptionalBinder(binder(), String.class);
                optionalbinder.setBinding().to(Key.get(String.class, Names.named("b")));
                bindConstant().annotatedWith(Names.named("b")).to("B");
              }
            });

    Binding<String> binding = injector.getBinding(Key.get(String.class));
    HasDependencies withDependencies = (HasDependencies) binding;
    Set<String> elements = Sets.newHashSet();
    elements.addAll(recurseForDependencies(injector, withDependencies));
    assertEquals(ImmutableSet.of("B"), elements);
  }

  public void testDependencies_default() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                OptionalBinder<String> optionalbinder =
                    OptionalBinder.newOptionalBinder(binder(), String.class);
                optionalbinder.setDefault().toInstance("A");
              }
            });

    Binding<String> binding = injector.getBinding(Key.get(String.class));
    HasDependencies withDependencies = (HasDependencies) binding;
    Set<String> elements = Sets.newHashSet();
    elements.addAll(recurseForDependencies(injector, withDependencies));
    assertEquals(ImmutableSet.of("A"), elements);
  }

  @SuppressWarnings("rawtypes")
  private Set<String> recurseForDependencies(Injector injector, HasDependencies hasDependencies) {
    Set<String> elements = Sets.newHashSet();
    for (Dependency<?> dependency : hasDependencies.getDependencies()) {
      Binding<?> binding = injector.getBinding(dependency.getKey());
      HasDependencies deps = (HasDependencies) binding;
      if (binding instanceof InstanceBinding) {
        elements.add((String) ((InstanceBinding) binding).getInstance());
      } else {
        elements.addAll(recurseForDependencies(injector, deps));
      }
    }
    return elements;
  }

  /** Doubly-installed modules should not conflict, even when one is overridden. */
  public void testModuleOverrideRepeatedInstalls_toInstance() {
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<String> b = OptionalBinder.newOptionalBinder(binder(), String.class);
            b.setDefault().toInstance("A");
            b.setBinding().toInstance("B");
          }
        };

    assertEquals("B", Guice.createInjector(m, m).getInstance(Key.get(String.class)));

    Injector injector = Guice.createInjector(m, Modules.override(m).with(m));
    assertEquals("B", injector.getInstance(Key.get(String.class)));

    assertOptionalVisitor(
        stringKey,
        setOf(m, Modules.override(m).with(m)),
        VisitType.BOTH,
        0,
        instance("A"),
        instance("B"),
        null);
  }

  public void testModuleOverrideRepeatedInstalls_toKey() {
    final Key<String> aKey = Key.get(String.class, Names.named("A_string"));
    final Key<String> bKey = Key.get(String.class, Names.named("B_string"));
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(aKey).toInstance("A");
            bind(bKey).toInstance("B");

            OptionalBinder<String> b = OptionalBinder.newOptionalBinder(binder(), String.class);
            b.setDefault().to(aKey);
            b.setBinding().to(bKey);
          }
        };

    assertEquals("B", Guice.createInjector(m, m).getInstance(Key.get(String.class)));

    Injector injector = Guice.createInjector(m, Modules.override(m).with(m));
    assertEquals("B", injector.getInstance(Key.get(String.class)));

    assertOptionalVisitor(
        stringKey,
        setOf(m, Modules.override(m).with(m)),
        VisitType.BOTH,
        0,
        linked(aKey),
        linked(bKey),
        null);
  }

  public void testModuleOverrideRepeatedInstalls_toProviderInstance() {
    // Providers#of() does not redefine equals/hashCode, so use the same one both times.
    final Provider<String> aProvider = Providers.of("A");
    final Provider<String> bProvider = Providers.of("B");
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<String> b = OptionalBinder.newOptionalBinder(binder(), String.class);
            b.setDefault().toProvider(aProvider);
            b.setBinding().toProvider(bProvider);
          }
        };

    assertEquals("B", Guice.createInjector(m, m).getInstance(Key.get(String.class)));

    Injector injector = Guice.createInjector(m, Modules.override(m).with(m));
    assertEquals("B", injector.getInstance(Key.get(String.class)));

    assertOptionalVisitor(
        stringKey,
        setOf(m, Modules.override(m).with(m)),
        VisitType.BOTH,
        0,
        providerInstance("A"),
        providerInstance("B"),
        null);
  }

  private static class AStringProvider implements Provider<String> {
    @Override
    public String get() {
      return "A";
    }
  }

  private static class BStringProvider implements Provider<String> {
    @Override
    public String get() {
      return "B";
    }
  }

  public void testModuleOverrideRepeatedInstalls_toProviderKey() {
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<String> b = OptionalBinder.newOptionalBinder(binder(), String.class);
            b.setDefault().toProvider(Key.get(AStringProvider.class));
            b.setBinding().toProvider(Key.get(BStringProvider.class));
          }
        };

    assertEquals("B", Guice.createInjector(m, m).getInstance(Key.get(String.class)));

    Injector injector = Guice.createInjector(m, Modules.override(m).with(m));
    assertEquals("B", injector.getInstance(Key.get(String.class)));

    assertOptionalVisitor(
        stringKey,
        setOf(m, Modules.override(m).with(m)),
        VisitType.BOTH,
        0,
        providerKey(Key.get(AStringProvider.class)),
        providerKey(Key.get(BStringProvider.class)),
        null);
  }

  private static class StringGrabber {
    private final String string;

    @SuppressWarnings("unused") // Found by reflection
    public StringGrabber(@Named("A_string") String string) {
      this.string = string;
    }

    @SuppressWarnings("unused") // Found by reflection
    public StringGrabber(@Named("B_string") String string, int unused) {
      this.string = string;
    }

    @Override
    public int hashCode() {
      return string.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return (obj instanceof StringGrabber) && ((StringGrabber) obj).string.equals(string);
    }

    @Override
    public String toString() {
      return "StringGrabber(" + string + ")";
    }
  }

  public void testModuleOverrideRepeatedInstalls_toConstructor() {
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            Key<String> aKey = Key.get(String.class, Names.named("A_string"));
            Key<String> bKey = Key.get(String.class, Names.named("B_string"));
            bind(aKey).toInstance("A");
            bind(bKey).toInstance("B");
            bind(Integer.class).toInstance(0); // used to disambiguate constructors

            OptionalBinder<StringGrabber> b =
                OptionalBinder.newOptionalBinder(binder(), StringGrabber.class);
            try {
              b.setDefault().toConstructor(StringGrabber.class.getConstructor(String.class));
              b.setBinding()
                  .toConstructor(StringGrabber.class.getConstructor(String.class, int.class));
            } catch (NoSuchMethodException e) {
              fail("No such method: " + e.getMessage());
            }
          }
        };

    assertEquals("B", Guice.createInjector(m, m).getInstance(Key.get(StringGrabber.class)).string);

    Injector injector = Guice.createInjector(m, Modules.override(m).with(m));
    assertEquals("B", injector.getInstance(Key.get(StringGrabber.class)).string);
  }

  /**
   * Unscoped bindings should not conflict, whether they were bound with no explicit scope, or
   * explicitly bound in {@link Scopes#NO_SCOPE}.
   */
  public void testDuplicateUnscopedBindings() {
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<Integer> b = OptionalBinder.newOptionalBinder(binder(), Integer.class);
            b.setDefault().to(Key.get(Integer.class, named("foo")));
            b.setDefault().to(Key.get(Integer.class, named("foo"))).in(Scopes.NO_SCOPE);
            b.setBinding().to(Key.get(Integer.class, named("foo")));
            b.setBinding().to(Key.get(Integer.class, named("foo"))).in(Scopes.NO_SCOPE);
          }

          @Provides
          @Named("foo")
          int provideInt() {
            return 5;
          }
        };
    assertEquals(5, Guice.createInjector(m).getInstance(Integer.class).intValue());
  }

  /** Ensure key hash codes are fixed at injection time, not binding time. */
  public void testKeyHashCodesFixedAtInjectionTime() {
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<List<String>> b =
                OptionalBinder.newOptionalBinder(binder(), listOfStrings);
            List<String> list = Lists.newArrayList();
            b.setDefault().toInstance(list);
            b.setBinding().toInstance(list);
            list.add("A");
            list.add("B");
          }
        };

    Injector injector = Guice.createInjector(m);
    for (Entry<Key<?>, Binding<?>> entry : injector.getAllBindings().entrySet()) {
      Key<?> bindingKey = entry.getKey();
      Key<?> clonedKey;
      if (bindingKey.getAnnotation() != null) {
        clonedKey = bindingKey.ofType(bindingKey.getTypeLiteral());
      } else if (bindingKey.getAnnotationType() != null) {
        clonedKey = bindingKey.ofType(bindingKey.getTypeLiteral());
      } else {
        clonedKey = Key.get(bindingKey.getTypeLiteral());
      }
      assertEquals(bindingKey, clonedKey);
      assertEquals(
          "Incorrect hashcode for " + bindingKey + " -> " + entry.getValue(),
          bindingKey.hashCode(),
          clonedKey.hashCode());
    }
  }

  /** Ensure bindings do not rehash their keys once returned from {@link Elements#getElements}. */
  public void testBindingKeysFixedOnReturnFromGetElements() {
    final List<String> list = Lists.newArrayList();
    Module m =
        new AbstractModule() {
          @Override
          protected void configure() {
            OptionalBinder<List<String>> b =
                OptionalBinder.newOptionalBinder(binder(), listOfStrings);
            b.setDefault().toInstance(list);
            list.add("A");
            list.add("B");
          }
        };

    InstanceBinding<?> binding =
        Elements.getElements(m).stream()
            .filter(InstanceBinding.class::isInstance)
            .map(InstanceBinding.class::cast)
            .collect(onlyElement());
    Key<?> keyBefore = binding.getKey();
    assertEquals(listOfStrings, keyBefore.getTypeLiteral());

    list.add("C");
    Key<?> keyAfter = binding.getKey();
    assertSame(keyBefore, keyAfter);
  }

  @BindingAnnotation
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
  private static @interface Marker {}

  @Marker
  public void testMatchingMarkerAnnotations() throws Exception {
    Method m = OptionalBinderTest.class.getDeclaredMethod("testMatchingMarkerAnnotations");
    assertNotNull(m);
    final Annotation marker = m.getAnnotation(Marker.class);
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              public void configure() {
                OptionalBinder<Integer> mb1 =
                    OptionalBinder.newOptionalBinder(
                        binder(), Key.get(Integer.class, Marker.class));
                OptionalBinder<Integer> mb2 =
                    OptionalBinder.newOptionalBinder(binder(), Key.get(Integer.class, marker));
                mb1.setDefault().toInstance(1);
                mb2.setBinding().toInstance(2);

                // This assures us that the two binders are equivalent, so we expect the instance
                // added to each to have been added to one set.
                assertEquals(mb1, mb2);
              }
            });
    Integer i1 = injector.getInstance(Key.get(Integer.class, Marker.class));
    Integer i2 = injector.getInstance(Key.get(Integer.class, marker));

    // These must be identical, because the marker annotations collapsed to the same thing.
    assertSame(i1, i2);
    assertEquals(2, i2.intValue());
  }

  // Tests for com.google.inject.internal.WeakKeySet not leaking memory.
  public void testWeakKeySet_integration() {
    Injector parentInjector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(String.class).toInstance("hi");
              }
            });
    WeakKeySetUtils.assertNotBanned(parentInjector, Key.get(Integer.class));

    Injector childInjector =
        parentInjector.createChildInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                OptionalBinder.newOptionalBinder(binder(), Integer.class)
                    .setDefault()
                    .toInstance(4);
              }
            });
    WeakReference<Injector> weakRef = new WeakReference<>(childInjector);
    WeakKeySetUtils.assertBanned(parentInjector, Key.get(Integer.class));

    // Clear the ref, GC, and ensure that we are no longer banning.
    childInjector = null;

    Asserts.awaitClear(weakRef);
    WeakKeySetUtils.assertNotBanned(parentInjector, Key.get(Integer.class));
  }

  public void testCompareEqualsAgainstOtherAnnotation() {
    RealOptionalBinder.Actual impl1 = new RealOptionalBinder.ActualImpl("foo");
    RealOptionalBinder.Actual other1 = Dummy.class.getAnnotation(RealOptionalBinder.Actual.class);
    assertEquals(impl1, other1);

    RealOptionalBinder.Default impl2 = new RealOptionalBinder.DefaultImpl("foo");
    RealOptionalBinder.Default other2 = Dummy.class.getAnnotation(RealOptionalBinder.Default.class);
    assertEquals(impl2, other2);

    assertFalse(impl1.equals(impl2));
    assertFalse(impl1.equals(other2));
    assertFalse(impl2.equals(other1));
    assertFalse(other1.equals(other2));
  }

  static final class JitInjectable {
    @Inject
    JitInjectable() {}
  }

  public void testOptionalBinderAndProviderLookup() {
    Optional<JitInjectable> optional =
        Guice.createInjector(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    OptionalBinder.newOptionalBinder(binder(), JitInjectable.class);
                    getProvider(JitInjectable.class);
                  }
                })
            .getInstance(new Key<Optional<JitInjectable>>() {});
    assertThat(optional).isPresent();
  }

  /**
   * Tests that an OptionalBinder that depends on a Multibinder resolves initialization order
   * correctly to an optimized provider.
   */
  public void testOptionalBinderDependsOnMultibinder() {
    Key<Set<String>> key = new Key<Set<String>>() {};
    Optional<Provider<Set<String>>> e =
        Guice.createInjector(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    OptionalBinder.newOptionalBinder(binder(), key);
                    Multibinder.newSetBinder(binder(), String.class);
                  }
                })
            .getInstance(new Key<Optional<Provider<Set<String>>>>() {});
    // Check that we are using the optimized path.
    assertThat(e.get()).isInstanceOf(InternalFactory.InstanceProvider.class);
    e =
        Guice.createInjector(
                new AbstractModule() {
                  @Override
                  protected void configure() {
                    Multibinder.newSetBinder(binder(), String.class);
                    OptionalBinder.newOptionalBinder(binder(), key);
                  }
                })
            .getInstance(new Key<Optional<Provider<Set<String>>>>() {});
    assertThat(e.get()).isInstanceOf(InternalFactory.InstanceProvider.class);
  }

  /**
   * Returns the short name for a module instance. Used to get the name of the anoymous class that
   * can change depending on the order the module intance is created.
   */
  private static String getShortName(Module module) {
    String fullName = module.getClass().getName();
    return fullName.substring(fullName.lastIndexOf(".") + 1);
  }

  @RealOptionalBinder.Actual("foo")
  @RealOptionalBinder.Default("foo")
  static class Dummy {}

  @SuppressWarnings("unchecked")
  private <V> Set<V> setOf(V... elements) {
    return ImmutableSet.copyOf(elements);
  }

  static class MyClass {}
}
