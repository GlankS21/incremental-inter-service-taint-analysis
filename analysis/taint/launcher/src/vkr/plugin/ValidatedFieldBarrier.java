package vkr.plugin;

import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.Nop;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.annotation.Annotation;
import pascal.taie.language.annotation.StringElement;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bean Validation as a sanitizer: a field annotated with {@code @Pattern} whose regular expression
 * admits neither quotes nor line breaks (e.g. coupon codes {@code ^[A-Za-z0-9_-]{3,30}$}) holds no
 * text that could inject SQL or forge a log line, so taint is dropped where the field is read.
 *
 * <p>The request DTOs are validated when Spring binds the body ({@code @Valid}); values a service
 * puts into such a DTO itself are not, which this barrier assumes does not happen. A pattern the
 * check below does not understand is treated as unsafe and keeps the taint.
 *
 * <p>Validation by an explicit regex check is treated the same way (TCI-4):
 * <ul>
 * <li>{@code s.matches("<safe literal>")} — and the equivalent {@code Matcher.matches()} — either
 * succeeds, so {@code s} holds no dangerous char, or the caller throws / discards {@code s}
 * (observed: OrderServiceImpl.normalizeIdempotencyKey throws on a mismatch, while
 * CorrelationIdUtils.resolveOrGenerate returns a fresh UUID instead), so the receiver keeps no
 * taint past the check.</li>
 * <li>The taint configs transfer taint from a whole tainted DTO object to the result of every
 * accessor call ({@code <com.shop.order.dto.request.*: * *()>}), which bypasses the LoadField
 * filter inside the accessor body; so a no-arg method returning exactly an instance field that
 * carries a safe {@code @Pattern} (a record accessor or {@code return this.<field>;}) gets its
 * result filtered at the call site. List/map accessors never match: the field must be a String
 * and the body may contain nothing else.</li>
 * </ul>
 */
public class ValidatedFieldBarrier implements Plugin {

    private static final String[] PATTERN = {"jakarta.validation.constraints.Pattern",
            "javax.validation.constraints.Pattern"};

    /** quotes, backslash, statement separator, line breaks */
    private static final String DANGEROUS = "'\"`\\;\r\n\u0085  ";

    private static final String STRING = "java.lang.String";
    private static final String PATTERN_CLASS = "java.util.regex.Pattern";
    private static final String MATCHER_CLASS = "java.util.regex.Matcher";

    private Solver solver;

    private CSManager csManager;

    private final Map<JField, Boolean> validated = new HashMap<>();

    /** static Pattern fields whose <clinit> compiles a safe constant regex */
    private final Map<JField, Boolean> compiledPatterns = new HashMap<>();

