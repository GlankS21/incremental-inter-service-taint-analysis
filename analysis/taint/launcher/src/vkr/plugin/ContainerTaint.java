package vkr.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.MergedObj;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.plugin.taint.IndexRef;
import pascal.taie.analysis.pta.plugin.taint.Sink;
import pascal.taie.analysis.pta.plugin.taint.TaintConfig;
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
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeSystem;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Object-level taint for JDK containers and string builders.
 *
 * <p>A Tai-e transfer {@code from: 0, to: base} taints the base <em>variable</em> only, so an
 * alias of the same collection in another method never sees it, e.g. a map filled inside a
 * {@code forEach} lambda and read by the enclosing method. Here a write ({@code add}, {@code put},
 * {@code merge}, {@code append}, ...) attaches the taint to every container object the base points
 * to, and a read ({@code get}, {@code iterator}, {@code toString}, ...) on any alias of that object
 * returns it. A {@code StringBuilder}/{@code StringBuffer} is a container of what is appended to it,
 * so text appended by a helper that is passed the builder reaches the caller's {@code toString}.
 *
 * <p>Map values are kept apart by key when the key is a string constant: {@code put("a", v)} stores
 * {@code v} under "a", and {@code get("b")} returns only what was stored under "b" or under a key that
 * is not a constant. Keys are stored apart from the values: {@code get}, {@code values} return values,
 * {@code keySet} the keys, {@code entrySet} and {@code toString} both. List elements are kept apart
 * by position, for the lists {@link LocalLists} handles.
 *
 * <p>Iteration: an iterator or a view ({@code keySet}, {@code values}, {@code entrySet}) also receives
 * <em>carriers</em>, mock objects that are not taint objects and hold the element. {@code next()} on a
 * carrier returns the element itself, typed as it was stored, so a cast to that type
 * ({@code (String) it.next()}) keeps it; the YAML transfers alone return a taint of type Object, which
 * every such cast drops. An entry of {@code entrySet} is a value carrier or a key carrier:
 * {@code getValue()} returns only values, {@code getKey()} only keys.
 *
 * <p>Sinks on contents: a container or builder passed to a sink of the JDK ({@code java.*},
 * {@code javax.*}, {@code jakarta.*}: {@code new ProcessBuilder(list)}, {@code println(list)}) is a flow
 * of each taint it holds, reported as Tai-e reports its own flows. Channel sinks are not given this:
 * the summaries of the services keep their DTO-level model.
 */
public class ContainerTaint implements Plugin {

    private static final Logger logger = LogManager.getLogger(ContainerTaint.class);

    /** method name -> indexes of the arguments stored into the container */
    private static final Map<String, int[]> COLLECTION_WRITES = Map.of(
            "add", new int[]{-1}, // last argument: add(e) or add(i, e)
            "addLast", new int[]{0}, "addFirst", new int[]{0},
            "set", new int[]{1}, "offer", new int[]{0}, "push", new int[]{0});

    private static final Map<String, int[]> MAP_WRITES = Map.of(
            "put", new int[]{0, 1}, "putIfAbsent", new int[]{0, 1}, "merge", new int[]{0, 1});

    /** what a builder stores: its initial text, append(x), insert(i, x), replace(start, end, x) */
    private static final Map<String, int[]> BUILDER_WRITES = Map.of(
            "<init>", new int[]{0}, "append", new int[]{0}, "insert", new int[]{1}, "replace", new int[]{2});

    /** reads returning an element or a view of them; remove(Object) returns a boolean, see register */
    private static final Set<String> COLLECTION_READS = Set.of(
            "iterator", "listIterator", "stream", "parallelStream", "get", "getFirst", "getLast",
            "peek", "poll", "pop", "element", "toArray", "remove", "removeFirst", "removeLast",
            "pollFirst", "pollLast", "peekFirst", "peekLast", "take", "elementAt", "firstElement",
            "lastElement", "toString");

    private static final Set<String> COLLECTION_ITERATORS = Set.of("iterator", "listIterator");

    private static final Set<String> BUILDER_READS = Set.of("toString", "substring", "subSequence");

    /** stored key of a map's keys: never a constant key of a value */
    static final String KEYS = "\0keys";

    /** which of the stored taints a read returns: the values, the keys of a map, or both */
    enum View { VALUES, KEYS, ALL }

    /** what a read puts into its result: the taints themselves, or carriers of them */
    enum Shape { RAW, ITERATOR, VIEW, ENTRIES }

    /** a write of {@code arg} into what {@code base} points to, under {@code key} (null: no constant key) */
    private record Write(CSVar base, CSVar arg, String key) {
    }

    /**
     * a read into {@code result}; {@code key} null reads every value, else that key and the values of
     * no constant key
     */
    record Read(CSVar result, String key, View view, Shape shape) {

