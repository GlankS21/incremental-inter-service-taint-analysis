package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.ParamProvider;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.language.classes.JField;
import pascal.taie.language.type.ArrayType;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.TwoKeyMultiMap;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry points and request arrays of a servlet application (a war without Spring request mappings).
 *
 * <p>Micans' Spring model enters an application through its request mappings only. Here every
 * {@code doGet}/{@code doPost}/... (and {@code service}) that an application subclass of
 * {@code javax.servlet.http.HttpServlet} or {@code jakarta.servlet.http.HttpServlet} declares is an
 * entry point, with {@code this} and the request and response mocked from their declared types. The
 * container calls them; the Servlet API stubs of the analysis do not dispatch from {@code service}. A
 * concrete servlet that inherits its handler from an application superclass (a template method:
 * the base class's {@code doGet} calls an abstract method the servlet implements) enters through that
 * handler, with {@code this} an object of the servlet's own class.
 *
 * <p>The request getters are call sources (gen_taint_config.SERVLET_SOURCES) of methods without a
 * body, so an array a getter returns ({@code getParameterValues}) is the taint object itself, and an
 * array a transfer returns ({@code String.split}) is a taint object as well: Tai-e reads an array
 * element from an array object, never from a taint object. An array load whose array points to a
 * taint object therefore reads a taint object of the same source typed as the element
 * ({@code values[0]} of {@code getParameterMap().get(k)} is a String): a taint typed as the array
 * would be dropped at the next cast to the element type, e.g. after a round trip through a list.
 *
 * <p>Taint never enters a library method (JDK or jar): servlet code hands the raw request strings to
 * library code, whose internal calls have one call site each, so under 1-call they merge every
 * caller (observed: {@code URLDecoder.decode(s, enc)} returns {@code s} through
 * {@code decode(s, Charset)}, and the taint of all 2740 Benchmark servlets reached every sink). As
 * {@link JdkTaintBarrier} does for java.util, the taint follows the YAML transfers at the
 * application call site instead, in the caller's context; sinks are checked at the call site.
 *
 * <p>The rules apply only when the analysed code has a servlet entry point: the Spring services are
 * analysed as before.
 */
public class ServletEntryPoints implements Plugin {

    private static final Logger logger = LogManager.getLogger(ServletEntryPoints.class);

    private static final Set<String> SERVLETS = Set.of(
            "javax.servlet.http.HttpServlet", "jakarta.servlet.http.HttpServlet");

    private static final Set<String> HANDLERS = Set.of(
            "doGet", "doPost", "doPut", "doDelete", "doHead", "doOptions", "doTrace", "service");

    private Solver solver;

    private CSManager csManager;

    private boolean enabled;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
    }

    @Override
    public void onStart() {
        int count = 0;
        for (JClass cls : solver.getHierarchy().applicationClasses().toList()) {
            if (cls.isAbstract() || !isServlet(cls)) {
                continue;
            }
            for (JMethod m : cls.getDeclaredMethods()) {
                if (isHandler(m)) {
                    solver.addEntryPoint(new EntryPoint(m, new DeclaredParamProvider(m, solver.getHeapModel(), 1)));
                    count++;
                }
            }
            if (MicroserviceHolder.isApplication(cls.getName())) {
                count += addInheritedHandlers(cls);
            }
        }
        enabled = count > 0;
        logger.info("Added {} servlet entry point(s)", count);
    }

    private static boolean isHandler(JMethod m) {
        return !m.isAbstract() && !m.isStatic() && HANDLERS.contains(m.getName()) && m.getParamCount() == 2;
    }

    /** the handlers cls does not declare but an application superclass does, entered with cls as this */
    private int addInheritedHandlers(JClass cls) {
        Set<String> declared = new HashSet<>();
        cls.getDeclaredMethods().stream().filter(ServletEntryPoints::isHandler).forEach(m -> declared.add(m.getName()));
        int count = 0;
        for (JClass c = cls.getSuperClass(); c != null && MicroserviceHolder.isApplication(c.getName());
             c = c.getSuperClass()) {
            for (JMethod m : c.getDeclaredMethods()) {
                if (isHandler(m) && declared.add(m.getName())) {
                    Obj self = solver.getHeapModel().getMockObj(Descriptor.ENTRY_DESC, cls, cls.getType(), m);
                    ParamProvider params = new WithThis(new DeclaredParamProvider(m, solver.getHeapModel(), 1), self);
                    solver.addEntryPoint(new EntryPoint(m, params));
                    count++;
                }
            }
        }
        return count;
    }

    /** the declared parameters of a handler, with this an object of the concrete servlet */
    private record WithThis(ParamProvider declared, Obj self) implements ParamProvider {

        @Override
        public Set<Obj> getThisObjs() {
            return Set.of(self);
        }

        @Override
        public Set<Obj> getParamObjs(int i) {
            return declared.getParamObjs(i);
        }

        @Override
        public TwoKeyMultiMap<Obj, JField, Obj> getFieldObjs() {
            return declared.getFieldObjs();
        }

        @Override
        public MultiMap<Obj, Obj> getArrayObjs() {
            return declared.getArrayObjs();
        }
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        // Micans' application classes: Tai-e's JClass.isApplication() does not separate the jars here
        if (!enabled || MicroserviceHolder.isApplication(method.getDeclaringClass().getName())
                || method.isAbstract() || method.isNative()) {
            return;
        }
        ContainerTaint.keepTaintOut(solver, csMethod);
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        if (!enabled) {
            return;
        }
        List<LoadArray> loads = csVar.getVar().getLoadArrays();
        if (loads.isEmpty()) {
            return;
        }
        PointsToSet taints = solver.makePointsToSet();
        for (CSObj obj : pts) {
            if (ContainerTaint.isTaint(obj)) {
                taints.addObject(element(obj));
            }
        }
        if (taints.isEmpty()) {
            return;
        }
        for (LoadArray load : loads) {
            solver.addPointsTo(csManager.getCSVar(csVar.getContext(), load.getLValue()), taints);
        }
    }

    /** a taint read out of a tainted array: the same source, typed as the array's element */
    private CSObj element(CSObj taint) {
        if (!(taint.getObject().getType() instanceof ArrayType array)) {
            return taint;
        }
        Obj element = solver.getHeapModel().getMockObj(taintDescriptor(), taint.getObject().getAllocation(),
                array.elementType(), false);
        return csManager.getCSObj(taint.getContext(), element);
    }

    private static Descriptor taintDesc;

    /** Tai-e's taint descriptor (TaintManager.TAINT_DESC): a taint object is a mock object with it */
    private static synchronized Descriptor taintDescriptor() {
        if (taintDesc == null) {
            try {
                Field f = Class.forName("pascal.taie.analysis.pta.plugin.taint.TaintManager")
                        .getDeclaredField("TAINT_DESC");
                f.setAccessible(true);
                taintDesc = (Descriptor) f.get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return taintDesc;
    }

    private static boolean isServlet(JClass cls) {
        for (JClass c = cls.getSuperClass(); c != null; c = c.getSuperClass()) {
            if (SERVLETS.contains(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
