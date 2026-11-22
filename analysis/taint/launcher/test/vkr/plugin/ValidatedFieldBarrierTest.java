package vkr.plugin;

import org.junit.jupiter.api.Test;
import pascal.taie.ir.exp.InstanceFieldAccess;
import pascal.taie.ir.exp.StaticFieldAccess;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.LoadField;
import pascal.taie.ir.stmt.Nop;
import pascal.taie.ir.stmt.Return;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JClassLoader;
import pascal.taie.language.type.ClassType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard decisions of {@link ValidatedFieldBarrier} that need no pointer analysis: the
 * conservative regex check behind every {@code @Pattern} / {@code matches} filter, and the strict
 * {@code return this.<field>;} accessor shape behind the whole-DTO-route filter (TCI-4).
 */
class ValidatedFieldBarrierTest {

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

    // --- regex-literal safety (admitsNoDangerousChar) ---

    @Test
    void admitsThePatternsOfTheValidatedDtoFields() {
        // CouponRequest.code, ProductRequest.sku, OrderServiceImpl.IDEMPOTENCY_KEY,
        // CorrelationIdUtils.SAFE_VALUE, MockCardPaymentRequest card number
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^[A-Za-z0-9_-]{3,30}$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^[A-Z0-9][A-Z0-9-]{2,63}$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^[A-Za-z0-9_-]{8,100}$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^[A-Za-z0-9._-]+$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^\\+7\\d{10}$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^\\d{12,19}$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^(0[1-9]|1[0-2])/\\d{2}$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("^[a-z0-9]+(?:-[a-z0-9]+)*$"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar(""));
    }

    @Test
    void admitsOnlyUnderstoodConstructs() {
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("cat|dog"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("a{2,4}b?c*"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("\\.\\-\\/"));
        assertTrue(ValidatedFieldBarrier.admitsNoDangerousChar("[A-Za-z0-9_-]"));
    }

    @Test
    void rejectsConstructsThatMayMatchDangerousChars() {
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar(".")); // any char
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar(".*"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("[^a-z]")); // negated class
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\\s")); // whitespace incl. line breaks
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\\W"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\\S"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\\p{L}"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("(?=x)")); // lookahead
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("(?!x)")); // negative lookahead
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("(?<name>x)")); // named group
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("a{2,}x{b}")); // malformed quantifier
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("[abc")); // unterminated class
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("[a&&b]")); // class intersection
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("[\\d-\\u2029]")); // range to a line break
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("[ -~]")); // range over quote
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("abc\\")); // dangling escape
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\\n")); // escaped line break
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\\x20"));
    }

    @Test
    void rejectsLiteralsWithInjectionChars() {
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("'"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("\""));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("`"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar(";"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("a\r\nb"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("a\u0085b"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("a\u2028b"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("a\u2029b"));
        assertFalse(ValidatedFieldBarrier.admitsNoDangerousChar("it's[done]")); // even inside a class
    }

    // --- accessor shape (return this.<field>;) ---

    private static LoadField loadOf(Var base) {
        JClass cls = new JClass(LOADER, "com.shop.Dto");
        ClassType str = new ClassType(LOADER, "java.lang.String");
        FieldRef ref = FieldRef.get(cls, "code", str, false);
        Var value = new Var(null, "x", str, -1);
        return new LoadField(value, new InstanceFieldAccess(ref, base));
    }

    private static LoadField staticLoad() {
        JClass cls = new JClass(LOADER, "com.shop.Dto");
        ClassType str = new ClassType(LOADER, "java.lang.String");
        FieldRef ref = FieldRef.get(cls, "CODE", str, true);
        Var value = new Var(null, "x", str, -1);
        return new LoadField(value, new StaticFieldAccess(ref));
    }

    @Test
    void acceptsThePlainFieldAccessorShape() {
        Var thisVar = new Var(null, "%this", null, 0);
        LoadField load = loadOf(thisVar);
        assertEquals(load, ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(load, new Return(load.getLValue()))));
        // a stray Nop carries no behaviour
        assertEquals(load, ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(new Nop(), load, new Nop(), new Return(load.getLValue()))));
    }

    @Test
    void rejectsShapesThatAreNotAPlainFieldAccessor() {
        Var thisVar = new Var(null, "%this", null, 0);
        Var other = new Var(null, "%other", null, 1);
        LoadField load = loadOf(thisVar);
        // a static field, a field of another object, or a computed second access
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(staticLoad(), new Return(staticLoad().getLValue()))));
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(loadOf(other), new Return(loadOf(other).getLValue()))));
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(load, loadOf(thisVar))));
        // the returned value is not the loaded one
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(load, new Return(new Var(null, "y", null, -2)))));
        // void return, missing return, statement after the return
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar, List.of(load, new Return())));
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar, List.of(load)));
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar,
                List.of(load, new Return(load.getLValue()), load)));
        // return without a preceding load
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar, List.of(new Return(other))));
        // empty body
        assertNull(ValidatedFieldBarrier.accessorLoad(thisVar, List.<Stmt>of()));
    }
}
