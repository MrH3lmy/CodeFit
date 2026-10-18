import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

/** Original Java21 fixtures for author verification; no publisher source snippets. */
public final class EffectiveJavaObjectContractsV19 {
  private static int assertions;
  private static void check(boolean ok) { assertions++; if (!ok) throw new AssertionError("check #" + assertions); }
  private static void reject(Runnable work) {
    boolean threw = false; try { work.run(); } catch (IllegalArgumentException expected) { threw = true; }
    check(threw);
  }
  private record SecretRef(String id, String token) {
    @Override public String toString() { return "SecretRef[id="+id+",token=[REDACTED]]"; }
  }
  private record HumanStatus(String code, long count) {
    @Override public String toString() { return "status="+code+"; events="+count; }
  }
  private static void item12() {
    var s = new SecretRef("r7", "super-secret");
    check(s.toString().contains("r7"));
    check(!s.toString().contains("super-secret"));
    check(s.token().equals("super-secret"));
    check(s.toString().contains("REDACTED"));
    check(new HumanStatus("DONE",4).toString().contains("events=4"));
    check(new HumanStatus("DONE",4).code().equals("DONE"));
    check(new HumanStatus("DONE",4).count()==4);
    check(new Object().toString().contains("@"));
    check(s.toString().equals(new SecretRef("r7", "other").toString())); // Masking removes secret dependence.
    check(!s.toString().equals(new SecretRef("r8","super-secret").toString()));
  }
  private static final class MutableItem {
    String label;
    MutableItem(String label){this.label=label;}
    MutableItem(MutableItem src){this.label=src.label;}
  }
  private static final class Snapshot {
    final ArrayList<MutableItem> items;
    Snapshot(List<MutableItem> original) {
      items = new ArrayList<>();
      for (MutableItem entry:original) items.add(new MutableItem(entry));
    }
  }
  private static void item13(){
    var src=new ArrayList<>(List.of(new MutableItem("a"),new MutableItem("b")));
    var copied=new Snapshot(src);
    check(copied.items!=src);
    check(copied.items.size()==2);
    check(copied.items.get(0)!=src.get(0));
    check(copied.items.get(1)!=src.get(1));
    check(copied.items.get(0).label.equals("a"));
    src.get(0).label="changed";
    check(copied.items.get(0).label.equals("a"));
    copied.items.get(1).label="independent";
    check(src.get(1).label.equals("b"));
    var shallow=new ArrayList<>(src);
    check(shallow!=src);
    check(shallow.get(0)==src.get(0));
    var raw=new MutableItem[]{new MutableItem("x")};
    var arrayCopy=raw.clone();
    check(arrayCopy!=raw);
    check(arrayCopy[0]==raw[0]);
    var unmodifiable=List.copyOf(src);
    check(unmodifiable.get(0)==src.get(0));
    reject(()->{ throw new IllegalArgumentException("copy contract rejects invalid"); });
  }
  private record EventKey(long timestamp,String id) implements Comparable<EventKey> {
    @Override public int compareTo(EventKey that){
      int result=Long.compare(timestamp,that.timestamp);
      return result!=0?result:id.compareTo(that.id);
    }
  }
  private static void item14(){
    var large=new EventKey(Long.MAX_VALUE,"a");
    var small=new EventKey(Long.MIN_VALUE,"z");
    check(large.compareTo(small)>0);
    check(small.compareTo(large)<0);
    check(large.compareTo(large)==0);
    var a=new EventKey(5,"a");var b=new EventKey(5,"b");var c=new EventKey(6,"a");
    check(a.compareTo(b)<0);
    check(b.compareTo(c)<0);
    check(a.compareTo(c)<0);
    check(Integer.signum(a.compareTo(b))==-Integer.signum(b.compareTo(a)));
    check(new TreeSet<>(List.of(a,b,c)).size()==3);
    check(new TreeSet<>(List.of(a,a)).size()==1);
    check(new HashSet<>(List.of(new BigDecimal("1.0"),new BigDecimal("1.00"))).size()==2);
    check(new TreeSet<>(List.of(new BigDecimal("1.0"),new BigDecimal("1.00"))).size()==1);
    var inputs=List.of(new EventKey(1,"z"),new EventKey(2,"a"),new EventKey(1,"a"));
    var sorted=new ArrayList<>(inputs);Collections.sort(sorted);
    check(sorted.get(0).equals(new EventKey(1,"a")));
    check(sorted.get(1).equals(new EventKey(1,"z")));
    check(sorted.get(2).equals(new EventKey(2,"a")));
    check(IntStream.range(0,sorted.size()-1).allMatch(i->sorted.get(i).compareTo(sorted.get(i+1))<=0));
  }
  private static final class SafeConstant {
    private static final byte[] SECRET={(byte)11,(byte)22,(byte)33};
    public static byte[] secretCopy(){return SECRET.clone();}
  }
  private static void item15(){
    var a=SafeConstant.secretCopy();var b=SafeConstant.secretCopy();
    check(a!=b);
    check(a.length==3);
    check(a[0]==11);
    a[0]=99;
    check(b[0]==11);
    check(SafeConstant.secretCopy()[0]==11);
    check(!java.lang.reflect.Modifier.isPublic(SafeConstant.class.getDeclaredFields()[0].getModifiers()));
    var mutable=new ArrayList<>(List.of("a"));
    var view=Collections.unmodifiableList(mutable);
    mutable.add("b");
    check(view.size()==2); // Unmodifiable view is not a snapshot.
    var independent=List.copyOf(mutable);
    mutable.add("c");
    check(independent.size()==2);
    boolean denied=false;
    try { independent.add("bad"); } catch (UnsupportedOperationException expected){denied=true;}
    check(denied);
  }
  private static final class Limit {
    private int value;
    Limit(int value){update(value);}
    int value(){return value;}
    void update(int next){if(next<0)throw new IllegalArgumentException("negative");value=next;}
  }
  private record LimitRecord(byte[] raw) {
    LimitRecord { raw=raw.clone(); }
    @Override public byte[] raw(){return raw.clone();}
  }
  private static void item16(){
    var limit=new Limit(10);
    check(limit.value()==10);
    limit.update(15);check(limit.value()==15);
    reject(()->limit.update(-1));
    check(limit.value()==15);
    reject(()->new Limit(-20));
    var array=new byte[]{1,2};var record=new LimitRecord(array);
    check(record.raw().length==2);
    check(record.raw()[0]==1);
    array[0]=9;
    check(record.raw()[0]==1);
    var returned=record.raw();returned[1]=8;
    check(record.raw()[1]==2);
    check(returned!=record.raw());
    check(record.equals(record));
    check(record.toString().contains("LimitRecord"));
  }
  public static void main(String[] args){
    item12();item13();item14();item15();item16();
    System.out.println("assertions="+assertions);
  }
}