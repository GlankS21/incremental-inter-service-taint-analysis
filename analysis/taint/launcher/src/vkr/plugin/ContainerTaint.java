package vkr.plugin;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Object-level taint for JDK containers.
 *
 * <p>A Tai-e transfer {@code from: 0, to: base} taints the base <em>variable</em> only, so an
 * alias of the same collection in another method never sees it, e.g. a map filled inside a
 * {@code forEach} lambda and read by the enclosing method. Here a write ({@code add}, {@code put},
 * {@code merge}, ...) attaches the taint to every container object the base points to, and a read
 * ({@code iterator}, {@code stream}, {@code keySet}, {@code get}, ...) on any alias of that object
 * returns it.
 *
 * <p>Map values are kept apart by key when the key is a string constant: {@code put("a", v)} stores
 * {@code v} under "a", and {@code get("b")} returns only what was stored under "b" or under a key that
 * is not a constant. Every other read ({@code values}, {@code entrySet}, {@code get(k)} with a
 * non-constant {@code k}, ...) returns all of it. Keys themselves are stored under no key. List
 * elements are kept apart the same way by position, for the lists {@link LocalLists} handles.
 */
public class ContainerTaint implements Plugin {

    /** method name -> indexes of the arguments stored into the container */
    private static final Map<String, int[]> COLLECTION_WRITES = Map.of(
            "add", new int[]{-1}, // last argument: add(e) or add(i, e)
            "addLast", new int[]{0}, "addFirst", new int[]{0},
            "set", new int[]{1}, "offer", new int[]{0}, "push", new int[]{0});

    private static final Map<String, int[]> MAP_WRITES = Map.of(
            "put", new int[]{0, 1}, "putIfAbsent", new int[]{0, 1}, "merge", new int[]{0, 1});

    private static final Set<String> COLLECTION_READS = Set.of(
            "iterator", "listIterator", "stream", "parallelStream", "get", "getFirst", "getLast",
            "peek", "poll", "pop", "element", "toArray");

    private static final Set<String> MAP_READS = Set.of(
            "keySet", "values", "entrySet", "get", "getOrDefault", "sequencedKeySet", "sequencedValues");

    /** map reads whose first argument is the key */
    private static final Set<String> KEYED_READS = Set.of("get", "getOrDefault");

    /** a write of {@code arg} into what {@code base} points to, under {@code key} (null: no constant key) */
    private record Write(CSVar base, CSVar arg, String key) {
    }

    /** a read into {@code result}; {@code key} null reads everything, else that key and the unknown ones */
    private record Read(CSVar result, String key) {

        boolean sees(String stored) {
            return key == null || stored == null || key.equals(stored);
        }
    }

    private Solver solver;

    private CSManager csManager;

    private ClassHierarchy hierarchy;

    private JClass collectionClass;

    private JClass mapClass;

    /** taint objects attached to each container object, by constant key (null: no constant key) */
    private final Map<CSObj, Map<String, Set<CSObj>>> contents = new HashMap<>();

    /** reads on each container object */
    private final Map<CSObj, Set<Read>> readers = new HashMap<>();

    private final Map<CSVar, List<Write>> writesByArg = new HashMap<>();

    private final Map<CSVar, List<Write>> writesByBase = new HashMap<>();

    private final Map<CSVar, List<Read>> readsByBase = new HashMap<>();