    /** matcher result variable -> the string argument it was created from */
    private final Map<CSVar, CSVar> matcherArg = new HashMap<>();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.csManager = solver.getCSManager();
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        if (method.isAbstract() || method.isNative() || !method.getDeclaringClass().isApplication()) {
            return;
        }
        Context ctx = csMethod.getContext();
        List<Stmt> stmts = method.getIR().stmts().toList();
        // pattern variables a Matcher may be created from: compile results of a safe constant
        // regex, and loads of static Pattern fields initialised by such a compile
        Set<CSVar> safePatterns = new HashSet<>();
        for (Stmt stmt : stmts) {
            if (stmt instanceof Invoke invoke && invoke.isStatic() && invoke.getResult() != null
                    && isPatternCompile(invoke.getMethodRef().resolveNullable())
                    && admits(constantString(invoke.getInvokeExp()))) {
                safePatterns.add(csManager.getCSVar(ctx, invoke.getResult()));
            } else if (stmt instanceof LoadField load && load.isStatic()
                    && isCompiledPattern(load.getFieldAccess().getFieldRef().resolveNullable())) {
                safePatterns.add(csManager.getCSVar(ctx, load.getLValue()));
            }
        }
        for (Stmt stmt : stmts) {
            if (stmt instanceof Invoke invoke) {
                register(ctx, invoke, safePatterns);
            } else if (stmt instanceof LoadField load) {
                JField field = load.getFieldAccess().getFieldRef().resolveNullable();
                if (field != null && validated.computeIfAbsent(field, ValidatedFieldBarrier::hasSafePattern)) {
                    solver.addPointerFilter(csManager.getCSVar(ctx, load.getLValue()), ContainerTaint.NOT_TAINT);
                }
            }
        }
    }

    private void register(Context ctx, Invoke invoke, Set<CSVar> safePatterns) {
        // static Pattern.compile calls are collected in the first pass, invokedynamic has no receiver
        if (!(invoke.getInvokeExp() instanceof InvokeInstanceExp exp)) {
            return;
        }
        JMethod callee = invoke.getMethodRef().resolveNullable();
        if (isStringMatches(callee)) {
            if (admits(constantString(exp))) {
                // the receiver matched a regex that admits no dangerous char; on mismatch the
                // caller throws or discards it, so no dangerous value flows past the check
                solver.addPointerFilter(csManager.getCSVar(ctx, exp.getBase()), ContainerTaint.NOT_TAINT);
            }
            return;
        }
        if (invoke.getResult() == null) {
            return;
        }
        if (isPatternMatcher(callee)) {
            if (safePatterns.contains(csManager.getCSVar(ctx, exp.getBase()))) {
                // remember which string this Matcher was created from
                matcherArg.put(csManager.getCSVar(ctx, invoke.getResult()),
                        csManager.getCSVar(ctx, exp.getArg(0)));
            }
        } else if (isMatcherMatches(callee)) {
            CSVar arg = matcherArg.get(csManager.getCSVar(ctx, exp.getBase()));
            if (arg != null) {
                solver.addPointerFilter(arg, ContainerTaint.NOT_TAINT);
            }
        } else {
            filterValidatedAccessor(ctx, invoke, callee);
        }
    }

    /**
     * TCI-4 GAP B: the transfer from the whole DTO object taints accessor results at the call
     * site, bypassing the LoadField filter inside the accessor body. A no-arg application method
     * returning exactly an instance field that carries a safe {@code @Pattern} yields only the
     * validated value.
     */
    private void filterValidatedAccessor(Context ctx, Invoke invoke, JMethod callee) {
        if (callee == null || callee.isAbstract() || callee.isNative()
                || callee.getParamCount() != 0 || !callee.getDeclaringClass().isApplication()) {
            return;
        }
        LoadField load = accessorLoad(callee.getIR());
        if (load == null) {
            return;
        }
        JField field = load.getFieldAccess().getFieldRef().resolveNullable();
        if (field != null && !field.isStatic() && STRING.equals(field.getType().getName())
                && validated.computeIfAbsent(field, ValidatedFieldBarrier::hasSafePattern)) {
            solver.addPointerFilter(csManager.getCSVar(ctx, invoke.getResult()), ContainerTaint.NOT_TAINT);
        }
    }

    /**
     * The LoadField of a method shaped exactly like {@code return this.<field>;}: nothing but a
     * LoadField on {@code this} (plus a Nop) and one Return of the loaded value. Strict by design:
     * a computed getter or a list accessor must never match.
     */
    static LoadField accessorLoad(Var thisVar, List<Stmt> stmts) {
        LoadField load = null;
        boolean returned = false;
        for (Stmt stmt : stmts) {
            if (stmt instanceof Nop) {
                continue;
            }
            if (stmt instanceof LoadField l && load == null && !returned
                    && l.getFieldAccess() instanceof InstanceFieldAccess access
                    && access.getBase() == thisVar) {
                load = l;
            } else if (stmt instanceof Return ret && !returned && load != null
                    && ret.getValue() == load.getLValue()) {
                returned = true;
            } else {
                return null;
            }
        }
        return returned ? load : null;
    }

    private static LoadField accessorLoad(IR ir) {
        return accessorLoad(ir.getThis(), ir.stmts().toList());
    }

    /** is a call of String.matches(String)? */
    private static boolean isStringMatches(JMethod callee) {
        return match(callee, STRING, "matches", 1);
    }

    private static boolean isPatternCompile(JMethod callee) {
        return match(callee, PATTERN_CLASS, "compile", 1)
                || match(callee, PATTERN_CLASS, "compile", 2);
    }

    private static boolean isPatternMatcher(JMethod callee) {
        return match(callee, PATTERN_CLASS, "matcher", 1);
    }

    private static boolean isMatcherMatches(JMethod callee) {
        return match(callee, MATCHER_CLASS, "matches", 0);
    }

    private static boolean match(JMethod callee, String declaring, String name, int params) {
        return callee != null && callee.getDeclaringClass().getName().equals(declaring)
                && callee.getName().equals(name) && callee.getParamCount() == params;
    }

    /** arg 0 of an invoke as a compile-time string constant, or null */
    private static String constantString(InvokeExp exp) {
        if (exp.getArgCount() > 0) {
            Var arg = exp.getArg(0);
            if (arg.isConst() && arg.getConstValue() instanceof StringLiteral literal) {
                return literal.getString();
            }
        }
        return null;
    }

    /** is the field a static Pattern initialised by a safe constant compile in <clinit>? */
    private boolean isCompiledPattern(JField field) {
        return field != null && field.isStatic() && PATTERN_CLASS.equals(field.getType().getName())
                && compiledPatterns.computeIfAbsent(field, ValidatedFieldBarrier::hasSafeClinit);
    }

    private static boolean hasSafeClinit(JField field) {
        JMethod clinit = field.getDeclaringClass().getClinit();
        if (clinit == null) {
            return false;
        }
        Set<Var> safeVars = new HashSet<>();
        for (Stmt stmt : clinit.getIR().stmts().toList()) {
            if (stmt instanceof Invoke invoke && invoke.isStatic() && invoke.getResult() != null
                    && isPatternCompile(invoke.getMethodRef().resolveNullable())
                    && admits(constantString(invoke.getInvokeExp()))) {
                safeVars.add(invoke.getResult());
            } else if (stmt instanceof StoreField store && store.isStatic()
                    && store.getFieldAccess().getFieldRef().resolveNullable() == field
                    && safeVars.contains(store.getRValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSafePattern(JField field) {
        for (String type : PATTERN) {
            Annotation pattern = field.getAnnotation(type);
            if (pattern != null && pattern.getElement("regexp") instanceof StringElement regexp) {
                return admitsNoDangerousChar(regexp.value());
            }
        }
        return false;
    }

    /** does {@code regex} admit no dangerous char, or is it not a constant at all? */
    private static boolean admits(String regex) {
        return regex != null && admitsNoDangerousChar(regex);
    }

    /**
     * Conservative check of a regular expression: literals, escaped punctuation, \d, \w, positive
     * character classes, groups, alternation, anchors and quantifiers are understood; '.', negated
     * classes, \s, \W, \p{..}, lookarounds and anything else make the pattern unsafe.
     */
    static boolean admitsNoDangerousChar(String regex) {
        int i = 0;
        int n = regex.length();
        while (i < n) {
            char c = regex.charAt(i);
            switch (c) {
                case '^', '$', ')', '|', '*', '+', '?' -> i++;
                case '(' -> {
                    if (i + 1 < n && regex.charAt(i + 1) == '?') {
                        if (!regex.startsWith("(?:", i)) {
                            return false;
                        }
                        i += 3;
                    } else {
                        i++;
                    }
                }
                case '{' -> {
                    int end = regex.indexOf('}', i);
                    if (end < 0 || !regex.substring(i + 1, end).matches("\\d+(,\\d*)?")) {
                        return false;
                    }
                    i = end + 1;
                }
                case '[' -> {
                    int end = safeClassEnd(regex, i + 1);
                    if (end < 0) {
                        return false;
                    }
                    i = end + 1;
                }
                case '\\' -> {
                    if (i + 1 >= n || !safeEscape(regex.charAt(i + 1))) {
                        return false;
                    }
                    i += 2;
                }
                case '.' -> {
                    return false;
                }
                default -> {
                    if (DANGEROUS.indexOf(c) >= 0) {
                        return false;
                    }
                    i++;
                }
            }
        }
        return true;
    }

    /** index of the ']' closing a class that starts at {@code i}, or -1 if the class is unsafe */
    private static int safeClassEnd(String regex, int i) {
        int n = regex.length();
        if (i < n && regex.charAt(i) == '^') {
            return -1;
        }
        boolean first = true;
        while (i < n) {
            char c = regex.charAt(i);
            if (c == ']' && !first) {
                return i;
            }
            first = false;
            if (c == '[' || regex.startsWith("&&", i)) {
                return -1;
            }
            if (c == '\\') {
                if (i + 1 >= n || !safeEscape(regex.charAt(i + 1))) {
                    return -1;
                }
                i += 2;
                continue;
            }
            if (i + 2 < n && regex.charAt(i + 1) == '-' && regex.charAt(i + 2) != ']') {
                char to = regex.charAt(i + 2);
                for (char x = c; x <= to; x++) {
                    if (DANGEROUS.indexOf(x) >= 0) {
                        return -1;
                    }
                }
                i += 3;
                continue;
            }
            if (DANGEROUS.indexOf(c) >= 0) {
                return -1;
            }
            i++;
        }
        return -1;
    }

    /** \d and \w, or an escaped punctuation character that is itself harmless */
    private static boolean safeEscape(char e) {
        return e == 'd' || e == 'w' || !Character.isLetterOrDigit(e) && DANGEROUS.indexOf(e) < 0;
    }
}
