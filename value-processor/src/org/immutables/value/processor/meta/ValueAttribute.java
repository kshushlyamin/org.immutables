/*
    Copyright 2013-2014 Immutables Authors and Contributors

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

import com.google.common.base.Ascii;
import com.google.common.base.Functions;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.immutables.value.processor.meta.Styles.UsingName.AttributeNames;

/**
 * It's pointless to refactor this mess until
 * 1) Some sort of type calculus toolkit used/created
 * 2) Facets/Implicits in Generator toolkit with auto-memoising implemented
 */
public final class ValueAttribute extends TypeIntrospectionBase {

  private static final String GUAVA_IMMUTABLE_PREFIX = UnshadeGuava.typeString("collect.Immutable");
  private static final String NULLABLE_SIMPLE_NAME = "Nullable";
  private static final String ID_ATTRIBUTE_NAME = "_id";

  public AttributeNames names;
  public boolean isGenerateDefault;
  public boolean isGenerateDerived;
  public boolean isGenerateAbstract;
  public boolean isGenerateLazy;
  public ImmutableList<String> typeParameters = ImmutableList.of();
  public Reporter reporter;

  public ValueType containingType;

  TypeMirror returnType;
  Element element;
  String returnTypeName;

  private boolean hasEnumFirstTypeParameter;
  @Nullable
  private TypeElement containedTypeElement;
  @Nullable
  private TypeElement containedSecondaryTypeElement;

  private boolean generateOrdinalValueSet;
  private TypeMirror arrayComponent;
  private boolean nullable;
  private String nullabilityPrefix;

  @Nullable
  private String rawTypeName;

  public String name() {
    return names.raw;
  }

  public boolean isBoolean() {
    return returnType.getKind() == TypeKind.BOOLEAN;
  }

  public boolean isInt() {
    return returnType.getKind() == TypeKind.INT;
  }

  public boolean isLong() {
    return returnType.getKind() == TypeKind.LONG;
  }

  public boolean isStringType() {
    return returnTypeName.equals(String.class.getName());
  }

  public boolean charType() {
    return returnType.getKind() == TypeKind.CHAR;
  }

  public String atNullability() {
    return isNullable() ? nullabilityPrefix : "";
  }

  public boolean isSimpleLiteralType() {
    return isPrimitive()
        || isStringType()
        || isEnumType();
  }

  public boolean isMandatory() {
    return isGenerateAbstract && !isContainerType() && !isNullable();
  }

  public boolean isNullable() {
    return nullable;
  }

  @Override
  public boolean isComparable() {
    return isNumberType() || isStringType() || super.isComparable();
  }

  @Nullable
  private String serializedName;

  /**
   * Serialized name, actully specified via annotation
   * @return name for JSON as overriden.
   */
  public String getSerializedName() {
    if (serializedName == null) {
      serializedName = readSerializedName();
    }
    return serializedName;
  }

  /**
   * Marshaled name for compatibility with repository.
   * @return get JSON name either specified or default.
   */
  public String getMarshaledName() {
    String serializedName = getSerializedName();
    if (!serializedName.isEmpty()) {
      return serializedName;
    }
    return name();
  }

  private String readSerializedName() {
    Optional<NamedMirror> namedAnnotation = NamedMirror.find(element);
    if (namedAnnotation.isPresent()) {
      String value = namedAnnotation.get().value();
      if (!value.isEmpty()) {
        return value;
      }
    }
    Optional<JsonPropertyMirror> jsonProperty = JsonPropertyMirror.find(element);
    if (jsonProperty.isPresent()) {
      String value = jsonProperty.get().value();
      if (!value.isEmpty()) {
        return value;
      }
    }
    if (isMarkedAsMongoId()) {
      return ID_ATTRIBUTE_NAME;
    }
    return "";
  }

  public boolean isForcedEmpty() {
    return !containingType.gsonTypeAdapters().emptyAsNulls();
  }

  @Override
  protected TypeMirror internalTypeMirror() {
    return returnType;
  }

  public String getType() {
    return returnTypeName;
  }

  public List<CharSequence> getAnnotations() {
    return AnnotationPrinting.getAnnotationLines(element);
  }

  public boolean isGsonIgnore() {
    // TBD need to optimize
    return IgnoreMirror.isPresent(element);
  }

  public List<String> typeParameters() {
    ensureTypeIntrospected();
    return arrayComponent != null ? ImmutableList.of(arrayComponent.toString()) : typeParameters;
  }

