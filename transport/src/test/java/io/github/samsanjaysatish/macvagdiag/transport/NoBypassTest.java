package io.github.samsanjaysatish.macvagdiag.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.samsanjaysatish.macvagdiag.can.AdapterMode;
import io.github.samsanjaysatish.macvagdiag.can.Bitrate;
import io.github.samsanjaysatish.macvagdiag.can.CanAdapter;
import io.github.samsanjaysatish.macvagdiag.can.CanFrame;
import io.github.samsanjaysatish.macvagdiag.can.FakeTimeSource;
import io.github.samsanjaysatish.macvagdiag.can.SimulatedAdapter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Root CLAUDE.md rule 1 and 5, by reflection over the compiled main classes of this package: nothing outside the
 * package can reach a raw (unguarded) channel or the bus, and {@link ChannelFactory} only hands out guarded channels.
 */
class NoBypassTest {

    /** The whole public API of this project. Adding a public type must be a deliberate, reviewed change. */
    private static final Set<String> PUBLIC_API = Set.of(
            "ApplicationProtocol",
            "ChannelFactory",
            "DiagnosticChannel",
            "ForbiddenRequestException",
            "ModuleRoute", "ModuleRoute$IsoTp", "ModuleRoute$Tp20",
            "RequestAuditListener",
            "RequestGuard",
            "Response", "Response$Failed", "Response$Failed$Kind", "Response$Received", "Response$TimedOut",
            "TransportException", "TransportException$Reason");

    /** Classes that can touch the bus or send unguarded. */
    private static final List<Class<?>> RAW = List.of(
            RawChannel.class, Tp20Channel.class, IsoTpChannel.class, BusGate.class, GuardedChannel.class,
            DiagnosticCanIds.class);

    private static List<Class<?>> mainClasses;

