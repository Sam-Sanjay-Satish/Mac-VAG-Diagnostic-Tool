package io.github.samsanjaysatish.macvagdiag.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Front ends see scan types only. Walks every type reachable from {@link ScanService}'s public signatures (return,
 * parameter and exception types, generic arguments, record components, public members of each scan type found)
 * and fails on any diag, transport or can-core type. {@link ScanServiceFactory} is the one public class allowed
 * to take can-core's {@code CanAdapter} and {@code TimeSource} (app startup).
 */
class PublicApiTest {

    private static final String ROOT = "io.github.samsanjaysatish.macvagdiag.";
    private static final String SCAN = ROOT + "scan";
    private static final Set<String> BELOW = Set.of(ROOT + "diag", ROOT + "transport", ROOT + "can");

    @Test
    @DisplayName("ScanService's method set is pinned: no send, raw, clear or write methods can slip in")
    void scanServiceMethodsArePinned() {
        Set<String> methods = new TreeSet<>();
        for (Method m : ScanService.class.getMethods()) {
            if (m.getDeclaringClass() == ScanService.class) {
                methods.add(m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList());
            }
        }
        assertEquals(new TreeSet<>(List.of("autoScan[Caller, ScanProgressListener]", "close[]",
                "findAutoScan[String]", "isDemo[]", "knownModules[]", "listModules[Caller]",
                "readFaultCodes[Caller, ModuleAddress]", "recordToolCall[ToolCallRecord]", "sessionStatus[Caller]",
                "startAutoScan[Caller, ScanProgressListener]", "vehicleInfo[Caller]")), methods);
        for (Method m : ScanService.class.getMethods()) {
            assertFalse(Arrays.asList(m.getParameterTypes()).contains(byte[].class), m.toString());
        }
    }

    @Test
    @DisplayName("nothing reachable from ScanService's public API is a diag, transport or can-core type")
    void scanServiceExposesScanTypesOnly() {
        List<String> leaks = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(ScanService.class));
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (!seen.add(c)) {
                continue;
            }
            for (Type t : signatureTypes(c)) {
                for (Class<?> ref : classesIn(t)) {
                    String pkg = ref.getPackageName();
                    if (BELOW.contains(pkg)) {
                        leaks.add(c.getSimpleName() + " exposes " + ref.getName());
                    } else if (pkg.equals(SCAN) && !seen.contains(ref)) {
                        todo.add(ref);
                    }
                }
            }
        }
        assertEquals(List.of(), leaks);
        assertTrue(seen.size() > 15, "walked " + seen);
        assertTrue(seen.contains(FaultCode.class) && seen.contains(AutoScanJob.class) && seen.contains(ScanEvent.Finished.class));
    }

    @Test
    @DisplayName("every public scan class: only ScanServiceFactory mentions can-core, and only CanAdapter/TimeSource")
    void publicClassesExposeNothingBelow() throws IOException, ClassNotFoundException {
        List<String> leaks = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                Class<?> c = Class.forName(SCAN + "." + p.getFileName().toString().replace(".java", ""));
                List<Class<?>> all = new ArrayList<>(List.of(c));
                all.addAll(Arrays.asList(c.getDeclaredClasses()));
                for (Class<?> type : all) {
                    if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isPublic(c.getModifiers())) {
                        continue;
                    }
                    for (Type t : signatureTypes(type)) {
                        for (Class<?> ref : classesIn(t)) {
                            if (!BELOW.contains(ref.getPackageName())) {
                                continue;
                            }
                            boolean allowed = type == ScanServiceFactory.class
                                    && (ref.getSimpleName().equals("CanAdapter") || ref.getSimpleName().equals("TimeSource"));
                            if (!allowed) {
                                leaks.add(type.getName() + " exposes " + ref.getName());
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), leaks);
    }

    @Test
    @DisplayName("the seam and the internals are package-private")
    void internalsArePackagePrivate() {
        for (Class<?> c : List.of(ModuleConnector.class, RequestObserver.class, ScanEngine.class, ModuleSession.class,
                RequestQueue.class, DefaultScanService.class, ChannelConnector.class, DemoCar.class, AuditLog.class,
                RequestBudget.class, DiagMapper.class, DiagOp.class, Json.class, ScanContext.class)) {
            assertFalse(Modifier.isPublic(c.getModifiers()), c + " must not be public");
        }
        for (Constructor<?> k : ScanServiceFactory.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(k.getModifiers()));
        }
        assertEquals(0, ModuleAddress.class.getConstructors().length, "addresses only come from the known set");
    }

    /** Types in the public signatures of {@code c}: methods, constructors, fields, record components, supertypes. */
    private static List<Type> signatureTypes(Class<?> c) {
        List<Type> out = new ArrayList<>();
        out.add(c.getGenericSuperclass());
        out.addAll(Arrays.asList(c.getGenericInterfaces()));
        List<Executable> members = new ArrayList<>(Arrays.asList(c.getMethods()));
        members.addAll(Arrays.asList(c.getConstructors()));
        for (Executable e : members) {
            if (e.getDeclaringClass().getPackageName().startsWith("java.")) {
                continue;
            }
            if (e instanceof Method m) {
                out.add(m.getGenericReturnType());
            }
            out.addAll(Arrays.asList(e.getGenericParameterTypes()));
            out.addAll(Arrays.asList(e.getGenericExceptionTypes()));
        }
        for (Field f : c.getFields()) {
            out.add(f.getGenericType());
        }
        if (c.isRecord()) {
            for (RecordComponent rc : c.getRecordComponents()) {
                out.add(rc.getGenericType());
            }
        }
        for (Class<?> nested : c.getClasses()) {
            out.add(nested);
        }
        if (c.isSealed()) {
            out.addAll(Arrays.asList(c.getPermittedSubclasses()));
        }
        return out;
    }

    private static List<Class<?>> classesIn(Type t) {
        List<Class<?>> out = new ArrayList<>();
        switch (t) {
            case null -> {
            }
            case Class<?> c -> out.add(c.isArray() ? c.getComponentType() : c);
            case ParameterizedType p -> {
                out.addAll(classesIn(p.getRawType()));
                for (Type a : p.getActualTypeArguments()) {
                    out.addAll(classesIn(a));
                }
            }
            case GenericArrayType g -> out.addAll(classesIn(g.getGenericComponentType()));
            case WildcardType w -> {
                for (Type b : w.getUpperBounds()) {
                    out.addAll(classesIn(b));
                }
                for (Type b : w.getLowerBounds()) {
                    out.addAll(classesIn(b));
                }
            }
            case TypeVariable<?> v -> {
                for (Type b : v.getBounds()) {
                    out.addAll(classesIn(b));
                }
            }
            default -> {
            }
        }
        return out;
    }
}
