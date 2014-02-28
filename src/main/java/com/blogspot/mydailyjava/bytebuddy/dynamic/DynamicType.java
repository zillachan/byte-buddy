package com.blogspot.mydailyjava.bytebuddy.dynamic;

import com.blogspot.mydailyjava.bytebuddy.ClassFormatVersion;
import com.blogspot.mydailyjava.bytebuddy.asm.ClassVisitorWrapper;
import com.blogspot.mydailyjava.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import com.blogspot.mydailyjava.bytebuddy.dynamic.loading.ClassLoaderByteArrayInjector;
import com.blogspot.mydailyjava.bytebuddy.dynamic.scaffold.FieldRegistry;
import com.blogspot.mydailyjava.bytebuddy.dynamic.scaffold.MethodRegistry;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.Instrumentation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.ModifierContributor;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.TypeInitializer;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.attribute.FieldAttributeAppender;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.attribute.MethodAttributeAppender;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.attribute.TypeAttributeAppender;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.MethodDescription;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.matcher.MethodMatcher;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.type.InstrumentedType;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.type.TypeDescription;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;

import static com.blogspot.mydailyjava.bytebuddy.instrumentation.method.matcher.MethodMatchers.named;

public interface DynamicType<T> {

    static interface ClassLoadingStrategy {

        static enum Default implements ClassLoadingStrategy {

            WRAPPER,
            INJECTION;

            @Override
            public Map<String, Class<?>> load(ClassLoader classLoader, Map<String, byte[]> types) {
                Map<String, Class<?>> loadedTypes = new HashMap<String, Class<?>>(types.size());
                switch (this) {
                    case WRAPPER:
                        classLoader = new ByteArrayClassLoader(classLoader, types);
                        for (String name : types.keySet()) {
                            try {
                                loadedTypes.put(name, classLoader.loadClass(name));
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException("Cannot load class " + name, e);
                            }
                        }
                        break;
                    case INJECTION:
                        ClassLoaderByteArrayInjector classLoaderByteArrayInjector = new ClassLoaderByteArrayInjector(classLoader);
                        for (Map.Entry<String, byte[]> entry : types.entrySet()) {
                            loadedTypes.put(entry.getKey(), classLoaderByteArrayInjector.inject(entry.getKey(), entry.getValue()));
                        }
                        break;
                    default:
                        throw new AssertionError();
                }
                return loadedTypes;
            }
        }

        Map<String, Class<?>> load(ClassLoader classLoader, Map<String, byte[]> types);
    }

    static interface Builder<T> {

        static abstract class AbstractBase<T> implements Builder<T> {

            protected static class MethodToken implements MethodRegistry.LatentMethodMatcher {

                private static class SignatureMatcher implements MethodMatcher {

                    private final TypeDescription returnType;
                    private final List<TypeDescription> parameterTypes;

                    private SignatureMatcher(TypeDescription returnType, List<TypeDescription> parameterTypes) {
                        this.returnType = returnType;
                        this.parameterTypes = parameterTypes;
                    }

                    @Override
                    public boolean matches(MethodDescription methodDescription) {
                        return methodDescription.getReturnType().equals(returnType)
                                && methodDescription.getParameterTypes().equals(parameterTypes);
                    }
                }

                protected final String name;
                protected final Class<?> returnType;
                protected final List<Class<?>> parameterTypes;
                protected final int modifiers;

                public MethodToken(String name, Class<?> returnType, List<Class<?>> parameterTypes, int modifiers) {
                    this.name = name;
                    this.returnType = returnType;
                    this.parameterTypes = Collections.unmodifiableList(new ArrayList<Class<?>>(parameterTypes));
                    this.modifiers = modifiers;
                }

                @Override
                public MethodMatcher manifest(TypeDescription instrumentedType) {
                    return named(name).and(new SignatureMatcher(resolveReturnType(instrumentedType),
                            resolveParameterTypes(instrumentedType)));
                }

                protected TypeDescription resolveReturnType(TypeDescription instrumentedType) {
                    return wrapAndConsiderSubstitution(returnType, instrumentedType);
                }

