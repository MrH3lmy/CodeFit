import java.time.Duration;
import java.util.*;
import java.util.function.*;

/** Original Java 21 generics behavior probes. Not player graders. */
public final class EffectiveJavaGenericsV6 {
    private static int checks;
    private static void ok(boolean value, String description) {
        if (!value) throw new AssertionError("GENERIC_" + checks + ": " + description);
        checks++;
    }
    private static void throwsType(Class<? extends Throwable> expected, Runnable action) {
        try { action.run(); }
        catch (Throwable ex) { ok(expected.isInstance(ex), "wrong exception type: " + ex); return; }
        throw new AssertionError("expected " + expected);
    }
    private static int count(Collection<?> incoming) { return incoming.size(); }
    private static void appendText(Collection<? super String> target, String text) { target.add(text); }
    private static <T> T firstOf(List<T> items) { return items.get(0); }
    private static final class GenericStack<E> {
        private final List<E> data = new ArrayList<>();
        void push(E v) { data.add(Objects.requireNonNull(v)); }
        E pop() { if (data.isEmpty()) throw new NoSuchElementException(); return data.remove(data.size()-1); }
        int size() { return data.size(); }
    }
    private static <T extends Comparable<? super T>> T earliest(T a, T b) { return a.compareTo(b) <= 0 ? a : b; }
    private static <T> Set<T> together(Set<? extends T> a, Set<? extends T> b) {
        Set<T> r = new HashSet<>();r.addAll(a);r.addAll(b);return r;
    }
    private static <T> void transfer(List<? extends T> source, List<? super T> sink) { sink.addAll(source); }
    private static <T> T smallestBy(T a, T b, Comparator<? super T> c) { return c.compare(a,b)<=0?a:b; }
    @SafeVarargs
    private static <T> List<T> combine(List<? extends T>... inputs) {
        List<T> result = new ArrayList<>();
        for (List<? extends T> input : inputs) result.addAll(input);
        return List.copyOf(result);
    }
    private static final class TypedContext {
        private final Map<Class<?>, Object> values = new HashMap<>();
        <T> void put(Class<T> key, T value) { values.put(key, key.cast(value)); }
        <T> T get(Class<T> key) { return key.cast(values.get(key)); }
    }
    private record Event(String id) {}
    private record SpecialEvent(String id) {}
    private static final class Value implements Comparable<Value> {
        final int n;
        Value(int n) {this.n=n;}
        public int compareTo(Value other){return Integer.compare(n,other.n);}
    }
    public static void main(String[] args) {
        // Item 26: wildcard read, consumers of supertypes, retained types
        ok(count(List.of("alpha", "beta"))==2,"unknown element list count");
        ok(count(List.of(4L))==1,"unknown numeric list count");
        List<Object> recipients = new ArrayList<>();
        appendText(recipients,"event");ok(recipients.size()==1,"supertype sink accepts string");
        ok("event".equals(recipients.get(0)),"supertype sink preserves value");
        ok("x".equals(firstOf(List.of("x","y"))),"generic return String");
        ok(firstOf(List.of(7))==7,"generic return Integer");
        List<?> any = List.of("entry");ok(any.get(0) instanceof String,"wildcard read as Object");
        // Item 28: runtime array covariance vs collection invariant
        Object[] covariant = new Number[2];
        covariant[0] = 18L;ok(covariant[0].equals(18L),"permitted array store");
        throwsType(ArrayStoreException.class, () -> covariant[1]="not a number");
        List<Number> numberList = new ArrayList<>();numberList.add(19L);numberList.add(4.0);
        ok(numberList.get(0).intValue()==19,"number list accepts subclasses");
        ok(numberList.get(1).doubleValue()==4.0,"number list second subtype");
        // Item 29: no caller casts and consistent element type
        GenericStack<Event> stack=new GenericStack<>();
        ok(stack.size()==0,"empty new stack");stack.push(new Event("ev1"));stack.push(new Event("ev2"));
        ok(stack.size()==2,"size after two pushes");
        ok(stack.pop().id().equals("ev2"),"LIFO last first");
        ok(stack.pop().id().equals("ev1"),"LIFO first last");
        ok(stack.size()==0,"empty after two pops");
        throwsType(NoSuchElementException.class,stack::pop);
        throwsType(NullPointerException.class,()->stack.push(null));
        // Item 30: generic methods and comparable constraints
        ok(earliest(new Value(9),new Value(4)).n==4,"bounded comparable chooses earlier");
        ok(earliest(new Value(2),new Value(4)).n==2,"bounded comparable chooses left");
        ok(earliest("aa","ab").equals("aa"),"bound works for String");
        ok(together(Set.of("a"),Set.of("b")).size()==2,"typed union");
        ok(together(Set.of("a"),Set.of("a")).size()==1,"union eliminates duplicates");
        // Item 31: PECS producer/consumer, comparator supertype
        List<Integer> src=List.of(1,2,3);List<Number> dest=new ArrayList<>();
        transfer(src,dest);ok(dest.size()==3,"compatible consumer sink");
        ok(dest.get(0).intValue()==1,"first transferred value");
        ok(dest.get(2).intValue()==3,"last transferred value");
        Comparator<Number> numbers = Comparator.comparingDouble(Number::doubleValue);
        ok(smallestBy(2,3,numbers)==2,"supertype comparator accepted");
        ok(smallestBy(8,3,numbers)==3,"comparator picks second");
        List<Object> heterogeneous=new ArrayList<>();transfer(List.of("a","b"),heterogeneous);
        ok(heterogeneous.equals(List.of("a","b")),"Object consumer accepts subtype producer");
        // Item 32: varargs array not written to/returned or escaped
        List<String> one=new ArrayList<>(List.of("r1"));List<String> two=List.of("r2","r3");
        List<String> combined=combine(one,two);ok(combined.size()==3,"safe varargs count");
        ok(combined.equals(List.of("r1","r2","r3")),"safe varargs order");
        one.set(0,"changed");ok(combined.get(0).equals("r1"),"result snapshot not aliased");
        throwsType(UnsupportedOperationException.class,()->combined.add("r4"));
        ok(combine(List.of(),List.of()).isEmpty(),"safe varargs empty");
        List<Number> mixed=combine(List.<Integer>of(4),List.<Double>of(2.5));
        ok(mixed.size()==2,"variance under varargs");
        ok(mixed.get(1).doubleValue()==2.5,"mixed numeric value preserved");
        // Item 33: heterogeneous typed values and null/missing semantics
        TypedContext ctx=new TypedContext();
        ctx.put(String.class,"java");ctx.put(Duration.class,Duration.ofMinutes(7));
        ctx.put(UUID.class,UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ok("java".equals(ctx.get(String.class)),"String.class typed fetch");
        ok(ctx.get(Duration.class).toMinutes()==7,"Duration.class typed fetch");
        ok(ctx.get(UUID.class).getLeastSignificantBits()==1,"UUID.class typed fetch");
        ok(ctx.get(Integer.class)==null,"missing token yields null");
        ctx.put(String.class,"overwrite");ok(ctx.get(String.class).equals("overwrite"),"typed overwrite");
        System.out.println("EffectiveJavaGenericsV6 checks="+checks+" PASS");
    }
}