  public boolean isMapType() {
    return typeKind.isMappingKind();
  }

  public boolean isMultimapType() {
    return typeKind.isMultimapKind();
  }

  public boolean isListType() {
    return typeKind.isList();
  }

  private OrderKind orderKind = OrderKind.NONE;

  private enum OrderKind {
    NONE, NATURAL, REVERSE
  }

  public boolean isSetType() {
    return typeKind.isSet();
  }

  public boolean hasNaturalOrder() {
    return orderKind == OrderKind.NATURAL;
  }

  public boolean hasReverseOrder() {
    return orderKind == OrderKind.REVERSE;
  }

  public boolean isSortedSetType() {
    return typeKind.isSortedSet();
  }

  public boolean isSortedMapType() {
    return typeKind.isSortedMap();
  }

  public boolean isGenerateSortedSet() {
    return typeKind.isSortedSet();
  }

  public boolean isGenerateSortedMap() {
    return typeKind.isSortedMap();
  }

  private void checkOrderAnnotations() {
    Optional<NaturalOrderMirror> naturalOrderAnnotation = NaturalOrderMirror.find(element);
    Optional<ReverseOrderMirror> reverseOrderAnnotation = ReverseOrderMirror.find(element);

    if (naturalOrderAnnotation.isPresent() && reverseOrderAnnotation.isPresent()) {
      report()
          .error("@Value.Natural and @Value.Reverse annotations could not be used on the same attribute");
    } else if (naturalOrderAnnotation.isPresent()) {
      if (typeKind.isSortedKind()) {
        if (isComparable()) {
          orderKind = OrderKind.NATURAL;
        } else {
          report()
              .annotationNamed(NaturalOrderMirror.simpleName())
              .error("@Value.Natural should used on a set of Comparable elements (map keys)");
        }
      } else {
        report()
            .annotationNamed(NaturalOrderMirror.simpleName())
            .error("@Value.Natural should specify order for SortedSet, SortedMap, NavigableSet or NavigableMap attributes");
      }
    } else if (reverseOrderAnnotation.isPresent()) {
      if (typeKind.isSortedKind()) {
        if (isComparable()) {
          orderKind = OrderKind.REVERSE;
        } else {
          report()
              .annotationNamed(ReverseOrderMirror.simpleName())
              .error("@Value.Reverse should used with a set of Comparable elements");
        }
      } else {
        report()
            .annotationNamed(ReverseOrderMirror.simpleName())
            .error("@Value.Reverse should specify order for SortedSet, SortedMap, NavigableSet or NavigableMap attributes");
      }
    }
  }

  public boolean isJdkOptional() {
    return typeKind.isOptionalKind() && typeKind.isJdkOnlyContainerKind();
  }

  public boolean isJdkSpecializedOptional() {
    return typeKind.isOptionalSpecializedJdk();
  }

  public boolean isOptionalType() {
    return typeKind.isOptionalKind();
  }

  public boolean isCollectionType() {
    return typeKind.isCollectionKind();
  }

  public boolean isGenerateEnumSet() {
    return typeKind.isEnumSet();
  }

  public boolean isGuavaImmutableDeclared() {
    return typeKind.isContainerKind() && rawTypeName.startsWith(GUAVA_IMMUTABLE_PREFIX);
  }

  @Nullable
  private CharSequence defaultInterface;

  public CharSequence defaultInterface() {
    if (defaultInterface == null) {
      defaultInterface = inferDefaultInterface();
    }
    return defaultInterface;
  }

  private CharSequence inferDefaultInterface() {
    if (element.getEnclosingElement().getKind() == ElementKind.INTERFACE
        && !element.getModifiers().contains(Modifier.ABSTRACT)) {
      if (containingType.element.getKind() == ElementKind.INTERFACE) {
        return containingType.typeAbstract().relative();
      }
    }
    return "";
  }

  public boolean isMapLike() {
    return typeKind.isMappingKind();
  }

  public boolean isGenerateEnumMap() {
    return typeKind.isEnumMap();
  }

  public String getUnwrappedElementType() {
    return isContainerType() ? unwrapType(containmentTypeName()) : getElementType();
  }

  public String getWrappedElementType() {
    return wrapType(containmentTypeName());
  }

  private String containmentTypeName() {
    return (isArrayType() || isContainerType()) ? firstTypeParameter() : returnTypeName;
  }