        Read(CSVar result, String key) {
            this(result, key, View.VALUES, Shape.RAW);
        }

        boolean sees(String stored) {
            if (KEYS.equals(stored)) {
                return view != View.VALUES;
            }
            return view != View.KEYS && (key == null || stored == null || key.equals(stored));
        }
    }

    /** a carrier: the element of an iterator or a view, a value or a key of an entry */
    private record Carried(String kind, Obj inner) {
    }

    /** a call on a carrier: {@code view.iterator()}, {@code it.next()}, {@code entry.getValue()} */
    private record Unwrap(CSVar result, String kind) {
    }

    private static final Descriptor CARRIER_DESC = () -> "ContainerCarrier";

    private Solver solver;

    private CSManager csManager;

    private ClassHierarchy hierarchy;

    private JClass collectionClass;

    private JClass mapClass;

    private JClass builderClass;

    private JClass iterableClass;

    private JClass iteratorClass;

    private JClass entryClass;

    /** taint objects attached to each container object, by constant key (null: no constant key) */
    private final Map<CSObj, Map<String, Set<CSObj>>> contents = new HashMap<>();

    /** reads on each container object */
    private final Map<CSObj, Set<Read>> readers = new HashMap<>();

    private final Map<CSVar, List<Write>> writesByArg = new HashMap<>();

    private final Map<CSVar, List<Write>> writesByBase = new HashMap<>();

    private final Map<CSVar, List<Read>> readsByBase = new HashMap<>();

    private final Map<CSVar, List<Unwrap>> unwrapsByBase = new HashMap<>();

    /** carrier -> what it carries */
    private final Map<CSObj, CSObj> carried = new HashMap<>();

    /** keys of the list adds and gets of each method, see {@link LocalLists} */
    private final Map<JMethod, Map<Invoke, String>> listKeys = new HashMap<>();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
        this.hierarchy = solver.getHierarchy();
        this.collectionClass = hierarchy.getJREClass("java.util.Collection");
        this.mapClass = hierarchy.getJREClass("java.util.Map");
        this.builderClass = hierarchy.getJREClass("java.lang.AbstractStringBuilder");
        this.iterableClass = hierarchy.getJREClass("java.lang.Iterable");
        this.iteratorClass = hierarchy.getJREClass("java.util.Iterator");
        this.entryClass = hierarchy.getJREClass("java.util.Map$Entry");
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

    private boolean isSub(JClass parent, JClass cls) {
        return parent != null && cls != null && hierarchy.isSubclass(parent, cls);
    }

    private void register(Context ctx, Invoke invoke, Map<Invoke, String> lists) {
        if (!(invoke.getInvokeExp() instanceof InvokeInstanceExp exp)) {
            return;
        }
        MethodRef ref = invoke.getMethodRef();
        JClass cls = ref.getDeclaringClass();
        String name = ref.getName();
        CSVar base = csManager.getCSVar(ctx, exp.getBase());
        Var result = invoke.getResult();
        boolean refResult = result != null && !(result.getType() instanceof PrimitiveType);
        if (refResult) {
            registerUnwrap(cls, name, base, csManager.getCSVar(ctx, result));
        }
        boolean isMap = isSub(mapClass, cls);
        boolean isBuilder = !isMap && isSub(builderClass, cls);
        if (!isMap && !isBuilder && !isSub(collectionClass, cls)) {
            return;
        }
        int[] writes = (isMap ? MAP_WRITES : isBuilder ? BUILDER_WRITES : COLLECTION_WRITES).get(name);
        if (writes != null) {
            for (int i : writes) {
                int index = i < 0 ? exp.getArgCount() - 1 : i;
                if (index >= 0 && index < exp.getArgCount()) {
                    // map values (index 1) go under their key and keys apart, list elements under
                    // their position, a builder's text under no key
                    String key = isMap ? (index == 1 ? constantKey(exp) : KEYS)
                            : isBuilder ? null : lists.get(invoke);
                    Write w = new Write(base, csManager.getCSVar(ctx, exp.getArg(index)), key);
                    writesByArg.computeIfAbsent(w.arg(), k -> new ArrayList<>()).add(w);
                    writesByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(w);
                    attach(solver.getPointsToSetOf(base), solver.getPointsToSetOf(w.arg()), key);
                }
            }
        }
        if (!refResult) {
            return;
        }
        CSVar res = csManager.getCSVar(ctx, result);
        if (isMap) {
            switch (name) {
                case "get", "getOrDefault" -> read(base, new Read(res, constantKey(exp), View.VALUES, Shape.RAW));
                case "values", "sequencedValues" -> {
                    read(base, new Read(res, null, View.VALUES, Shape.RAW));
                    read(base, new Read(res, null, View.VALUES, Shape.VIEW));
                }
                case "keySet", "sequencedKeySet" -> {
                    read(base, new Read(res, null, View.KEYS, Shape.RAW));
                    read(base, new Read(res, null, View.KEYS, Shape.VIEW));
                }
                case "entrySet" -> {
                    read(base, new Read(res, null, View.ALL, Shape.RAW));
                    read(base, new Read(res, null, View.ALL, Shape.ENTRIES));
                }
                case "toString" -> read(base, new Read(res, null, View.ALL, Shape.RAW));
                default -> {
                }
            }
        } else if (isBuilder) {
            if (BUILDER_READS.contains(name)) {
                read(base, new Read(res, null, View.ALL, Shape.RAW));
            }
        } else if (COLLECTION_READS.contains(name)) {
            String key = lists.get(invoke);
            read(base, new Read(res, key, View.VALUES, Shape.RAW));
            if (COLLECTION_ITERATORS.contains(name)) {
                read(base, new Read(res, key, View.VALUES, Shape.ITERATOR));
            }
        }
    }

