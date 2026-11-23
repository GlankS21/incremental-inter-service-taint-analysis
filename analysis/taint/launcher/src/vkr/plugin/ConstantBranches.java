package vkr.plugin;

import micans.plugin.spring.MicroserviceHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Evaluator;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.CFGEdge;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.DefaultIR;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Exps;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.ExceptionEntry;
import pascal.taie.ir.stmt.AssignLiteral;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.language.type.ByteType;
import pascal.taie.language.type.CharType;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.ShortType;
import pascal.taie.ir.stmt.If;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadArray;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.Nop;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreArray;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.ir.stmt.SwitchStmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Drops the code of an application method that only a branch on a constant condition reaches.
 *
 * <p>The points-to analysis is flow-insensitive: in {@code int num = 86; if ((7 * 42) - num > 200)
 * bar = "safe"; else bar = param;} both assignments flow into {@code bar}, although the else branch
 * never runs. Before the solver sees a method, Tai-e's intraprocedural constant propagation (with
 * {@code "ABC".charAt(1)} on a string constant and integer casts added: javac selects a constant switch
 * with {@code (int) "ABC".charAt(1)}) decides the
 * {@code if} and {@code switch} conditions; a statement reachable in the control-flow graph but not
 * once the edges of decided conditions are removed is replaced by a {@code nop}, and taken out of the
 * field, array and call statements its base variable lists (the solver processes those per variable,
 * not per method).
 *
 * <p>Only statements a constant condition cuts off are dropped: an exception handler is a root of
 * both traversals, so code that only an exception reaches stays. The pruning runs in
 * {@link #setSolver}, before any plugin adds an entry point (the solver processes an entry method as
 * it is added).
 */
public class ConstantBranches implements Plugin {

    private static final Logger logger = LogManager.getLogger(ConstantBranches.class);

    private static final String CHAR_AT = "<java.lang.String: char charAt(int)>";

    private final CFGBuilder cfgBuilder = new CFGBuilder(AnalysisConfig.of(CFGBuilder.ID, "exception", null, "dump", false));

    @Override
    public void setSolver(Solver solver) {
        int methods = 0;
        int stmts = 0;
        for (JClass cls : solver.getHierarchy().applicationClasses().toList()) {
            if (!MicroserviceHolder.isApplication(cls.getName())) {
                continue;
            }
            for (JMethod m : cls.getDeclaredMethods()) {
                if (m.isAbstract() || m.isNative()) {
                    continue;
                }
                int dropped = prune(m);
                if (dropped > 0) {
                    methods++;
                    stmts += dropped;
                }
            }
        }
        logger.info("Dropped {} statement(s) behind constant conditions in {} method(s)", stmts, methods);
    }

    /** Returns the number of statements dropped from the IR of {@code m}. */
    private int prune(JMethod m) {
        IR ir = m.getIR();
        if (ir.stmts().noneMatch(s -> s instanceof If || s instanceof SwitchStmt)) {
            return 0;
        }
        CFG<Stmt> cfg = cfgBuilder.analyze(ir);
        DataflowResult<Stmt, CPFact> constants = pascal.taie.analysis.dataflow.solver.Solver.<Stmt, CPFact>getSolver()
                .solve(new Analysis(cfg, strings(ir)));
        String debug = System.getProperty("vkr.debug");
        if (debug != null && m.toString().contains(debug)) {
            for (Stmt s : ir) {
                logger.info("[const] {} {} | {}", s.getClass().getSimpleName(), s,
                        s instanceof SwitchStmt sw ? constants.getInFact(sw).get(sw.getVar())
                                : s instanceof Invoke inv ? inv.getMethodRef() + " " + constants.getOutFact(inv) : "");
            }
        }
        Set<Stmt> all = reach(ir, cfg, e -> true);
        Set<Stmt> live = reach(ir, cfg, e -> !decidedAway(e, constants));
        Set<Stmt> dead = new HashSet<>(all);
        dead.removeAll(live);
        dead.remove(cfg.getExit());
        if (dead.isEmpty()) {
            return 0;
        }
        List<Stmt> stmts = new ArrayList<>(ir.getStmts().size());
        for (Stmt s : ir.getStmts()) {
            if (dead.contains(s)) {
                Nop nop = new Nop();
                nop.setIndex(s.getIndex());
                nop.setLineNumber(s.getLineNumber());
                stmts.add(nop);
            } else {
                stmts.add(s);
            }
        }
        unlink(dead);
        set(JMethod.class, "ir", m, new DefaultIR(m, ir.getThis(), ir.getParams(),
                new LinkedHashSet<>(ir.getReturnVars()), ir.getVars(), stmts, ir.getExceptionEntries()));
        return dead.size();
    }