    /** keys of the list adds and gets of each method, see {@link LocalLists} */
    private final Map<JMethod, Map<Invoke, String>> listKeys = new HashMap<>();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
        this.hierarchy = solver.getHierarchy();
        this.collectionClass = hierarchy.getJREClass("java.util.Collection");
        this.mapClass = hierarchy.getJREClass("java.util.Map");
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        // only containers handled by application code: library internals would cost a lot
        // and the summary only needs flows between application call sites
        if (method.isAbstract() || method.isNative() || !method.getDeclaringClass().isApplication()) {
            return;
        }
        Context ctx = csMethod.getContext();
        Map<Invoke, String> lists = listKeys.computeIfAbsent(method, m -> LocalLists.keys(m.getIR()));
        method.getIR().invokes(false).forEach(invoke -> register(ctx, invoke, lists));
    }

    private void register(Context ctx, Invoke invoke, Map<Invoke, String> lists) {
        if (!(invoke.getInvokeExp() instanceof InvokeInstanceExp exp)) {
            return;
        }
        MethodRef ref = invoke.getMethodRef();
        JClass cls = ref.getDeclaringClass();
        boolean isMap = hierarchy.isSubclass(mapClass, cls);
        if (!isMap && !hierarchy.isSubclass(collectionClass, cls)) {
            return;
        }
        String name = ref.getName();
        CSVar base = csManager.getCSVar(ctx, exp.getBase());
        int[] writes = (isMap ? MAP_WRITES : COLLECTION_WRITES).get(name);
        if (writes != null) {
            for (int i : writes) {
                int index = i < 0 ? exp.getArgCount() - 1 : i;
                if (index >= 0 && index < exp.getArgCount()) {
                    // map values (index 1) go under their key, list elements under their position
                    String key = isMap ? (index == 1 ? constantKey(exp) : null) : lists.get(invoke);
                    Write w = new Write(base, csManager.getCSVar(ctx, exp.getArg(index)), key);
                    writesByArg.computeIfAbsent(w.arg(), k -> new ArrayList<>()).add(w);
                    writesByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(w);
                    attach(solver.getPointsToSetOf(base), solver.getPointsToSetOf(w.arg()), key);
                }
            }
        }
        Var result = invoke.getResult();
        if (result != null && (isMap ? MAP_READS : COLLECTION_READS).contains(name)) {
            String key = isMap ? (KEYED_READS.contains(name) ? constantKey(exp) : null) : lists.get(invoke);
            Read r = new Read(csManager.getCSVar(ctx, result), key);
            readsByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(r);
            subscribe(solver.getPointsToSetOf(base), r);
        }
    }

    /** the first argument when it is a string constant, else null */
    private static String constantKey(InvokeInstanceExp exp) {
        if (exp.getArgCount() == 0) {
            return null;
        }
        Var key = exp.getArg(0);
        return key.isConst() && key.getConstValue() instanceof StringLiteral lit ? lit.getString() : null;
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        for (Write w : writesByArg.getOrDefault(csVar, List.of())) {
            attach(solver.getPointsToSetOf(w.base()), pts, w.key());
        }
        for (Write w : writesByBase.getOrDefault(csVar, List.of())) {
            attach(pts, solver.getPointsToSetOf(w.arg()), w.key());
        }
        for (Read r : readsByBase.getOrDefault(csVar, List.of())) {
            subscribe(pts, r);
        }
    }

    private void attach(PointsToSet containers, PointsToSet values, String key) {
        if (containers == null || values == null) {
            return;
        }
        List<CSObj> taints = values.objects().filter(ContainerTaint::isTaint).toList();
        if (taints.isEmpty()) {
            return;
        }
        for (CSObj container : containers) {
            if (isTaint(container) || !isTracked(container)) {
                continue; // tainted containers are handled by the YAML transfers
            }
            Set<CSObj> content = contents.computeIfAbsent(container, k -> new HashMap<>())
                    .computeIfAbsent(key, k -> new HashSet<>());
            PointsToSet added = solver.makePointsToSet();
            for (CSObj t : taints) {
                if (content.add(t)) {
                    added.addObject(t);
                }
            }
            if (!added.isEmpty()) {
                for (Read r : readers.getOrDefault(container, Set.of())) {
                    if (r.sees(key)) {
                        solver.addPointsTo(r.result(), added);
                    }
                }
            }
        }
    }

    private void subscribe(PointsToSet containers, Read r) {
        if (containers == null) {
            return;
        }
        for (CSObj container : containers) {
            if (isTaint(container) || !isTracked(container) || !readers.computeIfAbsent(container, k -> new HashSet<>()).add(r)) {
                continue;
            }
            PointsToSet pts = solver.makePointsToSet();
            contents.getOrDefault(container, Map.of()).forEach((key, content) -> {
                if (r.sees(key)) {
                    content.forEach(pts::addObject);
                }
            });
            if (!pts.isEmpty()) {
                solver.addPointsTo(r.result(), pts);
            }
        }
    }

    /**
     * Only containers allocated in application code (and the per-call-site Spring Data results).
     * A container allocated inside a library (Stream.toList, Collectors.toMap, ...) or a shared
     * factory is one abstract object for the whole program under this heap abstraction, so taint
     * attached to it would reach every reader in the service (observed: ApiResponse.getData()).
     */
    private static boolean isTracked(CSObj obj) {
        if (obj.getObject() instanceof MockObj mock) {
            return mock.getDescriptor().string().startsWith("SpringData");
        }
        return obj.getObject().getContainerMethod()
                .map(m -> m.getDeclaringClass().isApplication())
                .orElse(false);
    }

    /** the pointer filter of the barriers: no taint object passes */
    static final Predicate<CSObj> NOT_TAINT = o -> !isTaint(o);

    /** keeps taint objects out of a method in its context: from {@code this} and every parameter */
    static void keepTaintOut(Solver solver, CSMethod csMethod) {
        CSManager csManager = solver.getCSManager();
        Context ctx = csMethod.getContext();
        IR ir = csMethod.getMethod().getIR();
        if (ir.getThis() != null) {
            solver.addPointerFilter(csManager.getCSVar(ctx, ir.getThis()), NOT_TAINT);
        }
        for (Var param : ir.getParams()) {
            solver.addPointerFilter(csManager.getCSVar(ctx, param), NOT_TAINT);
        }
    }

    static boolean isTaint(CSObj obj) {
        return obj.getObject() instanceof MockObj mock
                && "TaintObj".equals(mock.getDescriptor().string());
    }
}