                protected List<TypeDescription> resolveParameterTypes(TypeDescription instrumentedType) {
                    List<TypeDescription> parameterTypes = new ArrayList<TypeDescription>(this.parameterTypes.size());
                    for (Class<?> parameterType : this.parameterTypes) {
                        parameterTypes.add(wrapAndConsiderSubstitution(parameterType, instrumentedType));
                    }
                    return parameterTypes;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && modifiers == ((MethodToken) other).modifiers
                            && name.equals(((MethodToken) other).name)
                            && parameterTypes.equals(((MethodToken) other).parameterTypes)
                            && returnType.equals(((MethodToken) other).returnType);
                }

                @Override
                public int hashCode() {
                    int result = name.hashCode();
                    result = 31 * result + returnType.hashCode();
                    result = 31 * result + parameterTypes.hashCode();
                    result = 31 * result + modifiers;
                    return result;
                }

                @Override
                public String toString() {
                    return "MethodToken{" +
                            "name='" + name + '\'' +
                            ", returnType=" + returnType +
                            ", parameterTypes=" + parameterTypes +
                            ", modifiers=" + modifiers + '}';
                }
            }

            protected static class FieldToken implements FieldRegistry.LatentFieldMatcher {

                protected final String name;
                protected final Class<?> fieldType;
                protected final int modifiers;

                public FieldToken(String name, Class<?> fieldType, int modifiers) {
                    this.name = name;
                    this.fieldType = fieldType;
                    this.modifiers = modifiers;
                }

                protected TypeDescription resolveFieldType(TypeDescription instrumentedType) {
                    return wrapAndConsiderSubstitution(fieldType, instrumentedType);
                }

                @Override
                public String getFieldName() {
                    return name;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && modifiers == ((FieldToken) other).modifiers
                            && fieldType.equals(((FieldToken) other).fieldType)
                            && name.equals(((FieldToken) other).name);
                }

                @Override
                public int hashCode() {
                    int result = name.hashCode();
                    result = 31 * result + fieldType.hashCode();
                    result = 31 * result + modifiers;
                    return result;
                }

                @Override
                public String toString() {
                    return "FieldToken{" +
                            "name='" + name + '\'' +
                            ", fieldType=" + fieldType +
                            ", modifiers=" + modifiers + '}';
                }
            }

            private static TypeDescription wrapAndConsiderSubstitution(Class<?> type, TypeDescription instrumentedType) {
                return type == TargetType.class ? instrumentedType : new TypeDescription.ForLoadedType(type);
            }

            protected static int resolveModifiers(int mask, ModifierContributor... modifierContributor) {
                int modifier = 0;
                for (ModifierContributor contributor : modifierContributor) {
                    modifier |= contributor.getMask();
                }
                if ((modifier & ~(mask | Opcodes.ACC_SYNTHETIC)) != 0) {
                    throw new IllegalArgumentException("Illegal modifiers " + Arrays.asList(modifierContributor));
                }
                return modifier;
            }

            protected abstract class AbstractDelegatingBuilder<T> implements Builder<T> {

                @Override
                public Builder<T> classFormatVersion(ClassFormatVersion classFormatVersion) {
                    return materialize().classFormatVersion(classFormatVersion);
                }

                @Override
                public Builder<T> implement(Class<?> interfaceType) {
                    return materialize().implement(interfaceType);
                }

                @Override
                public Builder<T> name(String name) {
                    return materialize().name(name);
                }

                @Override
                public Builder<T> modifier(ModifierContributor.ForType... modifier) {
                    return materialize().modifier(modifier);
                }

                @Override
                public Builder<T> ignoreMethods(MethodMatcher ignoredMethods) {
                    return materialize().ignoreMethods(ignoredMethods);
                }

                @Override
                public Builder<T> attribute(TypeAttributeAppender attributeAppender) {
                    return materialize().attribute(attributeAppender);
                }

                @Override
                public Builder<T> annotateType(Annotation annotation) {
                    return materialize().annotateType(annotation);
                }

                @Override
                public Builder<T> classVisitor(ClassVisitorWrapper classVisitorWrapper) {
                    return materialize().classVisitor(classVisitorWrapper);
                }

                @Override
                public FieldAnnotationTarget<T> defineField(String name,
                                                            Class<?> fieldType,
                                                            ModifierContributor.ForField... modifier) {
                    return materialize().defineField(name, fieldType, modifier);
                }