    private void read(CSVar base, Read r) {
        readsByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(r);
        subscribe(solver.getPointsToSetOf(base), r);
    }

    /** calls that take the element out of a carrier */
    private void registerUnwrap(JClass cls, String name, CSVar base, CSVar result) {
        String kind = null;
        if (COLLECTION_ITERATORS.contains(name) && isSub(iterableClass, cls)) {
            kind = "view";
        } else if ((name.equals("next") || name.equals("previous")) && isSub(iteratorClass, cls)) {
            kind = "iterator";
        } else if (name.equals("getValue") && isSub(entryClass, cls)) {
            kind = "value";
        } else if (name.equals("getKey") && isSub(entryClass, cls)) {
            kind = "key";
        }
        if (kind != null) {
            Unwrap u = new Unwrap(result, kind);
            unwrapsByBase.computeIfAbsent(base, k -> new ArrayList<>()).add(u);
            unwrap(solver.getPointsToSetOf(base), u);
        }
    }

    private void unwrap(PointsToSet pts, Unwrap u) {
        if (pts == null) {
            return;
        }
        PointsToSet out = solver.makePointsToSet();
        for (CSObj obj : pts) {
            CSObj inner = carried.get(obj);
            if (inner == null || !kindOf(obj).equals(u.kind())) {
                continue;
            }
            // view.iterator() gives an iterator over the same elements; the others give the element
            out.addObject(u.kind().equals("view") ? carrier("iterator", inner, iteratorClass) : inner);
        }
        if (!out.isEmpty()) {
            solver.addPointsTo(u.result(), out);
        }
    }

    private static String kindOf(CSObj carrier) {
        return ((Carried) carrier.getObject().getAllocation()).kind();
    }

    private CSObj carrier(String kind, CSObj inner, JClass type) {
        Type t = type.getType();
        Obj obj = solver.getHeapModel().getMockObj(CARRIER_DESC, new Carried(kind, inner.getObject()), t, false);
        CSObj cs = csManager.getCSObj(inner.getContext(), obj);
        carried.putIfAbsent(cs, inner);
        return cs;
    }

    /** what a read of {@code r} puts into its result for taint {@code t} stored under {@code stored} */
    private CSObj shape(Read r, CSObj t, String stored) {
        return switch (r.shape()) {
            case RAW -> t;
            case ITERATOR -> carrier("iterator", t, iteratorClass);
            case VIEW -> carrier("view", t, collectionClass);
            case ENTRIES -> carrier("view", carrier(KEYS.equals(stored) ? "key" : "value", t, entryClass),
                    collectionClass);
        };
    }

    /** the first argument when it is a string constant, else null */
    static String constantKey(InvokeInstanceExp exp) {
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
        for (Unwrap u : unwrapsByBase.getOrDefault(csVar, List.of())) {
            unwrap(pts, u);
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
            List<CSObj> added = new ArrayList<>();
            for (CSObj t : taints) {
                if (content.add(t)) {
                    added.add(t);
                }
            }
            if (!added.isEmpty()) {
                for (Read r : readers.getOrDefault(container, Set.of())) {
                    if (r.sees(key)) {
                        deliver(r, added, key);
                    }
                }
            }
        }
    }

    private void deliver(Read r, Iterable<CSObj> taints, String stored) {
        PointsToSet pts = solver.makePointsToSet();
        for (CSObj t : taints) {
            pts.addObject(shape(r, t, stored));
        }
        if (!pts.isEmpty()) {
            solver.addPointsTo(r.result(), pts);
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
            contents.getOrDefault(container, Map.of()).forEach((key, content) -> {
                if (r.sees(key)) {
                    deliver(r, content, key);
                }
            });
        }
    }

