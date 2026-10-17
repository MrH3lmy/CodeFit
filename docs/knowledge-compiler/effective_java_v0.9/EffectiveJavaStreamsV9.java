// Original CodeFit language behavior fixtures. NOT learner graders; no publisher source.
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.*;

public final class EffectiveJavaStreamsV9 {
    private static int checks;
    private static void check(boolean condition, String caseName) {
        if (!condition) throw new AssertionError(caseName);
        checks++;
    }
    @FunctionalInterface
    interface RetryDecision {
        boolean mayRetry(int status, int attempts);
        default RetryDecision and(RetryDecision next) {
            return (code, n) -> this.mayRetry(code, n) && next.mayRetry(code, n);
        }
    }
    private static void item44() {
        IntPredicate strictlyPositive = n -> n > 0;
        check(!strictlyPositive.test(-1), "44-negative");
        check(!strictlyPositive.test(0), "44-zero");
        check(strictlyPositive.test(7), "44-positive");
        BiPredicate<String,Integer> sufficient = (name, n) -> !name.isBlank() && n >= 2;
        check(sufficient.test("a",2), "44-bi-happy");
        check(!sufficient.test("",2), "44-bi-empty");
        check(!sufficient.test("x",1), "44-bi-low");
        IntUnaryOperator doubled = n -> n * 2;
        check(doubled.applyAsInt(3)==6,"44-primitive-unary");
        check(doubled.applyAsInt(-2)==-4,"44-primitive-negative");
        ToLongFunction<String> measure = String::length;
        check(measure.applyAsLong("abc")==3L,"44-long-function");
        check(measure.applyAsLong("")==0L,"44-long-empty");
        Predicate<Integer> bounded = strictlyPositive::test;
        check(bounded.and(n -> n < 5).test(4),"44-predicate-compose");
        check(!bounded.and(n -> n < 5).test(9),"44-predicate-compose-limit");
        RetryDecision transientOnly = (code, attempts) -> code >= 500;
        RetryDecision budget = (code, attempts) -> attempts < 3;
        check(transientOnly.and(budget).mayRetry(503,2),"44-custom-allow");
        check(!transientOnly.and(budget).mayRetry(503,3),"44-custom-budget");
        check(!transientOnly.and(budget).mayRetry(409,1),"44-custom-reject");
        BinaryOperator<Integer> max = Integer::max;
        check(max.apply(2,8)==8,"44-binary-operator");
        Supplier<String> factory = () -> "ready";
        check(factory.get().equals("ready"),"44-supplier");
    }
    private static void item45() {
        AtomicInteger evaluated = new AtomicInteger();
        Stream<Integer> lazy = Stream.of(1,2,3).map(n -> {evaluated.incrementAndGet(); return n+1;});
        check(evaluated.get()==0,"45-lazy-no-terminal");
        check(lazy.toList().equals(List.of(2,3,4)),"45-terminal-list");
        check(evaluated.get()==3,"45-map-only-on-terminal");
        boolean reused=false;
        try {lazy.count();} catch (IllegalStateException expected) {reused=true;}
        check(reused,"45-one-shot");
        AtomicInteger shortCircuit=new AtomicInteger();
        OptionalInt first=IntStream.range(0,10).map(n -> {shortCircuit.incrementAndGet();return n;}).filter(n -> n >= 5).findFirst();
        check(first.orElseThrow()==5,"45-first-match");
        check(shortCircuit.get()==6,"45-short-circuit-count");
        List<Integer> flattened=Stream.<List<Integer>>of(List.of(1,2),List.of(),List.of(3)).flatMap(Collection::stream).toList();
        check(flattened.equals(List.of(1,2,3)),"45-flatten");
        check(Stream.<List<Integer>>empty().flatMap(Collection::stream).count()==0,"45-flat-empty");
        List<Integer> result=Stream.of(4,2,1).sorted().toList();
        check(result.equals(List.of(1,2,4)),"45-sort");
        boolean immutable=false;
        try {result.add(20);} catch(UnsupportedOperationException expected) {immutable=true;}
        check(immutable,"45-stream-tolist-unmodifiable");
        check("A".chars().findFirst().orElseThrow()==65,"45-chars-int-not-char");
        check("\uD83D\uDE00".chars().count()==2,"45-utf16-units");
        check("\uD83D\uDE00".codePoints().count()==1,"45-code-point");
        check(Stream.of("a","b").map(String::toUpperCase).collect(Collectors.joining(",")).equals("A,B"),"45-composition");
        check(IntStream.range(0,10).filter(n->n%2==0).limit(2).boxed().toList().equals(List.of(0,2)),"45-filter-limit");
        check(Stream.of(1,2,3).takeWhile(n->n<3).toList().equals(List.of(1,2)),"45-java9-takewhile");
        check(Stream.of(1,2,3).dropWhile(n->n<3).toList().equals(List.of(3)),"45-java9-dropwhile");
    }
    private static void item46() {
        boolean duplicate=false;
        try {Stream.of("aa","ab").collect(Collectors.toMap(String::length,Function.identity()));}
        catch(IllegalStateException expected) {duplicate=true;}
        check(duplicate,"46-duplicate-rejected");
        Map<Integer,String> joined=Stream.of("ax","by","z").collect(Collectors.toMap(String::length,Function.identity(),(a,b)->a+"/"+b));
        check(joined.get(2).equals("ax/by"),"46-explicit-merge");
        check(joined.get(1).equals("z"),"46-singleton");
        Map<String,Long> counts=Stream.of("ok","fail","ok","ok").collect(Collectors.groupingBy(Function.identity(),Collectors.counting()));
        check(counts.get("ok")==3L,"46-count-ok");
        check(counts.get("fail")==1L,"46-count-fail");
        check(counts.size()==2,"46-count-size");
        check(Stream.of("a","b","c").collect(Collectors.joining("|", "[", "]")).equals("[a|b|c]"),"46-join");
        check(Stream.<String>empty().collect(Collectors.joining(",")).isEmpty(),"46-join-empty");
        check(Stream.of(1,2,3).reduce(0,Integer::sum)==6,"46-associative-reduce");
        check(IntStream.rangeClosed(1,100).parallel().sum()==5050,"46-parallel-sum");
        SortedSet<Integer> sorted=Stream.of(4,1,3,1).collect(Collectors.toCollection(TreeSet::new));
        check(sorted.equals(new TreeSet<>(List.of(1,3,4))),"46-explicit-collection");
        check(sorted.first()==1,"46-collection-first");
        boolean rejectsNull=false;
        try {Stream.of("a",null).collect(Collectors.toUnmodifiableList());}
        catch(NullPointerException expected){rejectsNull=true;}
        check(rejectsNull,"46-unmodifiable-null-rejected");
        check(Stream.of("a",null).toList().size()==2,"46-stream-tolist-null-accepted");
        check(Stream.of("a",null).toList().get(1)==null,"46-stream-tolist-null-position");
        Map<Boolean,Long> partitions=Stream.of(1,2,3,4).collect(Collectors.partitioningBy(n->n%2==0,Collectors.counting()));
        check(partitions.get(true)==2L,"46-even-partition");
        check(partitions.get(false)==2L,"46-odd-partition");
    }
    private static void item47() {
        List<String> page=List.of("p1","p2");
        check(page.size()==2,"47-finite-collection");
        check(page.stream().count()==2,"47-stream-method");
        check(page.stream().map(String::length).reduce(0,Integer::sum)==4,"47-repeat-stream");
        Iterable<Integer> iterable=()->List.of(3,4,5).iterator();
        int total=0;for(int n:iterable) total+=n;
        check(total==12,"47-iterable-foreach");
        check(StreamSupport.stream(iterable.spliterator(),false).count()==3,"47-stream-adapter");
        check(StreamSupport.stream(iterable.spliterator(),false).filter(n->n>3).count()==2,"47-adapter-filter");
        check(page.getFirst().equals("p1"),"47-jdk21-sequenced-first");
        check(page.getLast().equals("p2"),"47-jdk21-sequenced-last");
        AtomicInteger closed=new AtomicInteger();
        try(Stream<Integer> cursor=Stream.of(1,2).onClose(closed::incrementAndGet)){
            check(cursor.count()==2,"47-cursor-count");
            check(closed.get()==0,"47-not-closed-before-scope");
        }
        check(closed.get()==1,"47-close-hook");
        Collection<String> empty=List.of();
        check(empty.stream().findAny().isEmpty(),"47-empty-collection");
        check(empty.isEmpty(),"47-empty-size");
    }
    private static void item48() {
        int sum=IntStream.rangeClosed(1,1000).sum();
        int parallel=IntStream.rangeClosed(1,1000).parallel().sum();
        check(sum==500500,"48-sequential-sum");
        check(parallel==sum,"48-parallel-equal");
        check(IntStream.rangeClosed(1,1000).parallel().reduce(0,Integer::sum)==sum,"48-associative-reduction");
        check(IntStream.rangeClosed(1,1000).parallel().count()==1000,"48-count");
        check(IntStream.rangeClosed(1,50).parallel().findFirst().orElseThrow()==1,"48-findfirst-ordered");
        int any=IntStream.rangeClosed(1,50).parallel().findAny().orElseThrow();
        check(any>=1 && any<=50,"48-findany-membership");
        List<Integer> ordered=new ArrayList<>();
        IntStream.rangeClosed(1,15).parallel().forEachOrdered(ordered::add);
        check(ordered.size()==15,"48-foreachordered-count");
        check(ordered.getFirst()==1 && ordered.getLast()==15,"48-foreachordered-boundaries");
        check(ordered.equals(IntStream.rangeClosed(1,15).boxed().toList()),"48-foreachordered-encounter-order");
        check(IntStream.range(0,10).parallel().isParallel(),"48-parallel-flag");
        check(!IntStream.range(0,10).isParallel(),"48-sequential-flag");
        Spliterator.OfInt split=IntStream.range(0,1000).spliterator();
        check(split.hasCharacteristics(Spliterator.SIZED),"48-sized-spliterator");
        check(split.hasCharacteristics(Spliterator.SUBSIZED),"48-subsized-spliterator");
        check(split.trySplit()!=null,"48-splittable-range");
        check(IntStream.range(0,1000).parallel().filter(n->n%2==0).count()==500,"48-parallel-filter");
        check(IntStream.range(0,1000).parallel().boxed().collect(Collectors.groupingByConcurrent(n->n%5,Collectors.counting())).get(0)==200L,"48-concurrent-grouping");
        check(LongStream.rangeClosed(1,500).parallel().reduce(0L,Long::sum)==125250L,"48-long-reduce");
    }
    public static void main(String[] args) {
        item44();item45();item46();item47();item48();
        System.out.println("PASS EffectiveJavaStreamsV9 assertions="+checks);
    }
}