                @Override
                public MatchedMethodInterception<T> defineMethod(String name,
                                                                 Class<?> returnType,
                                                                 List<Class<?>> parameterTypes,
                                                                 ModifierContributor.ForMethod... modifier) {
                    return materialize().defineMethod(name, returnType, parameterTypes, modifier);
                }

                @Override
                public MatchedMethodInterception<T> defineConstructor(List<Class<?>> parameterTypes,
                                                                      ModifierContributor.ForMethod... modifier) {
                    return materialize().defineConstructor(parameterTypes, modifier);
                }

                @Override
                public MatchedMethodInterception<T> method(MethodMatcher methodMatcher) {
                    return materialize().method(methodMatcher);
                }

                @Override
                public Unloaded<T> make() {
                    return materialize().make();
                }

                protected abstract Builder<T> materialize();
            }

            protected final List<FieldToken> fieldTokens;
            protected final List<MethodToken> methodTokens;

            protected AbstractBase(List<FieldToken> fieldTokens, List<MethodToken> methodTokens) {
                this.fieldTokens = fieldTokens;
                this.methodTokens = methodTokens;
            }

            protected InstrumentedType applyRecordedMembersTo(InstrumentedType instrumentedType) {
                for (FieldToken fieldToken : fieldTokens) {
                    instrumentedType = instrumentedType.withField(fieldToken.name,
                            fieldToken.resolveFieldType(instrumentedType),
                            fieldToken.modifiers);
                }
                for (MethodToken methodToken : methodTokens) {
                    instrumentedType = instrumentedType.withMethod(methodToken.name,
                            methodToken.resolveReturnType(instrumentedType),
                            methodToken.resolveParameterTypes(instrumentedType),
                            methodToken.modifiers);
                }
                return instrumentedType;
            }
        }

        static interface MatchedMethodInterception<T> {

            MethodAnnotationTarget<T> intercept(Instrumentation instrumentation);

            MethodAnnotationTarget<T> withoutCode();
        }

        static interface MethodAnnotationTarget<T> extends Builder<T> {

            MethodAnnotationTarget<T> attribute(MethodAttributeAppender.Factory attributeAppenderFactory);

            MethodAnnotationTarget<T> annotateMethod(Annotation annotation);

            MethodAnnotationTarget<T> annotateParameter(int parameterIndex, Annotation annotation);
        }

        static interface FieldAnnotationTarget<T> extends Builder<T> {

            FieldAnnotationTarget<T> attribute(FieldAttributeAppender.Factory attributeAppenderFactory);

            FieldAnnotationTarget<T> annotateField(Annotation annotation);
        }

        Builder<T> classFormatVersion(ClassFormatVersion classFormatVersion);

        Builder<T> implement(Class<?> interfaceType);

        Builder<T> name(String name);

        Builder<T> modifier(ModifierContributor.ForType... modifier);

        Builder<T> ignoreMethods(MethodMatcher ignoredMethods);

        Builder<T> attribute(TypeAttributeAppender attributeAppender);

        Builder<T> annotateType(Annotation annotation);

        Builder<T> classVisitor(ClassVisitorWrapper classVisitorWrapper);

        FieldAnnotationTarget<T> defineField(String name,
                                             Class<?> fieldType,
                                             ModifierContributor.ForField... modifier);

        MatchedMethodInterception<T> defineMethod(String name,
                                                  Class<?> returnType,
                                                  List<Class<?>> parameterTypes,
                                                  ModifierContributor.ForMethod... modifier);

        MatchedMethodInterception<T> defineConstructor(List<Class<?>> parameterTypes,
                                                       ModifierContributor.ForMethod... modifier);

        MatchedMethodInterception<T> method(MethodMatcher methodMatcher);

        Unloaded<T> make();
    }

    static interface Loaded<T> extends DynamicType<T> {

        Class<? extends T> getMainType();

        Map<String, Class<?>> getAuxiliaryTypes();
    }

    static interface Unloaded<T> extends DynamicType<T> {

        Loaded<T> load(ClassLoader classLoader, ClassLoadingStrategy classLoadingStrategy);
    }

    static class Default<T> implements DynamicType<T> {

        public static class Unloaded<T> extends Default<T> implements DynamicType.Unloaded<T> {

            public Unloaded(String mainTypeName,
                            byte[] mainTypeByte,
                            TypeInitializer typeInitializer,
                            List<? extends DynamicType<?>> auxiliaryTypes) {
                super(mainTypeName, mainTypeByte, typeInitializer, auxiliaryTypes);
            }