    /**
     * Only containers allocated in application code (and the per-call-site Spring Data results).
     * A container allocated inside a library (Stream.toList, Collectors.toMap, ...) or a shared
     * factory is one abstract object for the whole program under this heap abstraction, so taint
     * attached to it would reach every reader in the service (observed: ApiResponse.getData()).
     */
    private static boolean isTracked(CSObj obj) {
        if (obj.getObject() instanceof MergedObj) {
            return false; // every StringBuilder/StringBuffer/String of the program (Tai-e's merge-* options)
        }
        if (obj.getObject() instanceof MockObj mock) {
            return mock.getDescriptor().string().startsWith("SpringData");
        }
        return obj.getObject().getContainerMethod()
                .map(m -> m.getDeclaringClass().isApplication())
                .orElse(false);
    }

    // ------------------------------------------------------------------ sinks on contents

    @Override
    public void onFinish() {
        List<Sink> sinks;
        try {
            sinks = loadSinks();
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warn("container sinks skipped: the taint config could not be read ({})", e.toString());
            return;
        }
        Set<String> flows = new TreeSet<>();
        for (Sink sink : sinks) {
            IndexRef ref = sink.indexRef();
            String owner = sink.method().getDeclaringClass().getName();
            if (ref.kind() != IndexRef.Kind.VAR || ref.index() < 0
                    || !(owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("jakarta."))) {
                continue;
            }
            solver.getCallGraph().reachableMethods()
                    .filter(m -> m.getMethod().equals(sink.method()))
                    .flatMap(m -> solver.getCallGraph().edgesInTo(m))
                    .forEach(edge -> contentFlows(edge, sink, flows));
        }
        flows.forEach(logger::info);
        if (!flows.isEmpty()) {
            logger.info("{} taint flow(s) of container contents", flows.size());
        }
    }

    private void contentFlows(Edge<CSCallSite, CSMethod> edge, Sink sink, Set<String> flows) {
        CSCallSite site = edge.getCallSite();
        Invoke call = site.getCallSite();
        int index = sink.indexRef().index();
        if (!call.getContainer().getDeclaringClass().isApplication() || index >= call.getInvokeExp().getArgCount()) {
            return;
        }
        PointsToSet pts = solver.getPointsToSetOf(csManager.getCSVar(site.getContext(), call.getInvokeExp().getArg(index)));
        if (pts == null) {
            return;
        }
        // a taint the argument holds itself is a flow Tai-e reports
        Set<Object> direct = new HashSet<>();
        pts.objects().filter(ContainerTaint::isTaint).forEach(t -> direct.add(t.getObject().getAllocation()));
        Set<Object> sources = new LinkedHashSet<>();
        for (CSObj container : pts) {
            contents.getOrDefault(container, Map.of()).values().forEach(content -> content.forEach(t -> {
                Object source = t.getObject().getAllocation();
                if (!direct.contains(source)) {
                    sources.add(source);
                }
            }));
        }
        for (Object source : sources) {
            try {
                flows.add(taintFlow(source, call, sink));
            } catch (ReflectiveOperationException e) {
                logger.warn("container sink flow not built: {}", e.toString());
                return;
            }
        }
    }

    private List<Sink> loadSinks() throws ReflectiveOperationException {
        String path = solver.getOptions().getString("taint-config");
        Class<?> provider = Class.forName("pascal.taie.analysis.pta.plugin.taint.YamlTaintConfigProvider");
        Constructor<?> make = provider.getDeclaredConstructor(ClassHierarchy.class, TypeSystem.class);
        make.setAccessible(true);
        Object p = make.newInstance(hierarchy, World.get().getTypeSystem());
        Method setPath = provider.getDeclaredMethod("setPath", String.class);
        setPath.setAccessible(true);
        setPath.invoke(p, path);
        Method get = provider.getMethod("get");
        get.setAccessible(true);
        return ((TaintConfig) get.invoke(p)).sinks();
    }

    /** Tai-e's own text of a flow from {@code source} to argument {@code sink.indexRef()} of {@code call} */
    private static String taintFlow(Object source, Invoke call, Sink sink) throws ReflectiveOperationException {
        Class<?> sinkPoint = Class.forName("pascal.taie.analysis.pta.plugin.taint.SinkPoint");
        Class<?> sourcePoint = Class.forName("pascal.taie.analysis.pta.plugin.taint.SourcePoint");
        Constructor<?> sp = sinkPoint.getDeclaredConstructor(Invoke.class, IndexRef.class, Sink.class);
        sp.setAccessible(true);
        Object point = sp.newInstance(call, sink.indexRef(), sink);
        Class<?> flow = Class.forName("pascal.taie.analysis.pta.plugin.taint.TaintFlow");
        Constructor<?> f = flow.getDeclaredConstructor(sourcePoint, sinkPoint);
        f.setAccessible(true);
        return f.newInstance(source, point).toString();
    }

    // ------------------------------------------------------------------ barriers

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