  public String getRawType() {
    return rawTypeName != null ? rawTypeName : extractRawType(returnTypeName);
  }

  public String getConsumedElementType() {
    return (isUnwrappedElementPrimitiveType()
        || String.class.getName().equals(containmentTypeName())
        || hasEnumFirstTypeParameter)
        ? getWrappedElementType()
        : "? extends " + getWrappedElementType();
  }

  private String extractRawType(String className) {
    String rawType = className;
    int indexOfGenerics = rawType.indexOf('<');
    if (indexOfGenerics > 0) {
      rawType = rawType.substring(0, indexOfGenerics);
    }
    int endOfTypeAnnotations = rawType.lastIndexOf(' ');
    if (endOfTypeAnnotations > 0) {
      rawType = rawType.substring(endOfTypeAnnotations + 1);
    }
    return rawType;
  }

  public boolean isUnwrappedElementPrimitiveType() {
    return isPrimitiveType(getUnwrappedElementType());
  }

  public boolean isUnwrappedSecondaryElementPrimitiveType() {
    return isPrimitiveType(getUnwrappedSecondaryElementType());
  }

  public String firstTypeParameter() {
    return Iterables.getFirst(typeParameters(), "");
  }

  public String secondTypeParameter() {
    return Iterables.get(typeParameters(), 1);
  }

  public String getElementType() {
    return containmentTypeName();
  }

  @Nullable
  private List<String> expectedSubtypes;

  public List<String> getExpectedSubtypes() {
    if (expectedSubtypes == null) {
      ensureTypeIntrospected();
      if (!isPrimitiveElement()) {
        TypeElement supertypeElement = MoreObjects.firstNonNull(containedSecondaryTypeElement, containedTypeElement);
        Optional<ExpectedSubtypesMirror> annotationOnAttribute = ExpectedSubtypesMirror.find(element);
        if (annotationOnAttribute.isPresent()) {
          expectedSubtypes = ImmutableList.copyOf(annotationOnAttribute.get().valueName());
          if (expectedSubtypes.isEmpty()) {
            expectedSubtypes = tryFindSubtypes(supertypeElement);
          }
        } else {
          Optional<ExpectedSubtypesMirror> annotationOnType = ExpectedSubtypesMirror.find(supertypeElement);
          if (annotationOnType.isPresent()) {
            expectedSubtypes = ImmutableList.copyOf(annotationOnType.get().valueName());
            if (expectedSubtypes.isEmpty()) {
              expectedSubtypes = tryFindSubtypes(supertypeElement);
            }
          }
        }
      }
      if (expectedSubtypes == null) {
        expectedSubtypes = ImmutableList.of();
      }
    }
    return expectedSubtypes;
  }

  private ImmutableList<String> tryFindSubtypes(TypeElement supertypeElement) {
    ValueType surroundingType =
        MoreObjects.firstNonNull(containingType.enclosingValue, containingType);
    Set<ValueType> subtypes =
        surroundingType.getCases().knownSubtypesOf(supertypeElement.getQualifiedName().toString());
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (ValueType valueType : subtypes) {
      builder.add(valueType.typeAbstract().toString());
    }
    return builder.build();
  }

  public boolean isGenerateJdkOnly() {
    return containingType.isGenerateJdkOnly() && !isGuavaImmutableDeclared();
  }

  public boolean isGenerateOrdinalValueSet() {
    if (!isSetType()) {
      return false;
    }
    ensureTypeIntrospected();
    return generateOrdinalValueSet;
  }

/*
  public boolean isDocumentElement() {
    ensureTypeIntrospected();
    return containedTypeElement != null
        && containedTypeElement.getAnnotation(Mongo.Repository.class) != null;
  }
*/
  public boolean isArrayType() {
    return typeKind.isArray();
  }