            @Override
            public DynamicType.Loaded<T> load(ClassLoader classLoader, ClassLoadingStrategy classLoadingStrategy) {
                Map<String, byte[]> types = getRawAuxiliaryTypes();
                types.put(getMainTypeName(), getMainTypeByte());
                return new Default.Loaded<T>(mainTypeName,
                        mainTypeByte,
                        typeInitializer,
                        auxiliaryTypes,
                        initialize(classLoadingStrategy.load(classLoader, types)));
            }

            private Map<String, Class<?>> initialize(Map<String, Class<?>> types) {
                for (Map.Entry<String, TypeInitializer> entry : getTypeInitializers().entrySet()) {
                    entry.getValue().onLoad(types.get(entry.getKey()));
                }
                return types;
            }
        }

        public static class Loaded<T> extends Default<T> implements DynamicType.Loaded<T> {

            private final Map<String, Class<?>> types;

            public Loaded(String mainTypeName,
                          byte[] mainTypeByte,
                          TypeInitializer typeInitializer,
                          List<? extends DynamicType<?>> auxiliaryTypes,
                          Map<String, Class<?>> types) {
                super(mainTypeName, mainTypeByte, typeInitializer, auxiliaryTypes);
                this.types = types;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Class<? extends T> getMainType() {
                return (Class<? extends T>) types.get(getMainTypeName());
            }

            @Override
            public Map<String, Class<?>> getAuxiliaryTypes() {
                Map<String, Class<?>> auxiliaryTypes = new HashMap<String, Class<?>>(types);
                types.remove(getMainTypeName());
                return auxiliaryTypes;
            }
        }

        private static final String CLASS_FILE_EXTENSION = ".class";

        protected final String mainTypeName;
        protected final byte[] mainTypeByte;
        protected final TypeInitializer typeInitializer;
        protected final List<? extends DynamicType<?>> auxiliaryTypes;

        public Default(String mainTypeName,
                       byte[] mainTypeByte,
                       TypeInitializer typeInitializer,
                       List<? extends DynamicType<?>> auxiliaryTypes) {
            this.mainTypeName = mainTypeName;
            this.mainTypeByte = mainTypeByte;
            this.typeInitializer = typeInitializer;
            this.auxiliaryTypes = auxiliaryTypes;
        }

        @Override
        public String getMainTypeName() {
            return mainTypeName;
        }

        @Override
        public Map<String, TypeInitializer> getTypeInitializers() {
            Map<String, TypeInitializer> classLoadingCallbacks = new HashMap<String, TypeInitializer>();
            for (DynamicType<?> auxiliaryType : auxiliaryTypes) {
                classLoadingCallbacks.putAll(auxiliaryType.getTypeInitializers());
            }
            classLoadingCallbacks.put(getMainTypeName(), typeInitializer);
            return classLoadingCallbacks;
        }

        @Override
        public byte[] getMainTypeByte() {
            return mainTypeByte;
        }

        @Override
        public Map<String, byte[]> getRawAuxiliaryTypes() {
            Map<String, byte[]> auxiliaryTypes = new HashMap<String, byte[]>(this.auxiliaryTypes.size());
            for (DynamicType<?> auxiliaryType : this.auxiliaryTypes) {
                auxiliaryTypes.put(auxiliaryType.getMainTypeName(), auxiliaryType.getMainTypeByte());
                auxiliaryTypes.putAll(auxiliaryType.getRawAuxiliaryTypes());
            }
            return auxiliaryTypes;
        }

        @Override
        public File saveIn(File folder) throws IOException {
            File target = new File(folder, getMainTypeName().replace('.', File.separatorChar) + CLASS_FILE_EXTENSION);
            FileOutputStream fileOutputStream = new FileOutputStream(target);
            try {
                fileOutputStream.write(getMainTypeByte());
            } finally {
                fileOutputStream.close();
            }
            for (DynamicType<?> auxiliaryType : auxiliaryTypes) {
                auxiliaryType.saveIn(folder);
            }
            return target;
        }
    }

    String getMainTypeName();

    byte[] getMainTypeByte();

    Map<String, byte[]> getRawAuxiliaryTypes();

    Map<String, TypeInitializer> getTypeInitializers();

    File saveIn(File folder) throws IOException;
}