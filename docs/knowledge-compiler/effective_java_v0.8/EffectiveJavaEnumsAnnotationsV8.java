// Original CodeFit educational language-behavior fixtures, not publisher examples.
// Java 21 only. These are smoke facts, NOT learner-submission graders.
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.*;

public final class EffectiveJavaEnumsAnnotationsV8 {
  private static int passed;
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    passed++;
  }
  private static void rejects(Class<? extends Throwable> type, Runnable action, String message) {
    try { action.run(); }
    catch (Throwable t) {
      if (!type.isInstance(t)) throw new AssertionError(message + ": wrong exception " + t);
      passed++; return;
    }
    throw new AssertionError(message + ": exception missing");
  }

  // Item 34: typed choices, explicit external codes, constant-specific validation.
  private enum SettlementAction {
    AUTHORIZE("A", "Authorize") { @Override boolean accepts(int cents) { return cents > 0; } },
    CAPTURE("C", "Capture") { @Override boolean accepts(int cents) { return cents >= 100; } },
    REVERSE("R", "Reverse") { @Override boolean accepts(int cents) { return cents == 0; } };
    final String externalCode; final String label;
    SettlementAction(String code, String label) { this.externalCode=code; this.label=label; }
    abstract boolean accepts(int cents);
    @Override public String toString() { return label; }
    static Optional<SettlementAction> byCode(String code) {
      return Arrays.stream(values()).filter(x -> x.externalCode.equals(code)).findFirst();
    }
  }
  private enum OtherAction { CAPTURE }
  private static void item34() {
    check(SettlementAction.values().length==3,"enum count");
    check(SettlementAction.CAPTURE.accepts(100),"constant specific valid");
    check(!SettlementAction.CAPTURE.accepts(99),"constant specific invalid");
    check(SettlementAction.REVERSE.accepts(0),"reverse zero");
    check(!SettlementAction.AUTHORIZE.accepts(0),"authorize positive");
    check(SettlementAction.CAPTURE.name().equals("CAPTURE"),"declared name");
    check(SettlementAction.CAPTURE.toString().equals("Capture"),"custom label");
    check(SettlementAction.valueOf("CAPTURE")==SettlementAction.CAPTURE,"valueOf uses name");
    rejects(IllegalArgumentException.class,()->SettlementAction.valueOf("Capture"),"display label not enum name");
    check(SettlementAction.byCode("R").orElseThrow()==SettlementAction.REVERSE,"code lookup");
    check(SettlementAction.byCode("unknown").isEmpty(),"unknown code");
    check(!SettlementAction.class.equals(OtherAction.class),"same constant name different enum type");
    int severity=switch(SettlementAction.REVERSE) {
      case AUTHORIZE -> 1; case CAPTURE -> 2; case REVERSE -> 3;
    };
    check(severity==3,"Java21 exhaustive switch expression");
  }

  // Item 35: external codes and domain scores independent of ordinal.
  private enum RiskDecision {
    REVIEW(40, 5), APPROVE(10, 0), LEGACY_REVIEW(41, 5), DECLINE(90, 10);
    final int code; final int score;
    RiskDecision(int code,int score){this.code=code;this.score=score;}
    static Optional<RiskDecision> fromCode(int code) {
      return Arrays.stream(values()).filter(x->x.code==code).findFirst();
    }
  }
  private static void item35() {
    check(RiskDecision.APPROVE.code==10,"explicit sparse code");
    check(RiskDecision.APPROVE.ordinal()!=RiskDecision.APPROVE.code,"ordinal not code");
    check(RiskDecision.REVIEW.score==RiskDecision.LEGACY_REVIEW.score,"shared domain score");
    check(RiskDecision.REVIEW.code!=RiskDecision.LEGACY_REVIEW.code,"different external codes");
    check(RiskDecision.fromCode(90).orElseThrow()==RiskDecision.DECLINE,"lookup code");
    check(RiskDecision.fromCode(0).isEmpty(),"unknown code");
    check(RiskDecision.fromCode(11).isEmpty(),"sparse hole");
    check(RiskDecision.REVIEW.ordinal()==0,"declaration position is not score");
  }

  // Item 36: typed sets, snapshot boundaries and >64 flags.
  private enum Permission { VIEW, REFUND, EXPORT, APPROVE }
  private enum Jumbo { F00,F01,F02,F03,F04,F05,F06,F07,F08,F09,F10,F11,F12,F13,F14,F15,
    F16,F17,F18,F19,F20,F21,F22,F23,F24,F25,F26,F27,F28,F29,F30,F31,
    F32,F33,F34,F35,F36,F37,F38,F39,F40,F41,F42,F43,F44,F45,F46,F47,
    F48,F49,F50,F51,F52,F53,F54,F55,F56,F57,F58,F59,F60,F61,F62,F63,F64 }
  private static Set<Permission> snapshot(Set<Permission> input) {
    return Collections.unmodifiableSet(EnumSet.copyOf(input.isEmpty() ? EnumSet.noneOf(Permission.class) : input));
  }
  private static void item36() {
    EnumSet<Permission> a=EnumSet.of(Permission.VIEW,Permission.EXPORT);
    EnumSet<Permission> b=EnumSet.of(Permission.EXPORT,Permission.REFUND);
    EnumSet<Permission> union=a.clone();union.addAll(b);
    check(union.size()==3,"union size");
    check(union.contains(Permission.REFUND),"union member");
    EnumSet<Permission> intersection=a.clone();intersection.retainAll(b);
    check(intersection.equals(EnumSet.of(Permission.EXPORT)),"intersection");
    EnumSet<Permission> none=EnumSet.noneOf(Permission.class);
    check(none.isEmpty(),"empty enum set");
    check(EnumSet.complementOf(none).size()==4,"complement");
    Set<Permission> live=Collections.unmodifiableSet(a);
    Set<Permission> stable=snapshot(a);
    a.add(Permission.APPROVE);
    check(live.contains(Permission.APPROVE),"wrapper is live view");
    check(!stable.contains(Permission.APPROVE),"snapshot isolates later changes");
    rejects(UnsupportedOperationException.class,()->stable.add(Permission.REFUND),"snapshot immutable");
    check(snapshot(Set.of()).isEmpty(),"empty snapshot");
    EnumSet<Jumbo> jumbo=EnumSet.allOf(Jumbo.class);
    check(jumbo.size()==65,"jumbo enum set");
    check(jumbo.contains(Jumbo.F64),"65th flag");
    check(Set.copyOf(a).size()==3,"Java21 immutable Set copy");
  }

  // Item 37: enum-keyed grouping and sparse typed transitions.
  private enum Status { CREATED, PAID, REJECTED }
  private record Entry(Status status,String id) {}
  private static EnumMap<Status,List<String>> allGroups(Collection<Entry> entries) {
    EnumMap<Status,List<String>> groups=new EnumMap<>(Status.class);
    for (Status status:Status.values()) groups.put(status,new ArrayList<>());
    for (Entry e:entries) groups.get(e.status()).add(e.id());
    return groups;
  }
  private static void item37() {
    var groups=allGroups(List.of(new Entry(Status.PAID,"a"),new Entry(Status.PAID,"b")));
    check(groups.size()==3,"prefill all keys");
    check(groups.get(Status.CREATED).isEmpty(),"unobserved group empty");
    check(groups.get(Status.PAID).equals(List.of("a","b")),"grouped order");
    check(new ArrayList<>(groups.keySet()).equals(List.of(Status.CREATED,Status.PAID,Status.REJECTED)),"enum declaration iteration order");
    Map<Status,List<Entry>> streamGroups=List.of(new Entry(Status.PAID,"x")).stream()
      .collect(Collectors.groupingBy(Entry::status,()->new EnumMap<>(Status.class),Collectors.toList()));
    check(streamGroups instanceof EnumMap,"explicit groupingBy factory");
    check(streamGroups.size()==1,"groupingBy does not prefill");
    check(!streamGroups.containsKey(Status.CREATED),"missing group absent");
    Map<Status,List<Entry>> empty=Stream.<Entry>empty()
      .collect(Collectors.groupingBy(Entry::status,()->new EnumMap<>(Status.class),Collectors.toList()));
    check(empty.isEmpty(),"empty stream no groups");
    EnumMap<Status,EnumMap<Status,String>> transitions=new EnumMap<>(Status.class);
    EnumMap<Status,String> created=new EnumMap<>(Status.class);
    created.put(Status.PAID,"capture");created.put(Status.REJECTED,"decline");
    transitions.put(Status.CREATED,created);
    check("capture".equals(transitions.get(Status.CREATED).get(Status.PAID)),"typed transition");
    check(!transitions.containsKey(Status.PAID),"missing outer key");
    check(Optional.ofNullable(transitions.get(Status.PAID)).map(m->m.get(Status.CREATED)).isEmpty(),"safe missing transition");
    rejects(NullPointerException.class,()->groups.put(null,new ArrayList<>()),"null enum key rejected");
    check(groups.get(Status.REJECTED)!=null,"preinitialized missing status");
  }

  // Item 38: independent enums sharing an extensible operation interface.
  private interface ValidationRule { boolean permits(int score); }
  private enum BuiltInRule implements ValidationRule {
    NONNEGATIVE { public boolean permits(int score){return score>=0;} },
    SMALL { public boolean permits(int score){return score<100;} }
  }
  private enum PartnerRule implements ValidationRule {
    EVEN { public boolean permits(int score){return score%2==0;} },
    NONZERO { public boolean permits(int score){return score!=0;} }
  }
  private static <E extends Enum<E> & ValidationRule> int acceptedByEnum(Class<E> type,int score) {
    int count=0;for (E rule:type.getEnumConstants())if(rule.permits(score))count++;return count;
  }
  private static int acceptedByCollection(Collection<? extends ValidationRule> rules,int score) {
    int count=0;for(ValidationRule rule:rules)if(rule.permits(score))count++;return count;
  }
  private static void item38() {
    check(BuiltInRule.NONNEGATIVE.permits(0),"built-in rule");
    check(!BuiltInRule.SMALL.permits(101),"built-in rejects");
    check(PartnerRule.EVEN.permits(8),"partner enum rule");
    check(!PartnerRule.NONZERO.permits(0),"partner rule rejects");
    check(acceptedByEnum(BuiltInRule.class,12)==2,"bounded class token builtin");
    check(acceptedByEnum(PartnerRule.class,12)==2,"bounded class token partner");
    List<ValidationRule> mixed=List.of(BuiltInRule.SMALL,PartnerRule.EVEN,PartnerRule.NONZERO);
    check(acceptedByCollection(mixed,12)==3,"mixed enum families through interface");
    check(acceptedByCollection(mixed,101)==1,"mixed rules filter");
    ValidationRule custom=score->score==42;
    check(custom.permits(42),"non-enum implementation allowed");
    check(acceptedByCollection(List.of(custom,PartnerRule.EVEN),42)==2,"mixed enum and class");
  }

  // Item 39: repeatable annotations, container visibility, runtime retention.
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Repeatable(AuditCases.class)
  private @interface AuditCase { String value(); }
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  private @interface AuditCases { AuditCase[] value(); }
  @Retention(RetentionPolicy.CLASS)
  @Target(ElementType.METHOD)
  private @interface CompileOnlyTag {}
  private static final class AuditChecks {
    @AuditCase("negative") @AuditCase("zero") public static void repeated() {}
    @AuditCase("positive") public static void single() {}
    @CompileOnlyTag public static void classRetained() {}
    public static void unmarked() {}
    @AuditCase("invalid-static-contract") public void invalid() {}
  }
  private static void item39() throws ReflectiveOperationException {
    Method repeated=AuditChecks.class.getDeclaredMethod("repeated");
    Method single=AuditChecks.class.getDeclaredMethod("single");
    Method absent=AuditChecks.class.getDeclaredMethod("unmarked");
    Method invalid=AuditChecks.class.getDeclaredMethod("invalid");
    check(repeated.getAnnotationsByType(AuditCase.class).length==2,"repeatable retrieval");
    check(!repeated.isAnnotationPresent(AuditCase.class),"repeated annotations stored via container");
    check(repeated.isAnnotationPresent(AuditCases.class),"container visible");
    check(single.getAnnotationsByType(AuditCase.class).length==1,"single retrieval");
    check(single.isAnnotationPresent(AuditCase.class),"single direct visible");
    check(!single.isAnnotationPresent(AuditCases.class),"single not container");
    check(absent.getAnnotationsByType(AuditCase.class).length==0,"unmarked ignored");
    check(AuditChecks.class.getDeclaredMethod("classRetained").getAnnotation(CompileOnlyTag.class)==null,"CLASS not runtime visible");
    check(!Modifier.isStatic(invalid.getModifiers()),"method target does not enforce static");
    check(invalid.getAnnotationsByType(AuditCase.class).length==1,"invalid contract still annotated");
    check(Arrays.stream(repeated.getAnnotationsByType(AuditCase.class)).map(AuditCase::value)
      .collect(Collectors.toSet()).equals(Set.of("negative","zero")),"typed annotation payloads");
  }

  // Item 40: accidental overload versus actual Object equality override.
  private static final class LegacyLedgerKey {
    final String id;LegacyLedgerKey(String id){this.id=id;}
    public boolean equals(LegacyLedgerKey other){return id.equals(other.id);} // deliberately NOT an override
    @Override public int hashCode(){return id.hashCode();}
  }
  private static final class LedgerKey {
    final String id;LedgerKey(String id){this.id=id;}
    @Override public boolean equals(Object o){return o instanceof LedgerKey k && id.equals(k.id);}
    @Override public int hashCode(){return id.hashCode();}
  }
  private interface Retryable { default String retryPolicy(){return "none";} }
  private static final class Retrier implements Retryable {
    @Override public String retryPolicy(){return "bounded";}
  }
  private static void item40() {
    LegacyLedgerKey x=new LegacyLedgerKey("evt"),y=new LegacyLedgerKey("evt");
    check(x.equals(y),"overloaded typed call looks correct");
    check(!((Object)x).equals(y),"Object dispatch still identity");
    check(new HashSet<>(List.of(x,y)).size()==2,"overload fails Set dedup");
    LedgerKey a=new LedgerKey("evt"),b=new LedgerKey("evt"),c=new LedgerKey("other");
    check(a.equals(b),"actual override equality");
    check(a.hashCode()==b.hashCode(),"hash consistency");
    check(!a.equals(c),"distinct key");
    check(!a.equals(null),"null not equal");
    check(new HashSet<>(List.of(a,b)).size()==1,"Set deduplicates");
    check("hit".equals(Map.of(a,"hit").get(b)),"Map lookup");
    check(new Retrier().retryPolicy().equals("bounded"),"interface default override");
  }

  // Item 41: compile-time marker type versus runtime marker metadata.
  private interface Command { String id(); }
  private interface AuthorizedCommand extends Command {}
  private static final class Approved implements AuthorizedCommand {
    public String id(){return "ok";}
  }
  private static final class Plain implements Command {
    public String id(){return "plain";}
  }
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  private @interface AuditEndpoint {}
  @AuditEndpoint
  private static String submit(AuthorizedCommand cmd){return cmd.id();}
  private static void item41() throws ReflectiveOperationException {
    check(submit(new Approved()).equals("ok"),"typed marker boundary");
    check(new Approved() instanceof Command,"marker extends base type");
    Command plain=new Plain();
    check(!(plain instanceof AuthorizedCommand),"ordinary command not marker");
    check(EffectiveJavaEnumsAnnotationsV8.class.getDeclaredMethod("submit",AuthorizedCommand.class)
      .isAnnotationPresent(AuditEndpoint.class),"method annotation discoverable");
    check(AuthorizedCommand.class.getDeclaredMethods().length==0,"marker adds no methods");
    check(Approved.class.getInterfaces()[0]==AuthorizedCommand.class,"explicit implementation");
  }

  public static void main(String[] args) throws Exception {
    item34();item35();item36();item37();item38();item39();item40();item41();
    System.out.println("EffectiveJavaEnumsAnnotationsV8: "+passed+" Java 21 runtime checks PASS");
  }
}
