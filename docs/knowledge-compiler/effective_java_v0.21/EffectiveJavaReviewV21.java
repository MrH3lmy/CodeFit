import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Original adversarial author tests; not independent review or a learner grader. */
public final class EffectiveJavaReviewV21 {
    private static int assertions;
    private static void check(boolean value, String what) {
        assertions++;
        if (!value) throw new AssertionError("assertion " + assertions + ": " + what);
    }

    // Item 19: constructor dispatch and overridable self-use.
    static class Parent {
        int observed = -1;
        Parent() { observed = virtual(); }
        int virtual() { return -2; }
    }
    static final class Child extends Parent {
        final int initialized;
        Child(int value) { initialized = value; }
        @Override int virtual() { return initialized; }
    }
    static class CountingBase {
        int count;
        void put(String value) { count++; }
        void putAll(String... values) { for (String v : values) put(v); }
    }
    static final class CountingOverride extends CountingBase {
        @Override void put(String v) { count++; super.put(v); }
    }

    // Item 20: two unrelated capabilities alongside an existing superclass.
    static class FrameworkBase { String framework() { return "ready"; } }
    interface Encode { String encode(String s); }
    interface Observe { int events(); }
    static final class Multipurpose extends FrameworkBase implements Encode, Observe {
        @Override public String encode(String s) { return s.toUpperCase(Locale.ROOT); }
        @Override public int events() { return 3; }
    }
    interface Alpha { default String method() { return "alpha"; } }
    interface Beta { default String method() { return "beta"; } }
    static final class ExplicitResolution implements Alpha, Beta {
        @Override public String method() { return Alpha.super.method() + "+" + Beta.super.method(); }
    }

    // Item 21: each primitive invocation locked, but no whole-batch lock.
    interface BatchApi {
        void apply(String s);
        default void applyBatch(String a, String b) { apply(a); apply(b); }
    }
    static final class LockedAdapter implements BatchApi {
        final Object guard = new Object();
        int calls;
        boolean everyPrimitiveWasLocked = true;
        @Override public void apply(String s) {
            synchronized (guard) {
                calls++;
                everyPrimitiveWasLocked &= Thread.holdsLock(guard);
            }
        }
        boolean batchMethodHoldsLock() {
            // This probe models entry into the inherited default body, not the primitive calls.
            return Thread.holdsLock(guard);
        }
    }

    // Item 22: inherited interface constant is part of visible type surface.
    interface LegacyConstants { int MAGIC = 9; }
    static class LegacyHandler implements LegacyConstants { }
    enum Outcome { APPROVED, DECLINED }

    // Item 23: sealed does not auto-validate; constructors have to enforce invariants.
    sealed interface Payment permits UnsafeApproval, CheckedApproval, Rejection { }
    record UnsafeApproval(int amount) implements Payment { }
    record CheckedApproval(int amount) implements Payment {
        CheckedApproval { if (amount <= 0) throw new IllegalArgumentException("amount"); }
    }
    record Rejection(String cause) implements Payment { }
    static String verdict(Payment payment) {
        return switch (payment) {
            case UnsafeApproval a -> "unsafe:" + a.amount();
            case CheckedApproval a -> "ok:" + a.amount();
            case Rejection r -> "reject:" + r.cause();
        };
    }
    record Container(byte[] payload) { }

    // Item 24: Java 21 javac may omit synthetic enclosing field when never accessed.
    static class Owner {
        final byte[] large = new byte[32];
        class NoOuterAccess { int answer() { return 7; } }
        class UsesOuter { int size() { return large.length; } }
        static class Detached { int answer() { return 7; } }
    }
    static boolean hasOuterField(Class<?> klass) {
        for (Field field : klass.getDeclaredFields()) {
            if (field.getType() == Owner.class && !Modifier.isStatic(field.getModifiers())) return true;
        }
        return false;
    }
    static boolean hasOuterParameter(Class<?> klass) {
        for (Constructor<?> c : klass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length > 0 && p[0] == Owner.class) return true;
        }
        return false;
    }

    // Item 25: multiple distinct package-private declarations ARE legal in one file.
    static String distinctTypes() { return LocalHelperOne.name() + LocalHelperTwo.name(); }

    public static void main(String[] ignored) {
        Child child = new Child(42);
        check(child.observed == 0, "virtual call saw pre-initialization field value");
        check(child.initialized == 42, "later child field initialization");
        check(child.virtual() == 42, "virtual call after construction");
        CountingOverride doubled = new CountingOverride();
        doubled.putAll("a", "b");
        check(doubled.count == 4, "overridable self-use doubles count");

        Multipurpose m = new Multipurpose();
        check(m instanceof Encode, "capability one");
        check(m instanceof Observe, "capability two");
        check(m.framework().equals("ready"), "retain required base class");
        check(m.encode("ab").equals("AB"), "independent interface method");
        check(m.events() == 3, "second independent method");
        check(new ExplicitResolution().method().equals("alpha+beta"), "explicit conflicting-default resolution");

        LockedAdapter locked = new LockedAdapter();
        check(!locked.batchMethodHoldsLock(), "inherited batch not under shared guard");
        locked.applyBatch("a", "b");
        check(locked.calls == 2, "both primitive calls executed");
        check(locked.everyPrimitiveWasLocked, "each primitive independently held guard");
        check(!Thread.holdsLock(locked.guard), "guard released between calls and after batch");

        check(LegacyHandler.MAGIC == 9, "constant name inherited into implementing class");
        check(LegacyConstants.MAGIC == 9, "constant remains accessible at interface");
        check(Outcome.APPROVED != Outcome.DECLINED, "typed enum choices");

        UnsafeApproval invalid = new UnsafeApproval(-9);
        check(invalid.amount() < 0, "record construction alone does not enforce domain value");
        check(verdict(invalid).equals("unsafe:-9"), "sealed hierarchy permits invalid record state");
        boolean rejected = false;
        try { new CheckedApproval(-9); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "compact canonical record constructor enforces domain invariant");
        check(verdict(new CheckedApproval(4)).equals("ok:4"), "valid record state");
        check(verdict(new Rejection("quota")).equals("reject:quota"), "exhaustive sealed switch rejection");
        byte[] original = new byte[] {1};
        Container mutable = new Container(original);
        original[0] = 8;
        check(mutable.payload()[0] == 8, "record containing mutable array is not deeply immutable");

        Owner owner = new Owner();
        check(owner.new NoOuterAccess().answer() == 7, "nonstatic member can be instantiated");
        check(owner.new UsesOuter().size() == 32, "nonstatic member accesses outer state");
        check(new Owner.Detached().answer() == 7, "static member class needs no owner");
        check(!hasOuterField(Owner.NoOuterAccess.class), "Java21 javac elides unused outer field");
        check(hasOuterField(Owner.UsesOuter.class), "used outer state retains synthetic field");
        check(!hasOuterField(Owner.Detached.class), "static nested lacks outer field");
        check(hasOuterParameter(Owner.NoOuterAccess.class), "nonstatic constructor still needs enclosing instance");
        check(hasOuterParameter(Owner.UsesOuter.class), "outer-state constructor needs enclosing instance");
        check(!hasOuterParameter(Owner.Detached.class), "static constructor requires no enclosing instance");
        check(distinctTypes().equals("AB"), "distinct package-private top-level classes can share source file");
        System.out.println("AUTHOR_REVIEW_V21_ASSERTIONS=" + assertions);
    }
}
class LocalHelperOne { static String name() { return "A"; } }
class LocalHelperTwo { static String name() { return "B"; } }