    /** Statements reachable from the entry and the exception handlers along the edges {@code keep} accepts. */
    private static Set<Stmt> reach(IR ir, CFG<Stmt> cfg, Predicate<CFGEdge<Stmt>> keep) {
        Set<Stmt> seen = new HashSet<>();
        Deque<Stmt> queue = new ArrayDeque<>();
        queue.add(cfg.getEntry());
        for (ExceptionEntry e : ir.getExceptionEntries()) {
            queue.add(e.handler());
        }
        while (!queue.isEmpty()) {
            Stmt s = queue.remove();
            if (!seen.add(s)) {
                continue;
            }
            for (CFGEdge<Stmt> edge : cfg.getOutEdgesOf(s)) {
                if (keep.test(edge)) {
                    queue.add(edge.target());
                }
            }
        }
        return seen;
    }

    /** Whether {@code edge} leaves an if or switch whose condition is constant and selects another edge. */
    private static boolean decidedAway(CFGEdge<Stmt> edge, DataflowResult<Stmt, CPFact> constants) {
        Stmt src = edge.source();
        if (src instanceof If ifStmt) {
            Value cond = Evaluator.evaluate(ifStmt.getCondition(), constants.getInFact(ifStmt));
            if (cond.isConstant()) {
                int v = cond.getConstant();
                return v == 1 && edge.getKind() == CFGEdge.Kind.IF_FALSE
                        || v == 0 && edge.getKind() == CFGEdge.Kind.IF_TRUE;
            }
        } else if (src instanceof SwitchStmt sw) {
            Value sel = Evaluator.evaluate(sw.getVar(), constants.getInFact(sw));
            if (sel.isConstant()) {
                int v = sel.getConstant();
                return edge.isSwitchCase() ? v != edge.getCaseValue() : sw.getCaseValues().contains(v);
            }
        }
        return false;
    }

    /** String constants of the method: a variable whose only definition assigns a string literal. */
    private static Map<Var, String> strings(IR ir) {
        Map<Var, String> out = new HashMap<>();
        Set<Var> other = new HashSet<>();
        for (Stmt s : ir) {
            s.getDef().ifPresent(d -> {
                if (d instanceof Var v) {
                    if (s instanceof AssignLiteral a && a.getRValue() instanceof StringLiteral lit
                            && !other.contains(v) && !out.containsKey(v)) {
                        out.put(v, lit.getString());
                    } else {
                        out.remove(v);
                        other.add(v);
                    }
                }
            });
        }
        for (Var v : ir.getVars()) {
            if (v.isConst() && v.getConstValue() instanceof StringLiteral lit) {
                out.putIfAbsent(v, lit.getString());
            }
        }
        return out;
    }

    /** Tai-e's constant propagation, which also folds {@code charAt} of a string constant. */
    private static final class Analysis extends ConstantPropagation.Analysis {

        private final Map<Var, String> strings;

        Analysis(CFG<Stmt> cfg, Map<Var, String> strings) {
            super(cfg, false);
            this.strings = strings;
        }