    @BeforeAll
    static void loadMainClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(ChannelFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String pkg = ChannelFactory.class.getPackageName();
        String pkgPath = pkg.replace('.', '/') + "/";
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.list(root.resolve(pkgPath))) {
                files.map(f -> f.getFileName().toString()).forEach(names::add);
            }
        } else {
            // With java-test-fixtures, tests see the main classes as a jar.
            try (JarFile jar = new JarFile(root.toFile())) {
                jar.stream().map(JarEntry::getName)
                        .filter(n -> n.startsWith(pkgPath) && n.indexOf('/', pkgPath.length()) < 0)
                        .map(n -> n.substring(pkgPath.length()))
                        .forEach(names::add);
            }
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String file : names.stream().filter(n -> n.endsWith(".class")).sorted().toList()) {
            String name = file.substring(0, file.length() - ".class".length());
            classes.add(Class.forName(pkg + "." + name, false, NoBypassTest.class.getClassLoader()));
        }
        assertTrue(classes.size() > 10, "found the main classes in " + root);
        assertFalse(classes.stream().anyMatch(c -> c.getSimpleName().equals("FakeDiagnosticChannel")),
                "scanning main only, not the test fixtures");
        mainClasses = classes;
    }

    private static String simpleName(Class<?> c) {
        return c.getName().substring(c.getPackageName().length() + 1);
    }

    @Test
    @DisplayName("the public types are exactly the reviewed API")
    void publicApiIsExactlyTheReviewedSet() {
        Set<String> publicTypes = new TreeSet<>();
        for (Class<?> c : mainClasses) {
            if (Modifier.isPublic(c.getModifiers()) && !c.isAnonymousClass() && !c.isSynthetic()) {
                publicTypes.add(simpleName(c));
            }
        }
        assertEquals(new TreeSet<>(PUBLIC_API), publicTypes);
    }

    @Test
    @DisplayName("raw channels, the bus gate and the guarded wrapper are package-private and final")
    void rawClassesArePackagePrivate() {
        for (Class<?> c : RAW) {
            int m = c.getModifiers();
            assertFalse(Modifier.isPublic(m) || Modifier.isProtected(m) || Modifier.isPrivate(m),
                    c.getSimpleName() + " must be package-private");
            if (!c.isInterface()) {
                assertTrue(Modifier.isFinal(m), c.getSimpleName() + " must be final");
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    assertFalse(Modifier.isPublic(k.getModifiers()) || Modifier.isProtected(k.getModifiers()),
                            "constructor " + k);
                }
            }
        }
    }

    @Test
    @DisplayName("no public method or constructor in the API exposes a raw channel, the bus gate, CanFrame or CanAdapter")
    void publicApiDoesNotLeakRawTypes() {
        Set<Class<?>> forbidden = Set.of(RawChannel.class, Tp20Channel.class, IsoTpChannel.class, BusGate.class,
                GuardedChannel.class, CanFrame.class);
        for (Class<?> c : mainClasses) {
            if (!Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!(Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))) {
                    continue;
                }
                assertFalse(forbidden.contains(m.getReturnType()) || m.getReturnType() == CanAdapter.class,
                        "returns a raw type: " + m);
                for (Class<?> p : m.getParameterTypes()) {
                    assertFalse(forbidden.contains(p), "takes a raw type: " + m);
                    if (p == CanAdapter.class) {
                        // The one allowed place: app startup hands the adapter to the factory.
                        assertEquals(ChannelFactory.class, c, "only ChannelFactory.create takes the adapter: " + m);
                        assertEquals("create", m.getName());
                    }
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (Modifier.isPublic(k.getModifiers())) {
                    for (Class<?> p : k.getParameterTypes()) {
                        assertFalse(forbidden.contains(p) || p == CanAdapter.class, "public constructor " + k);
                    }
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers())) {
                    assertFalse(forbidden.contains(f.getType()) || f.getType() == CanAdapter.class, "public field " + f);
                }
            }
        }
    }

    @Test
    @DisplayName("GuardedChannel is the only DiagnosticChannel implementation in main")
    void onlyGuardedChannelImplementsDiagnosticChannel() {
        List<Class<?>> impls = mainClasses.stream()
                .filter(c -> !c.isInterface() && DiagnosticChannel.class.isAssignableFrom(c))
                .toList();
        assertEquals(List.of(GuardedChannel.class), impls);
        assertFalse(DiagnosticChannel.class.isAssignableFrom(Tp20Channel.class));
        assertFalse(DiagnosticChannel.class.isAssignableFrom(IsoTpChannel.class));
    }

    @Test
    @DisplayName("raw channels hold no CanAdapter: their only way to the bus is the BusGate")
    void rawChannelsOnlyReachTheBusThroughTheGate() {
        for (Class<?> c : List.of(Tp20Channel.class, IsoTpChannel.class, GuardedChannel.class)) {
            for (Field f : c.getDeclaredFields()) {
                assertFalse(CanAdapter.class.isAssignableFrom(f.getType()), c.getSimpleName() + "." + f.getName());
            }
        }
        for (Field f : GuardedChannel.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(f.getModifiers()), "GuardedChannel." + f.getName() + " must be private");
        }
        for (Method m : GuardedChannel.class.getDeclaredMethods()) {
            if (!Modifier.isPrivate(m.getModifiers()) && !m.isSynthetic()) {
                assertFalse(RawChannel.class.isAssignableFrom(m.getReturnType()), "exposes the raw channel: " + m);
            }
        }
    }

    @Test
    @DisplayName("ChannelFactory: final, private constructor, open() returns a GuardedChannel for every route type")
    void factoryOnlyReturnsGuardedChannels() throws Exception {
        assertTrue(Modifier.isFinal(ChannelFactory.class.getModifiers()));
        for (Constructor<?> k : ChannelFactory.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(k.getModifiers()));
        }
        for (Method m : ChannelFactory.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                assertEquals(DiagnosticChannel.class, m.getReturnType(), "public method " + m);
            }
        }
        // One route per permitted subtype of the sealed ModuleRoute: a new route type must be added here.
        Class<?>[] routeTypes = ModuleRoute.class.getPermittedSubclasses();
        assertEquals(Set.of(ModuleRoute.Tp20.class, ModuleRoute.IsoTp.class), Set.of(routeTypes));

        FakeTimeSource time = FakeTimeSource.autoAdvancing();
        try (SimulatedAdapter tp20 = new SimulatedAdapter(time, FakeEcus.tp20())) {
            tp20.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
            assertInstanceOf(GuardedChannel.class, ChannelFactory.create(tp20, time).open(new ModuleRoute.Tp20(0x01)));
        }
        try (SimulatedAdapter iso = new SimulatedAdapter(time, FakeEcus.isoTp())) {
            iso.open(Bitrate.KBIT_500, AdapterMode.NORMAL);
            assertInstanceOf(GuardedChannel.class, ChannelFactory.create(iso, time)
                    .open(new ModuleRoute.IsoTp(0x7E0, 0x7E8, ApplicationProtocol.UDS)));
        }
    }

    @Test
    @DisplayName("DiagnosticChannel has no method that takes frames or a CAN ID")
    void diagnosticChannelHasNoRawSend() {
        for (Method m : DiagnosticChannel.class.getMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(p == CanFrame.class || p == CanAdapter.class, m.toString());
            }
        }
        assertEquals(Set.of("route", "protocol", "request", "awaitResponse", "keepAlive", "isOpen", "close"),
                Set.copyOf(Stream.of(DiagnosticChannel.class.getDeclaredMethods()).map(Method::getName).toList()));
    }
}