  @Override
  protected void introspectType() {
    TypeMirror typeMirror = returnType;

    // Special case for primitive Optional, may become a pattern for specialized types
    if (typeKind.isOptionalSpecializedJdk()) {
      typeParameters = ImmutableList.of(optionalSpecializedType());
      // no delegation to introspect further
      return;
    }

    if (isContainerType()) {
      if (typeMirror.getKind() == TypeKind.DECLARED) {
        DeclaredType declaredType = (DeclaredType) typeMirror;

        List<String> typeParameters = Lists.newArrayList();

        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();

        if (!typeArguments.isEmpty()) {
          if (typeArguments.size() == 1) {
            final TypeMirror typeArgument = typeArguments.get(0);
            if (typeArgument instanceof DeclaredType) {
              typeMirror = typeArgument;
            }

            if (isSetType()) {
              generateOrdinalValueSet = new TypeIntrospectionBase() {
                @Override
                protected TypeMirror internalTypeMirror() {
                  return typeArgument;
                }
              }.isOrdinalValue();
            }
          }

          if (typeArguments.size() >= 1) {
            TypeMirror typeArgument = typeArguments.get(0);
            if (typeArgument instanceof DeclaredType) {
              TypeElement typeElement = (TypeElement) ((DeclaredType) typeArgument).asElement();
              hasEnumFirstTypeParameter = typeElement.getSuperclass().toString().startsWith(Enum.class.getName());
            }

            if (typeArguments.size() >= 2) {
              TypeMirror typeSecondArgument = typeArguments.get(1);

              if (typeSecondArgument instanceof DeclaredType) {
                TypeElement typeElement = (TypeElement) ((DeclaredType) typeSecondArgument).asElement();
                this.containedSecondaryTypeElement = typeElement;
              }
            }

            typeMirror = typeArgument;
          }

          typeParameters.addAll(Lists.transform(typeArguments, Functions.toStringFunction()));
        }

        this.typeParameters = ImmutableList.copyOf(typeParameters);
      }
    } else if (isArrayType()) {
      arrayComponent = ((ArrayType) typeMirror).getComponentType();
      typeMirror = arrayComponent;
    }

    if (typeMirror.getKind() == TypeKind.DECLARED) {
      TypeElement typeElement = (TypeElement) ((DeclaredType) typeMirror).asElement();

      this.containedTypeElement = typeElement;
    }

    intospectTypeMirror(typeMirror);
  }

  private String optionalSpecializedType() {
    switch (typeKind) {
    case OPTIONAL_INT_JDK:
      return "int";
    case OPTIONAL_LONG_JDK:
      return "long";
    case OPTIONAL_DOUBLE_JDK:
      return "double";
    default:
      throw new AssertionError();
    }
  }

  public AttributeTypeKind typeKind() {
    return typeKind;
  }

  private static boolean isRegularMarshalableType(String name, boolean couldBeWrapped) {
    return String.class.getName().equals(name)
        || (couldBeWrapped ? isPrimitiveOrWrapped(name) : isPrimitiveType(name));
  }

  public boolean isRequiresMarshalingAdapter() {
    return !isRegularMarshalableType(getElementType(), isContainerType());
  }

  public boolean isRequiresMarshalingSecondaryAdapter() {
    return isMapLike() && !isRegularMarshalableType(getSecondaryElementType(), true);
  }

  /**
   * Suitable for JavaDocs, intemediate name mangling and for Guava intergration.
   * @return the raw collection type
   */
  public String getRawCollectionType() {
    return typeKind.rawSimpleName();
  }

  public boolean isMultisetType() {
    return typeKind.isMultiset();
  }

  public String getRawMapType() {
    return typeKind.rawSimpleName();
  }

  public String getSecondaryElementType() {
    return secondTypeParameter();
  }

  public String getUnwrappedSecondaryElementType() {
    return unwrapType(secondTypeParameter());
  }

  public String getWrappedSecondaryElementType() {
    return wrapType(secondTypeParameter());
  }

  public String getUnwrapperOrRawSecondaryElementType() {
    return extractRawType(getWrappedSecondaryElementType());
  }

  public String getUnwrapperOrRawElementType() {
    return extractRawType(getWrappedElementType());
  }

  public boolean isNumberType() {
    TypeKind kind = returnType.getKind();
    return kind.isPrimitive()
        && kind != TypeKind.CHAR
        && kind != TypeKind.BOOLEAN;
  }

  public boolean isFloatType() {
    return isFloat() || isDouble();
  }

  public boolean isFloat() {
    return returnType.getKind() == TypeKind.FLOAT;
  }

  public boolean isDouble() {
    return returnType.getKind() == TypeKind.DOUBLE;
  }

  public boolean isNonRawElemementType() {
    return getElementType().indexOf('<') > 0;
  }

  public boolean isContainerType() {
    // TBD replace with typeKind.isContainerKind() ?
    return isCollectionType()
        || isOptionalType()
        || isMapType();
  }

