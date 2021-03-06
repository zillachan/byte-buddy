package net.bytebuddy.agent.builder;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.test.utility.ClassFileExtraction;
import net.bytebuddy.test.utility.ToolsJarRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.MethodRule;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class AgentBuilderDefaultApplicationTest {

    private static final String FOO = "foo", BAR = "bar", QUX = "qux";

    @Rule
    public MethodRule toolsJarRule = new ToolsJarRule();

    private ClassLoader classLoader;

    @Before
    public void setUp() throws Exception {
        Map<String, byte[]> types = new HashMap<String, byte[]>();
        types.put(Foo.class.getName(), ClassFileExtraction.extract(Foo.class));
        types.put(Bar.class.getName(), ClassFileExtraction.extract(Bar.class));
        types.put(Qux.class.getName(), ClassFileExtraction.extract(Qux.class));
        types.put(Baz.class.getName(), ClassFileExtraction.extract(Baz.class));
        classLoader = new ByteArrayClassLoader.ChildFirst(getClass().getClassLoader(),
                types,
                null,
                ByteArrayClassLoader.PersistenceHandler.MANIFEST);
    }

    @Test
    @ToolsJarRule.Enforce
    public void testAgentWithoutSelfInitialization() throws Exception {
        assertThat(ByteBuddyAgent.installOnOpenJDK(), instanceOf(Instrumentation.class));
        ClassFileTransformer classFileTransformer = new AgentBuilder.Default()
                .disableSelfInitialization()
                .rebase(isAnnotatedWith(ShouldRebase.class), ElementMatchers.is(classLoader)).transform(new FooTransformer())
                .installOnByteBuddyAgent();
        try {
            Class<?> type = classLoader.loadClass(Foo.class.getName());
            assertThat(type.getDeclaredMethod(FOO).invoke(type.newInstance()), is((Object) BAR));
        } finally {
            ByteBuddyAgent.getInstrumentation().removeTransformer(classFileTransformer);
        }
    }

    @Test
    @ToolsJarRule.Enforce
    public void testAgentSelfInitialization() throws Exception {
        assertThat(ByteBuddyAgent.installOnOpenJDK(), instanceOf(Instrumentation.class));
        ClassFileTransformer classFileTransformer = new AgentBuilder.Default()
                .rebase(isAnnotatedWith(ShouldRebase.class), ElementMatchers.is(classLoader)).transform(new BarTransformer())
                .installOnByteBuddyAgent();
        try {
            Class<?> type = classLoader.loadClass(Bar.class.getName());
            assertThat(type.getDeclaredMethod(FOO).invoke(type.newInstance()), is((Object) BAR));
        } finally {
            ByteBuddyAgent.getInstrumentation().removeTransformer(classFileTransformer);
        }
    }

    @Test
    @ToolsJarRule.Enforce
    public void testAgentSelfInitializationAuxiliaryTypes() throws Exception {
        assertThat(ByteBuddyAgent.installOnOpenJDK(), instanceOf(Instrumentation.class));
        ClassFileTransformer classFileTransformer = new AgentBuilder.Default()
                .rebase(isAnnotatedWith(ShouldRebase.class), ElementMatchers.is(classLoader)).transform(new QuxTransformer())
                .installOnByteBuddyAgent();
        try {
            Class<?> type = classLoader.loadClass(Qux.class.getName());
            assertThat(type.getDeclaredMethod(FOO).invoke(type.newInstance()), is((Object) (FOO + BAR)));
        } finally {
            ByteBuddyAgent.getInstrumentation().removeTransformer(classFileTransformer);
        }
    }

    @Test
    @ToolsJarRule.Enforce
    public void testAgentWithoutSelfInitializationWithNativeMethodPrefix() throws Exception {
        assertThat(ByteBuddyAgent.installOnOpenJDK(), instanceOf(Instrumentation.class));
        ClassFileTransformer classFileTransformer = new AgentBuilder.Default()
                .disableSelfInitialization()
                .withNativeMethodPrefix(QUX)
                .rebase(isAnnotatedWith(ShouldRebase.class), ElementMatchers.is(classLoader)).transform(new FooTransformer())
                .installOnByteBuddyAgent();
        try {
            Class<?> type = classLoader.loadClass(Baz.class.getName());
            assertThat(type.getDeclaredMethod(FOO).invoke(type.newInstance()), is((Object) BAR));
            assertThat(type.getDeclaredMethod(QUX + FOO), notNullValue(Method.class));
        } finally {
            ByteBuddyAgent.getInstrumentation().removeTransformer(classFileTransformer);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface ShouldRebase {

    }

    private static class FooTransformer implements AgentBuilder.Transformer {

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
            return builder.method(named(FOO)).intercept(FixedValue.value(BAR));
        }
    }

    @ShouldRebase
    public static class Foo {

        public String foo() {
            return FOO;
        }
    }

    @ShouldRebase
    public static class Baz {

        public String foo() {
            return FOO;
        }
    }

    public static class BarTransformer implements AgentBuilder.Transformer {

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
            try {
                return builder.method(named(FOO)).intercept(MethodDelegation.to(new Interceptor()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        public static class Interceptor {

            public String intercept() {
                return BAR;
            }
        }
    }

    @ShouldRebase
    public static class Bar {

        public String foo() {
            return FOO;
        }
    }

    public static class QuxTransformer implements AgentBuilder.Transformer {

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription) {
            try {
                return builder.method(named(FOO)).intercept(MethodDelegation.to(new Interceptor()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        public static class Interceptor {

            public String intercept(@SuperCall Callable<String> zuper) throws Exception {
                return zuper.call() + BAR;
            }
        }
    }

    @ShouldRebase
    public static class Qux {

        public String foo() {
            return FOO;
        }
    }
}
