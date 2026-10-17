// Original CodeFit technical-fact fixtures: Java 21, NOT learner graders and NOT copied book code.
import java.util.*;
import java.util.function.*;

public final class EffectiveJavaMethodsV10 {
    private static int assertions;
    private static void ok(boolean yes, String name) {
        if (!yes) throw new AssertionError(name);
        assertions++;
    }
    private static void fails(Class<? extends Throwable> type, Runnable operation, String name) {
        try { operation.run(); }
        catch (Throwable error) {if (type.isInstance(error)) { assertions++; return; }
            throw new AssertionError(name + " wrong exception: " + error, error);}
        throw new AssertionError(name + " did not fail");
    }
    private enum CaptureMode { HOLD, RELEASE }
    private record PaymentRequest(String orderId, int cents, CaptureMode mode) {
        PaymentRequest {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(mode, "mode");
            if (orderId.isBlank()) throw new IllegalArgumentException("blank ID");
            if (cents <= 0) throw new IllegalArgumentException("nonpositive amount");
        }
    }
    private static final class LedgerWindow {
        private final String ledgerId;
        private final int from, size;
        LedgerWindow(String ledgerId, int from, int size, int total) {
            this.ledgerId=Objects.requireNonNull(ledgerId,"ledgerId");
            Objects.checkFromIndexSize(from,size,total);
            this.from=from; this.size=size;
        }
        boolean isEmpty() { return size==0; }
        int lastExclusive(){return from+size;}
    }
    private static void item49() {
        ok(Objects.requireNonNull("ok").equals("ok"),"49-nonnull");
        fails(NullPointerException.class,()->Objects.requireNonNull(null),"49-null");
        ok(Objects.checkIndex(0,3)==0,"49-index-begin");
        ok(Objects.checkIndex(2,3)==2,"49-index-end-valid");
        fails(IndexOutOfBoundsException.class,()->Objects.checkIndex(-1,3),"49-index-negative");
        fails(IndexOutOfBoundsException.class,()->Objects.checkIndex(3,3),"49-index-exclusive");
        ok(Objects.checkFromToIndex(2,2,3)==2,"49-empty-middle-window");
        ok(Objects.checkFromToIndex(3,3,3)==3,"49-empty-end-window");
        fails(IndexOutOfBoundsException.class,()->Objects.checkFromToIndex(3,2,3),"49-range-reversed");
        ok(Objects.checkFromIndexSize(2,0,3)==2,"49-zero-length-at-middle");
        ok(Objects.checkFromIndexSize(3,0,3)==3,"49-zero-length-at-end");
        fails(IndexOutOfBoundsException.class,()->Objects.checkFromIndexSize(1,3,3),"49-range-too-long");
        fails(IndexOutOfBoundsException.class,()->Objects.checkFromIndexSize(Integer.MAX_VALUE,4,12),"49-overflow-safe-check");
        fails(NullPointerException.class,()->new LedgerWindow(null,0,0,0),"49-ctor-null");
        fails(IndexOutOfBoundsException.class,()->new LedgerWindow("x",-1,1,3),"49-ctor-negative");
        ok(new LedgerWindow("x",3,0,3).isEmpty(),"49-ctor-empty");
        ok(new LedgerWindow("x",1,2,3).lastExclusive()==3,"49-last-exclusive");
    }
    private static String describe(Map<String,Integer> counter){ return counter.size()+":"+counter.getOrDefault("ok",0);}
    private static String choose(CaptureMode mode){return switch(mode){case HOLD->"hold";case RELEASE->"release";};}
    private static void item51(){
        PaymentRequest req=new PaymentRequest("evt-21",200,CaptureMode.HOLD);
        ok(req.orderId().equals("evt-21"),"51-record-id");
        ok(req.cents()==200,"51-record-cents");
        ok(req.mode()==CaptureMode.HOLD,"51-record-mode");
        fails(NullPointerException.class,()->new PaymentRequest("evt",5,null),"51-record-null-mode");
        fails(NullPointerException.class,()->new PaymentRequest(null,5,CaptureMode.HOLD),"51-record-null-id");
        fails(IllegalArgumentException.class,()->new PaymentRequest(" ",5,CaptureMode.HOLD),"51-record-blank-id");
        fails(IllegalArgumentException.class,()->new PaymentRequest("evt",0,CaptureMode.RELEASE),"51-record-zero");
        fails(IllegalArgumentException.class,()->new PaymentRequest("evt",-1,CaptureMode.HOLD),"51-record-negative");
        ok(describe(new HashMap<>(Map.of("ok",2))).equals("1:2"),"51-hashmap-as-map");
        ok(describe(new TreeMap<>(Map.of("ok",3))).equals("1:3"),"51-treemap-as-map");
        ok(describe(Collections.emptyMap()).equals("0:0"),"51-empty-interface");
        ok(choose(CaptureMode.HOLD).equals("hold"),"51-typed-hold");
        ok(choose(CaptureMode.RELEASE).equals("release"),"51-typed-release");
    }
    private static String routing(Collection<?> data) { return "general"; }
    private static String routing(Set<?> data) { return "set"; }
    private interface Selector { String selected(); }
    private static class BaseSelector implements Selector { public String selected(){return "base";} }
    private static final class SpecificSelector extends BaseSelector { @Override public String selected(){return "specific";} }
    private static void item52(){
        Collection<String> staticCollection=new LinkedHashSet<>(List.of("x"));
        ok(routing(staticCollection).equals("general"),"52-static-overload");
        ok(routing((Set<?>)staticCollection).equals("set"),"52-explicit-cast");
        ok(routing(new HashSet<>(List.of("x"))).equals("set"),"52-known-static-set");
        ok(routing(new ArrayList<>(List.of("x"))).equals("general"),"52-known-list");
        Selector actual=new SpecificSelector();
        ok(actual.selected().equals("specific"),"52-override-dynamic");
        BaseSelector typedAsBase=new SpecificSelector();
        ok(typedAsBase.selected().equals("specific"),"52-override-base-reference");
        List<Integer> numbers=new ArrayList<>(List.of(2,4,6));
        ok(numbers.remove(0).equals(2),"52-index-removal");
        ok(numbers.size()==2,"52-size-after-index");
        ok(numbers.remove(Integer.valueOf(6)),"52-object-remove");
        ok(numbers.equals(List.of(4)),"52-element-remaining");
        ok(!numbers.remove(Integer.valueOf(6)),"52-missing-value");
        Set<Integer> set=new HashSet<>(List.of(4,6));
        ok(set.remove(4),"52-set-removes-value");
        ok(set.equals(Set.of(6)),"52-set-result");
    }
    private static List<String> batch(String first, String... rest){
        Objects.requireNonNull(first,"first");Objects.requireNonNull(rest,"rest");
        ArrayList<String> values=new ArrayList<>(); values.add(first);
        for (String s : rest) values.add(Objects.requireNonNull(s,"element"));
        return List.copyOf(values);
    }
    private static int total(int... values){int sum=0;for(int n:values)sum+=n;return sum;}
    private static void item53(){
        ok(batch("a").equals(List.of("a")),"53-required-first");
        ok(batch("a","b").equals(List.of("a","b")),"53-two");
        ok(batch("a","b","c").size()==3,"53-three");
        ok(total()==0,"53-zero-arity-valid-for-sum");
        ok(total(5)==5,"53-one-arity");
        ok(total(1,2,3)==6,"53-many-arity");
        ok(total(new int[]{1,2})==3,"53-explicit-array");
        fails(NullPointerException.class,()->batch(null),"53-null-first");
        fails(NullPointerException.class,()->batch("a",(String[])null),"53-null-array");
        fails(NullPointerException.class,()->batch("a",new String[]{null}),"53-null-element");
        String[] mutable={"a"};List<String> copy=batch("z",mutable);mutable[0]="changed";
        ok(copy.get(1).equals("a"),"53-varargs-defensive-copy");
        fails(UnsupportedOperationException.class,()->copy.add("mutate"),"53-unmodifiable-output");
    }
    private static List<String> results(boolean empty){return empty?List.of():List.copyOf(List.of("settled","pending"));}
    private static String[] resultArray(boolean empty){return results(empty).toArray(String[]::new);}
    private static void item54(){
        ok(results(true)!=null,"54-not-null");
        ok(results(true).isEmpty(),"54-empty-list");
        ok(results(false).size()==2,"54-nonempty-list");
        ok(results(false).contains("pending"),"54-known-element");
        ok(!results(true).iterator().hasNext(),"54-safe-iteration");
        fails(UnsupportedOperationException.class,()->results(true).add("x"),"54-empty-immutable");
        fails(UnsupportedOperationException.class,()->results(false).add("x"),"54-nonempty-immutable");
        ok(resultArray(true).length==0,"54-empty-array");
        ok(resultArray(false).length==2,"54-two-elements-array");
        ok(resultArray(false)[0].equals("settled"),"54-first-array");
        ok(results(true).toArray(new String[0]).length==0,"54-typed-zero-array");
        ok(results(false).toArray(String[]::new).length==2,"54-generator-array");
        ok(Collections.<String>emptyList().isEmpty(),"54-shared-immutable-empty");
        ok(Collections.<String,String>emptyMap().isEmpty(),"54-empty-map");
        ok(Collections.<String>emptySet().isEmpty(),"54-empty-set");
    }
    public static void main(String[] args) {
        item49();item51();item52();item53();item54();
        System.out.println("CodeFit Java21 original method-contract assertions="+assertions);
    }
}