        @Override
        public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
            if (stmt instanceof Invoke inv && inv.getResult() != null && Exps.holdsInt(inv.getResult())
                    && inv.getInvokeExp() instanceof InvokeInstanceExp exp
                    && CHAR_AT.equals(inv.getMethodRef().toString())) {
                String s = strings.get(exp.getBase());
                Integer i = intValue(exp.getArg(0), in);
                if (s != null && i != null && i >= 0 && i < s.length()) {
                    Var lhs = inv.getResult();
                    boolean changed = false;
                    for (Var v : in.keySet()) {
                        if (!v.equals(lhs)) {
                            changed |= out.update(v, in.get(v));
                        }
                    }
                    return out.update(lhs, Value.makeConstant(s.charAt(i))) || changed;
                }
            }
            // javac widens a char selector before a tableswitch: $i1 = (int) $c0
            if (stmt instanceof Cast cast && Exps.holdsInt(cast.getLValue())
                    && Exps.holdsInt(cast.getRValue().getValue())
                    && cast.getRValue().getCastType() instanceof PrimitiveType to) {
                Value v = in.get(cast.getRValue().getValue());
                if (v.isConstant()) {
                    int c = v.getConstant();
                    int folded = to instanceof CharType ? (char) c
                            : to instanceof ShortType ? (short) c
                            : to instanceof ByteType ? (byte) c : c;
                    Var lhs = cast.getLValue();
                    boolean changed = false;
                    for (Var x : in.keySet()) {
                        if (!x.equals(lhs)) {
                            changed |= out.update(x, in.get(x));
                        }
                    }
                    return out.update(lhs, Value.makeConstant(folded)) || changed;
                }
            }
            return super.transferNode(stmt, in, out);
        }

        private static Integer intValue(Var v, CPFact fact) {
            if (v.isConst() && v.getConstValue() instanceof IntLiteral lit) {
                return lit.getValue();
            }
            Value val = fact.get(v);
            return val.isConstant() ? val.getConstant() : null;
        }
    }

    /** Takes the dropped statements out of the per-variable statement lists the solver walks. */
    private static void unlink(Set<Stmt> dead) {
        Set<Var> bases = new LinkedHashSet<>();
        for (Stmt s : dead) {
            if (s instanceof LoadField lf && !lf.isStatic()) {
                bases.add(((pascal.taie.ir.exp.InstanceFieldAccess) lf.getFieldAccess()).getBase());
            } else if (s instanceof StoreField sf && !sf.isStatic()) {
                bases.add(((pascal.taie.ir.exp.InstanceFieldAccess) sf.getFieldAccess()).getBase());
            } else if (s instanceof LoadArray la) {
                bases.add(la.getArrayAccess().getBase());
            } else if (s instanceof StoreArray sa) {
                bases.add(sa.getArrayAccess().getBase());
            } else if (s instanceof Invoke inv && inv.getInvokeExp() instanceof InvokeInstanceExp exp) {
                bases.add(exp.getBase());
            }
        }
        for (Var v : bases) {
            List<LoadField> loadFields = new ArrayList<>(v.getLoadFields());
            List<StoreField> storeFields = new ArrayList<>(v.getStoreFields());
            List<LoadArray> loadArrays = new ArrayList<>(v.getLoadArrays());
            List<StoreArray> storeArrays = new ArrayList<>(v.getStoreArrays());
            List<Invoke> invokes = new ArrayList<>(v.getInvokes());
            set(Var.class, "relevantStmts", v, EMPTY);
            loadFields.stream().filter(s -> !dead.contains(s)).forEach(v::addLoadField);
            storeFields.stream().filter(s -> !dead.contains(s)).forEach(v::addStoreField);
            loadArrays.stream().filter(s -> !dead.contains(s)).forEach(v::addLoadArray);
            storeArrays.stream().filter(s -> !dead.contains(s)).forEach(v::addStoreArray);
            invokes.stream().filter(s -> !dead.contains(s)).forEach(v::addInvoke);
        }
    }

    private static final Object EMPTY;

    static {
        try {
            Field f = Class.forName("pascal.taie.ir.exp.Var$RelevantStmts").getDeclaredField("EMPTY");
            f.setAccessible(true);
            EMPTY = f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void set(Class<?> owner, String name, Object target, Object value) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + owner.getSimpleName() + "." + name, e);
        }
    }
}
