package io.mockk.proxy;

import io.mockk.agent.MockKAgentException;
import io.mockk.agent.MockKAgentLogger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.Implementation.Context.Disabled.Factory;
import net.bytebuddy.matcher.ElementMatcher.Junction;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;

import static io.mockk.proxy.MockKInstrumentationLoader.LOADER;
import static java.lang.Integer.toHexString;
import static java.lang.System.identityHashCode;
import static java.util.Collections.synchronizedSet;
import static net.bytebuddy.dynamic.ClassFileLocator.Simple.of;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class MockKInstrumentation implements ClassFileTransformer {
    public static MockKAgentLogger log = MockKAgentLogger.NO_OP;

    public static MockKInstrumentation INSTANCE;

    public static void init(MockKAgentLogger log) {
        MockKInstrumentation.log = log;
        INSTANCE = new MockKInstrumentation();
    }

    private Instrumentation instrumentation;
    private final MockKProxyAdvice advice;
    private final MockKStaticProxyAdvice staticAdvice;

    private final Set<Class<?>> classesToTransform = synchronizedSet(new HashSet<Class<?>>());

    private ByteBuddy byteBuddy;


    MockKInstrumentation() {
        instrumentation = ByteBuddyAgent.install();
        if (instrumentation != null) {
            log.trace("Byte buddy agent installed");

            if (!LOADER.loadBootJar(instrumentation)) {
                log.trace("Failed to load mockk_boot.jar");
                instrumentation = null;
            } else {
                Class<?> dispatcher = LOADER.dispatcher();
                log.trace(dispatcher + " loaded at bootstrap classpath hashcode=" +
                        toHexString(identityHashCode(dispatcher)));
            }

        } else {
            log.trace("Can't install byte buddy agent");
        }


        if (instrumentation != null) {
            log.trace("Installing MockKInstrumentation transformer");
            instrumentation.addTransformer(this, true);
        }

        byteBuddy = new ByteBuddy()
                .with(TypeValidation.DISABLED)
                .with(Factory.INSTANCE);

        advice = new MockKProxyAdvice();
        staticAdvice = new MockKStaticProxyAdvice();

        Class<?> dispatcher = LOADER.dispatcher();
        try {
            Method setMethod = dispatcher.getMethod("set", long.class, dispatcher);
            setMethod.invoke(null, advice.getId(), advice);
            setMethod.invoke(null, staticAdvice.getId(), staticAdvice);
        } catch (Exception e) {
            throw new MockKAgentException("Failed to set advice", e);
        }
    }

    public boolean inject(List<Class<?>> classes) {
        if (instrumentation == null) {
            return false;
        }

        synchronized (classesToTransform) {
            classes.removeAll(classesToTransform);
            if (classes.isEmpty()) {
                return true;
            }

            log.trace("Injecting handler to " + classes);

            classesToTransform.addAll(classes);
        }


        Class[] cls = classes.toArray(new Class[classes.size()]);
        try {
            instrumentation.retransformClasses(cls);
            log.trace("Injected OK");
            return true;
        } catch (UnmodifiableClassException e) {
            return false;
        }
    }

    public void enable() {
        instrumentation = ByteBuddyAgent.getInstrumentation();
    }

    public void disable() {
        instrumentation = null;
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {
        if (!classesToTransform.contains(classBeingRedefined)) {
            return null;
        }

        try {
            DynamicType.Unloaded<?> unloaded = byteBuddy.redefine(classBeingRedefined, of(classBeingRedefined.getName(), classfileBuffer))
                    .visit(Advice.withCustomMapping()
                            .bind(MockKProxyAdviceId.class, advice.getId())
                            .to(MockKProxyAdvice.class).on(
                                    isVirtual()
                                            .and(not(isDefaultFinalizer()))
                                            .and(not(isPackagePrivateJavaMethods()))))
                    .visit(Advice.withCustomMapping()
                            .bind(MockKProxyAdviceId.class, staticAdvice.getId())
                            .to(MockKStaticProxyAdvice.class).on(
                                    ElementMatchers.<MethodDescription>isStatic().and(not(isTypeInitializer())).and(not(isConstructor()))
                                            .and(not(isPackagePrivateJavaMethods()))))
                    .make();

            return unloaded.getBytes();
        } catch (Throwable e) {
            log.trace(e, "Failed to tranform class");
            return null;
        }
    }

    private static Junction<MethodDescription> isPackagePrivateJavaMethods() {
        return isDeclaredBy(nameStartsWith("java.")).and(isPackagePrivate());
    }

}