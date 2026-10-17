// Original CodeFit Java 21 behavior controls for Effective Java 60–64.
// NOT publisher code. NOT a held-out learner grader. NOT a production component.
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public final class EffectiveJavaGeneralTypesV12 {
  private static int checks;
  private static void ok(boolean p, String id) { if (!p) throw new AssertionError(id); checks++; }
  private static void rejects(Class<? extends Throwable> type, Runnable task, String id) {
    try { task.run(); }
    catch (Throwable err) { if (type.isInstance(err)) { checks++;return; } throw new AssertionError(id+" other error",err); }
    throw new AssertionError(id+" should reject");
  }
  private enum CurrencyCode { USD, EUR }
  private record MoneyAmount(CurrencyCode currency, long minor) {
    MoneyAmount { Objects.requireNonNull(currency, "currency"); }
    MoneyAmount plus(MoneyAmount other) {
      Objects.requireNonNull(other, "other");
      if (currency != other.currency) throw new IllegalArgumentException("currency mismatch");
      return new MoneyAmount(currency, Math.addExact(minor, other.minor));
    }
    BigDecimal amount() { return BigDecimal.valueOf(minor, 2); }
  }
  private static void item60() {
    BigDecimal price = new BigDecimal("0.10");
    ok(price.add(new BigDecimal("0.20")).compareTo(new BigDecimal("0.30"))==0,"60-decimal-exact");
    ok(price.scale()==2,"60-scale");
    ok(new BigDecimal("1.0").compareTo(new BigDecimal("1.00"))==0,"60-compareTo-value");
    ok(!new BigDecimal("1.0").equals(new BigDecimal("1.00")),"60-equals-scale");
    ok(new BigDecimal("1.00").setScale(1,RoundingMode.UNNECESSARY).equals(new BigDecimal("1.0")),"60-explicit-scale");
    ok(BigDecimal.valueOf(0.1).compareTo(new BigDecimal("0.1"))==0,"60-valueOf-decimal-string");
    ok(new BigDecimal(0.1).compareTo(new BigDecimal("0.1"))!=0,"60-raw-double-origin-drift");
    ok(new BigDecimal("1").divide(new BigDecimal("8")).compareTo(new BigDecimal("0.125"))==0,"60-terminating-division");
    rejects(ArithmeticException.class,()->new BigDecimal("1").divide(new BigDecimal("3")),"60-nonterminating-division");
    ok(new BigDecimal("1").divide(new BigDecimal("3"),2,RoundingMode.HALF_UP).equals(new BigDecimal("0.33")),"60-rounding-policy");
    ok(new MoneyAmount(CurrencyCode.USD,150).plus(new MoneyAmount(CurrencyCode.USD,75)).minor()==225,"60-add-minor");
    ok(new MoneyAmount(CurrencyCode.USD,-50).plus(new MoneyAmount(CurrencyCode.USD,75)).minor()==25,"60-negative-minor");
    ok(new MoneyAmount(CurrencyCode.EUR,99).amount().equals(new BigDecimal("0.99")),"60-to-decimal");
    rejects(IllegalArgumentException.class,()->new MoneyAmount(CurrencyCode.USD,10).plus(new MoneyAmount(CurrencyCode.EUR,10)),"60-mixed-currencies");
    rejects(ArithmeticException.class,()->new MoneyAmount(CurrencyCode.USD,Long.MAX_VALUE).plus(new MoneyAmount(CurrencyCode.USD,1)),"60-add-overflow");
    rejects(ArithmeticException.class,()->Math.multiplyExact(Long.MAX_VALUE,2),"60-multiply-overflow");
    rejects(NullPointerException.class,()->new MoneyAmount(null,5),"60-null-currency");
    ok(Math.addExact(9L,3L)==12,"60-exact-add-ordinary");
  }
  private record AlertRecord(long score, String region) {}
  private static void item61() {
    Integer x = Integer.valueOf(7000), y = Integer.valueOf(7000);
    ok(x.equals(y),"61-equal-values");
    ok(Integer.compare(x,y)==0,"61-compare-values");
    ok(Integer.valueOf(-4).intValue()==-4,"61-negative-value");
    ok(Integer.compare(Integer.MIN_VALUE,Integer.MAX_VALUE)<0,"61-min-max");
    ok(Long.compare(Long.MAX_VALUE,Long.MIN_VALUE)>0,"61-long-compare-extremes");
    rejects(NullPointerException.class,()->{Integer nullable=null;int primitive=nullable;System.out.println(primitive);},"61-null-unbox");
    ok(OptionalInt.empty().isEmpty(),"61-optional-int-empty");
    ok(OptionalInt.of(0).isPresent(),"61-optional-int-zero-present");
    ok(OptionalInt.of(17).getAsInt()==17,"61-optional-int-present");
    ok(IntStream.of(1,2,3).sum()==6,"61-primitive-stream");
    ok(IntStream.empty().sum()==0,"61-primitive-stream-empty");
    ok(List.of(3,1,2).stream().sorted(Integer::compare).toList().equals(List.of(1,2,3)),"61-sorted-boxed-values");
    ok(Comparator.comparingLong(AlertRecord::score).compare(new AlertRecord(Long.MIN_VALUE,"a"),new AlertRecord(Long.MAX_VALUE,"b"))<0,"61-long-comparator-no-subtraction");
    ok(Boolean.TRUE.equals(true),"61-boolean-wrapper-value");
    ok(Long.valueOf(9L).longValue()==9L,"61-unbox-long");
  }
  private enum Status { ACCEPTED, REJECTED }
  private record EventKey(String tenantId, String eventId) {
    EventKey {if(tenantId==null||tenantId.isBlank()||eventId==null||eventId.isBlank())throw new IllegalArgumentException("blank key");}
  }
  private static Status parseStatus(String raw) {return Status.valueOf(Objects.requireNonNull(raw,"raw"));}
  private static void item62() {
    EventKey key=new EventKey("tenant:west","order:2");
    ok(key.tenantId().equals("tenant:west"),"62-delimiter-in-value");
    ok(key.eventId().equals("order:2"),"62-other-delimiter");
    ok(key.equals(new EventKey("tenant:west","order:2")),"62-record-value-equality");
    ok(!key.equals(new EventKey("tenant","west:order:2")),"62-no-joined-key-collision");
    ok(Set.of(key).contains(new EventKey("tenant:west","order:2")),"62-record-key-lookup");
    ok(parseStatus("ACCEPTED")==Status.ACCEPTED,"62-typed-enum");
    rejects(IllegalArgumentException.class,()->parseStatus("unknown"),"62-invalid-enum");
    rejects(NullPointerException.class,()->parseStatus(null),"62-null-status");
    rejects(IllegalArgumentException.class,()->new EventKey("", "e"),"62-empty-tenant");
    rejects(IllegalArgumentException.class,()->new EventKey("t", " "),"62-empty-event");
    ThreadLocal<String> one=new ThreadLocal<>(),two=new ThreadLocal<>();
    try {one.set("a");two.set("b");ok("a".equals(one.get()),"62-key-one");ok("b".equals(two.get()),"62-key-two");}
    finally {one.remove();two.remove();}
    ok(one.get()==null,"62-key-one-cleaned");
    ok(two.get()==null,"62-key-two-cleaned");
    Map<EventKey,Integer> byTypedKey=new HashMap<>();byTypedKey.put(key,7);
    ok(byTypedKey.get(new EventKey("tenant:west","order:2"))==7,"62-typed-map");
    ok(!byTypedKey.containsKey(new EventKey("tenant:west","order:3")),"62-distinct-key");
  }
  private static String quoteCsv(String value) {
    if(value.contains(",")||value.contains("\"")||value.contains("\n"))return "\""+value.replace("\"","\"\"")+"\"";
    return value;
  }
  private static String csv(List<String> rows) {
    StringBuilder out=new StringBuilder();
    for(int i=0;i<rows.size();i++) {if(i>0) out.append('\n');out.append(quoteCsv(rows.get(i)));}
    return out.toString();
  }
  private static void item63() {
    ok(csv(List.of()).isEmpty(),"63-empty-csv");
    ok(csv(List.of("a")).equals("a"),"63-single-row");
    ok(csv(List.of("a","b")).equals("a\nb"),"63-order-delimiter");
    ok(csv(List.of("a,b")).equals("\"a,b\""),"63-comma-quoted");
    ok(csv(List.of("a\"b")).equals("\"a\"\"b\""),"63-quote-escaped");
    ok(csv(List.of("line1\nline2")).equals("\"line1\nline2\""),"63-newline-quoted");
    List<String> many=Collections.nCopies(10_000,"X");
    String report=csv(many);
    ok(report.length()==19_999,"63-bounded-length");
    ok(report.startsWith("X\nX\n"),"63-start-order");
    ok(report.endsWith("X"),"63-end-order");
    StringBuilder builder=new StringBuilder(2);builder.append("a").append("b").append(123);
    ok(builder.toString().equals("ab123"),"63-append-works");
    ok(String.join("|",List.of("a","b","c")).equals("a|b|c"),"63-standard-join");
    ok("a".concat("b").equals("ab"),"63-small-expression");
    try {StringWriter w=new StringWriter();w.write("first");w.append('|');w.write("second");ok(w.toString().equals("first|second"),"63-stream-writer");}
    catch(Exception e){throw new AssertionError("63-writer",e);}
    StringBuilder mutable=new StringBuilder("old");String snapshot=mutable.toString();mutable.append("new");
    ok(snapshot.equals("old"),"63-string-snapshot-immutable");
  }
  private enum Region { EAST, WEST }
  private static String observe(Map<String,Integer> data) {return data.getOrDefault("ok",0)+":"+data.size();}
  private static void item64() {
    Map<String,Integer> hash=new HashMap<>(Map.of("ok",2,"warn",1));
    Map<String,Integer> ordered=new LinkedHashMap<>(Map.of("ok",2,"warn",1));
    ok(observe(hash).equals("2:2"),"64-hash-substitute");
    ok(observe(ordered).equals("2:2"),"64-linked-substitute");
    ok(hash.equals(ordered),"64-map-content-equality");
    LinkedHashSet<String> linked=new LinkedHashSet<>(List.of("b","a"));
    Set<String> plain=new HashSet<>(linked);
    ok(plain.equals(linked),"64-set-value-equality");
    ok(linked.iterator().next().equals("b"),"64-linked-order-contract");
    SequencedSet<String> sequenced=linked;
    ok(sequenced.getFirst().equals("b"),"64-sequenced-first");
    ok(sequenced.getLast().equals("a"),"64-sequenced-last");
    ok(sequenced.reversed().getFirst().equals("a"),"64-sequenced-reversed");
    SequencedMap<String,Integer> mapOrder=new LinkedHashMap<>();mapOrder.put("second",2);mapOrder.put("first",1);
    ok(mapOrder.firstEntry().getKey().equals("second"),"64-sequenced-map-first");
    ok(mapOrder.lastEntry().getKey().equals("first"),"64-sequenced-map-last");
    Map<Region,Integer> counts=new EnumMap<>(Region.class);counts.put(Region.WEST,7);
    ok(counts.get(Region.WEST)==7,"64-enummap-contract");
    ok(!counts.containsKey(Region.EAST),"64-enummap-absent");
    Map<String,String> immutable=Map.of("x","y");
    rejects(UnsupportedOperationException.class,()->immutable.put("z","w"),"64-mapof-unmodifiable");
    rejects(NullPointerException.class,()->Map.of("x",null),"64-mapof-null-rejected");
    List<String> vals=new ArrayList<>(List.of("a","b"));
    Collection<String> contract=vals;
    ok(contract.contains("b"),"64-collection-interface");
    ok(new HashSet<>(List.of("a","b")).containsAll(contract),"64-interface-set-matches");
    ok(new TreeSet<>(List.of("a","b")).first().equals("a"),"64-sortedset-extra-contract");
  }
  public static void main(String[] args) {
    item60();item61();item62();item63();item64();
    System.out.println("CodeFit original Java21 general-types assertions="+checks);
  }
}
