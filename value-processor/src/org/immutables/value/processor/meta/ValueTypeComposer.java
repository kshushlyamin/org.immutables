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

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.ElementFilter;
import org.immutables.value.processor.meta.Proto.Protoclass;

/**
 * It may grow later in some better abstraction, but as it stands now, currently it is
 * just a glue between new "protoclass" model and old discovery routines.
 */
public final class ValueTypeComposer {
  private final ProcessingEnvironment processing;
  private final Round round;
  @Nullable
  private final String typeMoreObjects;

  ValueTypeComposer(Round round) {
    this.round = round;
    this.processing = round.processing();
    this.typeMoreObjects = inferTypeMoreObjects();
  }

  /**
   * @return current Guava's MoreObjects or {@code null} if no Guava available on the classpath.
   */
  @Nullable
  String inferTypeMoreObjects() {
    String typeMoreObjects = UnshadeGuava.typeString("base.MoreObjects");
    String typeObjects = UnshadeGuava.typeString("base.Objects");
    @Nullable TypeElement typeElement =
        processing.getElementUtils().getTypeElement(typeMoreObjects);

    if (typeElement != null) {
      return typeMoreObjects;
    }

    typeElement = processing.getElementUtils().getTypeElement(typeObjects);
    if (typeElement != null) {
      return typeObjects;
    }

    return null;
  }

  ValueType compose(Protoclass protoclass) {
    ValueType type = new ValueType();
    type.round = round;
    type.typeMoreObjects = typeMoreObjects;
    type.element = protoclass.sourceElement();
    type.immutableFeatures = protoclass.features();
    type.constitution = protoclass.constitution();

    if (protoclass.kind().isFactory()) {
      new FactoryMethodAttributesCollector(protoclass, type).collect();
    } else if (protoclass.kind().isValue()) {
      // This check is legacy, most such checks should have been done on a higher level?
      if (isAbstractValueType(type.element)) {
        checkForMutableFields(protoclass, (TypeElement) type.element);

        new AccessorAttributesCollector(protoclass, type).collect();
      } else {
        protoclass.report()
            .error(
                "Type '%s' annotated or included as value must be non-final class, interface or annotation type (and without type parameters)",
                protoclass.sourceElement().getSimpleName());
        // Do nothing now. kind of way to less blow things up when it happens.
      }
    }

    checkAttributeNamesForDuplicates(type, protoclass);
    return type;
  }

  private void checkForMutableFields(Protoclass protoclass, TypeElement element) {
    for (VariableElement field : ElementFilter.fieldsIn(
        processing.getElementUtils().getAllMembers(CachingElements.getDelegate(element)))) {
      if (!field.getModifiers().contains(Modifier.FINAL)) {
        Reporter report = protoclass.report();
        boolean ownField = field.getEnclosingElement().equals(element);
        if (ownField) {
          report.withElement(field).warning("Avoid introduction of fields (except constants) in abstract value types");
        } else {
          report.warning("Abstract value type inherits mutable fields");
        }
      }
    }
  }

  private void checkAttributeNamesForDuplicates(ValueType type, Protoclass protoclass) {
    if (!type.attributes.isEmpty()) {
      Multiset<String> attributeNames = HashMultiset.create(type.attributes.size());
      for (ValueAttribute attribute : type.attributes) {
        attributeNames.add(attribute.name());
      }

      List<String> duplicates = Lists.newArrayList();
      for (Multiset.Entry<String> entry : attributeNames.entrySet()) {
        if (entry.getCount() > 1) {
          duplicates.add(entry.getElement());
        }
      }

      if (!duplicates.isEmpty()) {
        protoclass.report()
            .error("Duplicate attribute names %s. You should check if correct @Value.Style applied",
                duplicates);
      }
    }
  }

  static boolean isAbstractValueType(Element element) {
    boolean ofSupportedKind = false
        || element.getKind() == ElementKind.INTERFACE
        || element.getKind() == ElementKind.ANNOTATION_TYPE
        || element.getKind() == ElementKind.CLASS;

    boolean staticOrTopLevel = false
        || element.getEnclosingElement().getKind() == ElementKind.PACKAGE
        || element.getModifiers().contains(Modifier.STATIC);

    boolean nonFinal = !element.getModifiers().contains(Modifier.FINAL);

    boolean hasNoTypeParameters = ofSupportedKind && ((TypeElement) element).getTypeParameters().isEmpty();
    return ofSupportedKind && staticOrTopLevel && nonFinal && hasNoTypeParameters;
  }
}