  public String getWrapperType() {
    return isPrimitive()
        ? wrapType(rawTypeName)
        : returnTypeName;
  }

  public boolean isPrimitive() {
    return returnType.getKind().isPrimitive();
  }

  private int constructorOrder = Integer.MIN_VALUE;
  private AttributeTypeKind typeKind;

  int getConstructorParameterOrder() {
    if (constructorOrder < -1) {
      Optional<ParameterMirror> parameter = ParameterMirror.find(element);
      constructorOrder = parameter.isPresent()
          ? parameter.get().order()
          : -1;
    }
    return constructorOrder;
  }

  public boolean isConstructorParameter() {
    return getConstructorParameterOrder() >= 0;
  }

  public boolean isPrimitiveElement() {
    return isPrimitiveType(getUnwrappedElementType());
  }

  public boolean isAuxiliary() {
    return AuxiliaryMirror.isPresent(element);
  }

  private boolean isMarkedAsMongoId() {
    return IdMirror.isPresent(element);
  }

  boolean isIdAttribute() {
    return isMarkedAsMongoId()
        || ID_ATTRIBUTE_NAME.equals(getSerializedName());
  }

  /** Initialized Validates things that were not validated otherwise */
  void initAndValidate() {
    initRawTypeName();
    initTypeKind();
    initOrderKind();

    makeRegularAndNullableWithValidation();
    makeRegularIfContainsWildcards();
    makeRegularIfDefaultWithValidation();

    prohibitAuxiliaryOnAnnotationTypes();
  }

  private void initOrderKind() {
    if (typeKind.isSortedKind()) {
      checkOrderAnnotations();
      if (orderKind == OrderKind.NONE) {
        typeKind = AttributeTypeKind.REGULAR;
      }
    }
  }

  private void prohibitAuxiliaryOnAnnotationTypes() {
    if (containingType.isAnnotationType() && isAuxiliary()) {
      report()
          .annotationNamed(AuxiliaryMirror.simpleName())
          .error("@Value.Auxiliary cannot be used on annotation attribute to not violate annotation spec");
    }
  }

  private void initRawTypeName() {
    if (returnType.getKind() == TypeKind.DECLARED) {
      rawTypeName = ((TypeElement) ((DeclaredType) returnType).asElement()).getQualifiedName().toString();
    } else if (returnType.getKind().isPrimitive()) {
      rawTypeName = Ascii.toLowerCase(returnType.getKind().name());
    }
  }

  private void initTypeKind() {
    if (returnType.getKind() == TypeKind.ARRAY) {
      typeKind = AttributeTypeKind.ARRAY;
      ensureTypeIntrospected();
    } else {
      typeKind = AttributeTypeKind.forRawType(rawTypeName);
      ensureTypeIntrospected();
      typeKind = typeKind.havingEnumFirstTypeParameter(hasEnumFirstTypeParameter);
    }
  }

  private void makeRegularIfDefaultWithValidation() {
    if (isGenerateDefault && isContainerType()) {
      typeKind = AttributeTypeKind.REGULAR;
      report()
          .annotationNamed(DefaultMirror.simpleName())
          .warning("@Value.Default on a container attribute make it lose it's special treatment");
    }
  }

  private void makeRegularIfContainsWildcards() {
    // I hope this check isn't too simplistic
    if (returnTypeName.indexOf('?') >= 0) {
      typeKind = AttributeTypeKind.REGULAR;
    }
  }

  private void makeRegularAndNullableWithValidation() {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      TypeElement annotationElement = (TypeElement) annotation.getAnnotationType().asElement();
      if (annotationElement.getSimpleName().contentEquals(NULLABLE_SIMPLE_NAME)) {
        if (isPrimitive()) {
          report()
              .annotationNamed(NULLABLE_SIMPLE_NAME)
              .error("@Nullable could not be used with primitive type attibutes");
        } else {
          nullable = true;
          nullabilityPrefix = "@" + annotationElement.getQualifiedName() + " ";
          if (nullable) {
            typeKind = AttributeTypeKind.REGULAR;
          }
        }
      }
    }
    if (containingType.isAnnotationType() && nullable) {
      report()
          .annotationNamed(NULLABLE_SIMPLE_NAME)
          .error("@Nullable could not be used with annotation attribute, use default value");
    }
  }

  Reporter report() {
    return reporter.withElement(element);
  }

  @Override
  public String toString() {
    return "Attribute[" + name() + "]";
  }
}
