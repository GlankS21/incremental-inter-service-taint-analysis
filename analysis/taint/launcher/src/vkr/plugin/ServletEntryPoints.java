package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.DeclaredParamProvider;
import pascal.taie.analysis.pta.core.solver.EntryPoint;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Entry points and request arrays of a servlet application (a war without Spring request mappings).
 *
 * <p>Micans' Spring model enters an application through its request mappings only. Here every
 * {@code doGet}/{@code doPost}/... (and {@code service}) that an application subclass of
 * {@code javax.servlet.http.HttpServlet} or {@code jakarta.servlet.http.HttpServlet} declares is an
 * entry point, with {@code this} and the request and response mocked from their declared types. The
 * container calls them; the Servlet API stubs of the analysis do not dispatch from {@code service}.
 *
 * <p>The request getters are call sources (gen_taint_config.SERVLET_SOURCES) of methods without a
 * body, so an array a getter returns ({@code getParameterValues}) is the taint object itself, and an
 * array a transfer returns ({@code String.split}) is a taint object as well: Tai-e reads an array
 * element from an array object, never from a taint object. An array load whose array points to a
 * taint object therefore reads that taint object.
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

    private static final Predicate<CSObj> NOT_TAINT = o -> !ContainerTaint.isTaint(o);

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
                if (m.isAbstract() || m.isStatic() || !HANDLERS.contains(m.getName()) || m.getParamCount() != 2) {
                    continue;
                }
                solver.addEntryPoint(new EntryPoint(m, new DeclaredParamProvider(m, solver.getHeapModel(), 1)));
                count++;
            }
        }
        enabled = count > 0;
        logger.info("Added {} servlet entry point(s)", count);
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        // Micans' application classes: Tai-e's JClass.isApplication() does not separate the jars here
        if (!enabled || MicroserviceHolder.isApplication(method.getDeclaringClass().getName())
                || method.isAbstract() || method.isNative()) {
            return;
        }
        Context ctx = csMethod.getContext();
        IR ir = method.getIR();
        if (ir.getThis() != null) {
            solver.addPointerFilter(csManager.getCSVar(ctx, ir.getThis()), NOT_TAINT);
        }
        for (Var param : ir.getParams()) {
            solver.addPointerFilter(csManager.getCSVar(ctx, param), NOT_TAINT);
        }
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
                taints.addObject(obj);
            }
        }
        if (taints.isEmpty()) {
            return;
        }
        for (LoadArray load : loads) {
            solver.addPointsTo(csManager.getCSVar(csVar.getContext(), load.getLValue()), taints);
        }
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
