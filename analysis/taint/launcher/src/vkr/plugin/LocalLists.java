package vkr.plugin;

import pascal.taie.ir.IR;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.ExceptionEntry;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.JumpStmt;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.Throw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Element positions of the lists a method builds and reads in straight-line code.
 *
 * <p>A list is handled when it is allocated in the method ({@code new ArrayList}/{@code LinkedList}),
 * its variable is used only by {@code add(e)}, {@code remove(int)} and {@code get(int)} with constant
 * indexes (and {@code size}, {@code isEmpty}), never copied, passed, stored or returned, and no jump,
 * return, throw or exception handler lies inside the code from its allocation to its last use. The
 * statements then run in order, so the element each {@code get} returns is known: both that
 * {@code get} and the {@code add} of the element get the same key. Any other list keeps no keys.
 */
final class LocalLists {

    private static final Set<String> LIST_CLASSES = Set.of("java.util.ArrayList", "java.util.LinkedList");

    private static final Set<String> HARMLESS = Set.of("size", "isEmpty");

    private LocalLists() {
    }

    /** key of each add and get of a handled list in {@code ir} */
    static Map<Invoke, String> keys(IR ir) {
        Map<Invoke, String> keys = new HashMap<>();
        for (Stmt s : ir.getStmts()) {
            if (s instanceof New alloc && LIST_CLASSES.contains(alloc.getRValue().getType().getName())) {
                simulate(ir, alloc, keys);
            }
        }
        return keys;
    }

    private static void simulate(IR ir, New alloc, Map<Invoke, String> keys) {
        Var list = alloc.getLValue();
        List<Invoke> ops = new ArrayList<>();
        for (Stmt s : ir.getStmts()) {
            if (s == alloc) {
                continue;
            }
            boolean defines = s.getDef().filter(list::equals).isPresent();
            if (!defines && !s.getUses().contains(list)) {
                continue;
            }
            if (defines || !(s instanceof Invoke invoke)
                    || !(invoke.getInvokeExp() instanceof InvokeInstanceExp exp)
                    || !exp.getBase().equals(list) || exp.getArgs().contains(list)
                    || !accepted(invoke, exp)) {
                return; // copied, passed, stored, redefined or another call
            }
            ops.add(invoke);
        }
        if (ops.isEmpty() || !straightLine(ir, alloc.getIndex(), ops.get(ops.size() - 1).getIndex())) {
            return;
        }
        List<Invoke> elements = new ArrayList<>();
        Map<Invoke, String> found = new HashMap<>();
        for (Invoke op : ops) {
            InvokeInstanceExp exp = (InvokeInstanceExp) op.getInvokeExp();
            switch (op.getMethodRef().getName()) {
                case "add" -> elements.add(op);
                case "remove" -> {
                    int i = constIndex(exp);
                    if (i >= elements.size()) {
                        return;
                    }
                    elements.remove(i);
                }
                case "get" -> {
                    int i = constIndex(exp);
                    if (i >= elements.size()) {
                        return;
                    }
                    Invoke added = elements.get(i);
                    String key = "#" + added.getIndex();
                    found.put(added, key);
                    found.put(op, key);
                }
                default -> {
                }
            }
        }
        // an add never read by a get keeps a key too: no get of this list can see it
        for (Invoke op : ops) {
            if (op.getMethodRef().getName().equals("add")) {
                found.putIfAbsent(op, "#" + op.getIndex());
            }
        }
        keys.putAll(found);
    }

    private static boolean accepted(Invoke invoke, InvokeInstanceExp exp) {
        String name = invoke.getMethodRef().getName();
        int args = exp.getArgCount();
        return switch (name) {
            case "<init>" -> args == 0 || args == 1 && exp.getArg(0).getType().getName().equals("int");
            case "add" -> args == 1;
            case "remove", "get" -> args == 1 && constIndex(exp) >= 0;
            default -> HARMLESS.contains(name);
        };
    }

    /** the constant int argument, or -1 */
    private static int constIndex(InvokeInstanceExp exp) {
        Var arg = exp.getArg(0);
        return arg.getType().getName().equals("int") && arg.isConst() && arg.getConstValue() instanceof IntLiteral lit
                ? lit.getValue() : -1;
    }

    /** no statement in [from, to] jumps, returns or throws, and none in (from, to] is a jump target or handler */
    private static boolean straightLine(IR ir, int from, int to) {
        for (int i = from; i <= to; i++) {
            Stmt s = ir.getStmt(i);
            if (s instanceof JumpStmt || s instanceof Return || s instanceof Throw) {
                return false;
            }
        }
        for (Stmt s : ir.getStmts()) {
            if (s instanceof JumpStmt jump) {
                for (Stmt target : jump.getTargets()) {
                    if (target.getIndex() > from && target.getIndex() <= to) {
                        return false;
                    }
                }
            }
        }
        for (ExceptionEntry entry : ir.getExceptionEntries()) {
            int h = entry.handler().getIndex();
            if (h > from && h <= to) {
                return false;
            }
        }
        return true;
    }
}
