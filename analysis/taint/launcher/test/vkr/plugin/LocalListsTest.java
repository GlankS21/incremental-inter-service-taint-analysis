package vkr.plugin;

import org.junit.jupiter.api.Test;
import pascal.taie.ir.DefaultIR;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.InvokeInterface;
import pascal.taie.ir.exp.InvokeSpecial;
import pascal.taie.ir.exp.NewInstance;
import pascal.taie.ir.exp.StringLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.ExceptionEntry;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Catch;
import pascal.taie.ir.stmt.Goto;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.New;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JClassLoader;
import pascal.taie.language.type.BooleanType;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.IntType;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.VoidType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Element positions of {@link LocalLists} and the constant map keys of {@link ContainerTaint}, on IR
 * built by hand: the list idiom {@code add, add, add, remove(0), get(i)} and the shapes that must
 * keep no keys (a jump inside, a list that escapes, a non-constant index).
 */
class LocalListsTest {

    private static final JClassLoader LOADER = new JClassLoader() {
        @Override
        public JClass loadClass(String name) {
            return null;
        }

        @Override
        public java.util.Collection<JClass> getLoadedClasses() {
            return List.of();
        }
    };

    private static final JClass LIST = new JClass(LOADER, "java.util.List");
    private static final JClass ARRAY_LIST = new JClass(LOADER, "java.util.ArrayList");
    private static final JClass MAP = new JClass(LOADER, "java.util.Map");
    private static final ClassType OBJECT = new ClassType(LOADER, "java.lang.Object");
    private static final ClassType STRING = new ClassType(LOADER, "java.lang.String");
    private static final ClassType ARRAY_LIST_TYPE = new ClassType(LOADER, "java.util.ArrayList");

    /** builds the statements of one method; a fresh var per value */
    private static final class Body {
        final List<Stmt> stmts = new ArrayList<>();
        final List<Var> vars = new ArrayList<>();
        final List<ExceptionEntry> handlers = new ArrayList<>();

        Var var(String name, Type type) {
            Var v = new Var(null, name, type, vars.size());
            vars.add(v);
            return v;
        }

        Var intConst(int value) {
            Var v = new Var(null, "%int" + vars.size(), IntType.INT, vars.size(), IntLiteral.get(value));
            vars.add(v);
            return v;
        }

        Var newList() {
            Var list = var("list", ARRAY_LIST_TYPE);
            stmts.add(new New(null, list, new NewInstance(ARRAY_LIST_TYPE)));
            MethodRef init = MethodRef.get(ARRAY_LIST, "<init>", List.of(), VoidType.VOID, false);
            stmts.add(new Invoke(null, new InvokeSpecial(init, list, List.of())));
            return list;
        }

        Invoke call(Var base, String name, List<Type> params, Type ret, Var result, Var... args) {
            MethodRef ref = MethodRef.get(LIST, name, params, ret, false);
            InvokeInstanceExp exp = new InvokeInterface(ref, base, List.of(args));
            Invoke invoke = result == null ? new Invoke(null, exp) : new Invoke(null, exp, result);
            stmts.add(invoke);
            return invoke;
        }

        Invoke add(Var list, Var element) {
            return call(list, "add", List.of(OBJECT), BooleanType.BOOLEAN, null, element);
        }

        Invoke remove(Var list, int index) {
            return call(list, "remove", List.of(IntType.INT), OBJECT, null, intConst(index));
        }

        Invoke get(Var list, Var index) {
            return call(list, "get", List.of(IntType.INT), OBJECT, var("x" + vars.size(), OBJECT), index);
        }

        IR ir() {
            for (int i = 0; i < stmts.size(); i++) {
                stmts.get(i).setIndex(i);
            }
            return new DefaultIR(null, null, List.of(), Set.of(), List.of(), stmts, handlers);
        }
    }

    // --- the benchmark idiom: safe, param, moresafe; remove(0); get(i) ---

