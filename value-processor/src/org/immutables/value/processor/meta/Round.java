/*
    Copyright 2014 Immutables Authors and Contributors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.value.processor.meta;

import com.google.common.collect.Interners;
import com.google.common.collect.Interner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.immutables.value.Value;
import org.immutables.value.processor.meta.Proto.DeclaringPackage;
import org.immutables.value.processor.meta.Proto.DeclaringType;
import org.immutables.value.processor.meta.Proto.Protoclass;
import org.immutables.value.processor.meta.Proto.Protoclass.Kind;

@Value.Immutable
public abstract class Round {
  public abstract ProcessingEnvironment processing();

  public abstract RoundEnvironment round();

  public abstract Set<TypeElement> annotations();

  @Value.Derived
  public Interner<Protoclass> protoclassInterner() {
    return Interners.newStrongInterner();
  }

  @Value.Derived
  public Interner<DeclaringPackage> packageInterner() {
    return Interners.newStrongInterner();
  }

  @Value.Derived
  public Interner<DeclaringType> typeInterner() {
    return Interners.newStrongInterner();
  }

  @Value.Derived
  ValueTypeComposer composer() {
    return new ValueTypeComposer(this);
  }

  @Value.Lazy
  Proto.Environment environment() {
    return ImmutableProto.Environment.of(processing(), this);
  }

  public Multimap<DeclaringPackage, ValueType> collectValues() {
    ImmutableList<Protoclass> protoclasses = collectProtoclasses();
    Map<DeclaringType, ValueType> enclosingTypes = Maps.newHashMap();

    ImmutableMultimap.Builder<DeclaringPackage, ValueType> builder = ImmutableMultimap.builder();

    // Collect enclosing
    for (Protoclass protoclass : protoclasses) {
      if (protoclass.kind().isEnclosing()) {
        ValueType type = composer().compose(protoclass);
        enclosingTypes.put(protoclass.declaringType().get(), type);
      }
    }
    // Collect remaining and attach if nested
    for (Protoclass protoclass : protoclasses) {
      @Nullable ValueType current = null;
      if (protoclass.kind().isNested()) {
        @Nullable ValueType enclosing = enclosingTypes.get(protoclass.enclosingOf().get());
        if (enclosing != null) {
          current = composer().compose(protoclass);
          // Attach nested to enclosing
          enclosing.addNested(current);
        }
      }
      // getting the ValueType if it was alredy created and put into enclosingTypes
      if (current == null && protoclass.kind().isEnclosing()) {
        current = enclosingTypes.get(protoclass.declaringType().get());
      }
      // If none then we just create it
      if (current == null) {
        current = composer().compose(protoclass);
      }
      // We put all enclosing and nested values by the package
      builder.put(protoclass.packageOf(), current);
    }

    return builder.build();
  }

  public Optional<Protoclass> definedValueProtoclassFor(TypeElement element) {
    ProtoclassCollecter collecter = new ProtoclassCollecter();
    collecter.collect(element);
    for (Protoclass protoclass : collecter.builder.build()) {
      if (protoclass.kind().isDefinedValue()) {
        return Optional.of(protoclass);
      }
    }
    return Optional.absent();
  }

  public ImmutableList<Protoclass> collectProtoclasses() {
    ProtoclassCollecter collecter = new ProtoclassCollecter();
    for (final Element element : allAnnotatedElements()) {
      collecter.collect(element);
    }
    return collecter.builder.build();
  }

  private Set<Element> allAnnotatedElements() {
    Set<Element> elements = Sets.newHashSetWithExpectedSize(100);
    for (TypeElement annotation : annotations()) {
      elements.addAll(round().getElementsAnnotatedWith(annotation));
    }
    return elements;
  }

  private class ProtoclassCollecter {
    final ImmutableList.Builder<Protoclass> builder = ImmutableList.builder();

    void collect(Element element) {
      switch (element.getKind()) {
      case ANNOTATION_TYPE:
      case INTERFACE:
      case CLASS:
        collectIncludedAndDefinedBy((TypeElement) element);
        break;
      case METHOD:
        collectDefinedBy((ExecutableElement) element);
        break;
      case PACKAGE:
        collectIncludedBy((PackageElement) element);
        break;
      default:
        Reporter.from(processing())
            .withElement(element)
            .warning("Unmatched annotation will be skipped for annotation processing");
      }
    }

    void collectDefinedBy(ExecutableElement element) {
      DeclaringType declaringType = typeInterner().intern(
          ImmutableProto.DeclaringType.builder()
          .environment(environment())
          .element((TypeElement) element.getEnclosingElement())
          .build());

      if (declaringType.verifiedFactory(element)) {
        builder.add(protoclassInterner().intern(ImmutableProto.Protoclass.builder()
            .environment(environment())
            .packageOf(declaringType.packageOf())
            .sourceElement(element)
            .declaringType(declaringType)
            .kind(Kind.DEFINED_FACTORY)
            .build()));
      }
    }

    void collectIncludedBy(PackageElement element) {
      final DeclaringPackage declaringPackage = packageInterner().intern(ImmutableProto.DeclaringPackage.builder()
          .environment(environment())
          .element(element)
          .build());

      if (declaringPackage.hasInclude()) {
        for (TypeElement sourceElement : declaringPackage.includedTypes()) {
          builder.add(protoclassInterner().intern(ImmutableProto.Protoclass.builder()
              .environment(environment())
              .packageOf(declaringPackage)
              .sourceElement(sourceElement)
              .kind(Kind.INCLUDED_IN_PACKAGE)
              .build()));
        }
      }
    }

    void collectIncludedAndDefinedBy(TypeElement element) {
      DeclaringType declaringType = typeInterner().intern(ImmutableProto.DeclaringType.builder()
          .environment(environment())
          .element(element)
          .build());

      if (declaringType.hasInclude()) {
        Kind kind = declaringType.isEnclosing()
            ? Kind.INCLUDED_IN_TYPE
            : Kind.INCLUDED_ON_TYPE;

        for (TypeElement sourceElement : declaringType.includedTypes()) {
          builder.add(protoclassInterner().intern(ImmutableProto.Protoclass.builder()
              .environment(environment())
              .packageOf(declaringType.packageOf())
              .sourceElement(sourceElement)
              .declaringType(declaringType)
              .kind(kind)
              .build()));
        }
      }

      if (declaringType.isImmutable() || declaringType.isEnclosing()) {
        Kind kind = kindOfDefinedBy(declaringType);

        builder.add(protoclassInterner().intern(ImmutableProto.Protoclass.builder()
            .environment(environment())
            .packageOf(declaringType.packageOf())
            .sourceElement(element)
            .declaringType(declaringType)
            .kind(kind)
            .build()));
      }
    }

    private Kind kindOfDefinedBy(DeclaringType declaringType) {
      if (declaringType.isImmutable()) {
        if (declaringType.isEnclosing()) {
          return Kind.DEFINED_AND_ENCLOSING_TYPE;
        } else if (declaringType.enclosingOf().isPresent()) {
          return Kind.DEFINED_NESTED_TYPE;
        } else {
          return Kind.DEFINED_TYPE;
        }
      }
      assert declaringType.isEnclosing();
      return Kind.DEFINED_ENCLOSING_TYPE;
    }
  }
}