    @Test
    void getAfterRemoveReturnsTheElementNowAtThatPosition() {
        Body b = new Body();
        Var list = b.newList();
        Invoke safe = b.add(list, b.var("safe", STRING));
        Invoke param = b.add(list, b.var("param", STRING));
        Invoke moreSafe = b.add(list, b.var("moresafe", STRING));
        b.remove(list, 0);
        Invoke get0 = b.get(list, b.intConst(0));
        Invoke get1 = b.get(list, b.intConst(1));
        Map<Invoke, String> keys = LocalLists.keys(b.ir());

        assertEquals(keys.get(param), keys.get(get0));
        assertEquals(keys.get(moreSafe), keys.get(get1));
        assertNotEquals(keys.get(get0), keys.get(get1));
        // the removed element keeps a key of its own: no get can see it
        assertTrue(keys.containsKey(safe));
        assertNotEquals(keys.get(safe), keys.get(get0));
        assertNotEquals(keys.get(safe), keys.get(get1));
    }

    // --- shapes that keep no keys ---

    @Test
    void aJumpInsideTheListCodeDropsTheKeys() {
        Body b = new Body();
        Var list = b.newList();
        b.add(list, b.var("a", STRING));
        Goto jump = new Goto();
        b.stmts.add(jump);
        Invoke get = b.get(list, b.intConst(0));
        jump.setTarget(get);
        assertTrue(LocalLists.keys(b.ir()).isEmpty());
    }

    @Test
    void aJumpIntoTheListCodeFromOutsideDropsTheKeys() {
        Body b = new Body();
        Var list = b.newList();
        Invoke add = b.add(list, b.var("a", STRING));
        b.get(list, b.intConst(0));
        b.stmts.add(new Return());
        Goto back = new Goto();   // a loop back to the add, after the list code
        back.setTarget(add);
        b.stmts.add(back);
        assertTrue(LocalLists.keys(b.ir()).isEmpty());
    }

    @Test
    void aHandlerInsideTheListCodeDropsTheKeys() {
        Body b = new Body();
        Var list = b.newList();
        Invoke add = b.add(list, b.var("a", STRING));
        Catch handler = new Catch(b.var("e", OBJECT));
        b.stmts.add(handler);
        b.get(list, b.intConst(0));
        b.handlers.add(new ExceptionEntry(add, handler, handler, OBJECT));
        assertTrue(LocalLists.keys(b.ir()).isEmpty());
    }

    @Test
    void aListPassedToAnotherCallKeepsNoKeys() {
        Body b = new Body();
        Var list = b.newList();
        b.add(list, b.var("a", STRING));
        Var other = b.var("other", ARRAY_LIST_TYPE);
        b.call(other, "addAll", List.of(OBJECT), BooleanType.BOOLEAN, null, list); // escapes
        b.get(list, b.intConst(0));
        assertTrue(LocalLists.keys(b.ir()).isEmpty());
    }

    @Test
    void aNonConstantIndexKeepsNoKeys() {
        Body b = new Body();
        Var list = b.newList();
        b.add(list, b.var("a", STRING));
        b.get(list, b.var("i", IntType.INT));
        assertTrue(LocalLists.keys(b.ir()).isEmpty());
    }

    @Test
    void anIndexPastTheEndKeepsNoKeys() {
        Body b = new Body();
        Var list = b.newList();
        b.add(list, b.var("a", STRING));
        b.remove(list, 0);
        b.get(list, b.intConst(0));
        assertTrue(LocalLists.keys(b.ir()).isEmpty());
    }

    // --- ContainerTaint: constant map keys and what a read sees ---

    @Test
    void mapKeyIsTheStringConstantOnly() {
        Var key = new Var(null, "%str", STRING, 0, StringLiteral.get("keyB"));
        Var value = new Var(null, "v", STRING, 1);
        MethodRef put = MethodRef.get(MAP, "put", List.of(OBJECT, OBJECT), OBJECT, false);
        Var map = new Var(null, "map", OBJECT, 2);
        assertEquals("keyB", ContainerTaint.constantKey(new InvokeInterface(put, map, List.of(key, value))));
        Var dynamic = new Var(null, "k", STRING, 3);
        assertNull(ContainerTaint.constantKey(new InvokeInterface(put, map, List.of(dynamic, value))));
    }

    @Test
    void aKeyedReadSeesItsKeyAndTheUnknownOnes() {
        ContainerTaint.Read keyed = new ContainerTaint.Read(null, "keyB");
        assertTrue(keyed.sees("keyB"));
        assertTrue(keyed.sees(null));
        assertTrue(!keyed.sees("keyA"));
        ContainerTaint.Read all = new ContainerTaint.Read(null, null);
        assertTrue(all.sees("keyA") && all.sees("keyB") && all.sees(null));
    }
}
