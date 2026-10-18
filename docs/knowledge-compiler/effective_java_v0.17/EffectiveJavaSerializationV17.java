// Original CodeFit Java 21 language-fact controls: EJ85–89. Do not use as learner grader.
// All native object input is an isolated, locally created trusted fixture; never external bytes.
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class EffectiveJavaSerializationV17 {
    private EffectiveJavaSerializationV17() {}
    private static int checks;
    private static void ok(boolean b,String id){if(!b)throw new AssertionError(id);checks++;}
    @FunctionalInterface private interface Throwing{void run()throws Exception;}
    private static void fails(Class<? extends Throwable> expected,Throwing run,String id){
        try{run.run();}catch(Throwable e){if(expected.isInstance(e)){checks++;return;}throw new AssertionError(id+" unexpected: "+e,e);}throw new AssertionError(id+" expected rejection");
    }
    private static byte[] trustedWrite(Object obj)throws Exception{
        try(ByteArrayOutputStream data=new ByteArrayOutputStream();ObjectOutputStream out=new ObjectOutputStream(data)){
            out.writeObject(obj);out.flush();return data.toByteArray();
        }
    }
    private static Object trustedRead(byte[] locallyCreated, ObjectInputFilter filter)throws Exception{
        // Caller is a test only; no API accepts external/untrusted data.
        try(ObjectInputStream stream=new ObjectInputStream(new ByteArrayInputStream(locallyCreated))){
            stream.setObjectInputFilter(filter);return stream.readObject();
        }
    }
    private record WireFrame(int version,String status,long minor){}
    private static WireFrame parseWire(byte[] bytes){
        Objects.requireNonNull(bytes);
        if(bytes.length>64)throw new IllegalArgumentException("oversize wire frame");
        String value=new String(bytes,StandardCharsets.US_ASCII);
        if(!StandardCharsets.US_ASCII.newEncoder().canEncode(value))throw new IllegalArgumentException("encoding");
        String[] parts=value.split("\\|",-1);
        if(parts.length!=3)throw new IllegalArgumentException("wrong frame shape");
        if(!parts[0].equals("v1"))throw new IllegalArgumentException("version");
        if(!Set.of("NEW","POSTED").contains(parts[1]))throw new IllegalArgumentException("status");
        if(!parts[2].matches("[0-9]{1,9}"))throw new IllegalArgumentException("digits");
        return new WireFrame(1,parts[1],Long.parseLong(parts[2]));
    }
    private static void item85()throws Exception{
        WireFrame frame=parseWire("v1|POSTED|120".getBytes(StandardCharsets.US_ASCII));
        ok(frame.version()==1,"85-version");ok(frame.status().equals("POSTED"),"85-status");ok(frame.minor()==120,"85-amount");
        ok(parseWire("v1|NEW|0".getBytes(StandardCharsets.US_ASCII)).minor()==0,"85-zero");
        fails(IllegalArgumentException.class,()->parseWire("v3|NEW|1".getBytes(StandardCharsets.US_ASCII)),"85-unknown-version");
        fails(IllegalArgumentException.class,()->parseWire("v1|HACK|1".getBytes(StandardCharsets.US_ASCII)),"85-unknown-state");
        fails(IllegalArgumentException.class,()->parseWire("v1|NEW|-1".getBytes(StandardCharsets.US_ASCII)),"85-no-negative");
        fails(IllegalArgumentException.class,()->parseWire("v1|NEW|1234567890".getBytes(StandardCharsets.US_ASCII)),"85-amount-bounded");
        fails(IllegalArgumentException.class,()->parseWire("v1|NEW|1|EXTRA".getBytes(StandardCharsets.US_ASCII)),"85-no-injected-field");
        fails(IllegalArgumentException.class,()->parseWire(new byte[65]),"85-oversize");
        fails(NullPointerException.class,()->parseWire(null),"85-null");
        // Fixed known test object, with a denying stream-local filter. No user payloads.
        byte[] known=trustedWrite(new VersionedAmount("USD",7));
        ObjectInputFilter rejectAll=info->ObjectInputFilter.Status.REJECTED;
        fails(InvalidClassException.class,()->trustedRead(known,rejectAll),"85-filter-rejected");
        ObjectInputFilter allowKnown=info->info.depth()>4||info.references()>8||info.streamBytes()>1000?ObjectInputFilter.Status.REJECTED:ObjectInputFilter.Status.UNDECIDED;
        ok(((VersionedAmount)trustedRead(known,allowKnown)).minor()==7,"85-bounded-trusted-input");
        ok(!Arrays.equals(known,"local-test".getBytes(StandardCharsets.US_ASCII)),"85-java-graph-not-text-wire");
    }
    private static final class VersionedAmount implements Serializable {
        @Serial private static final long serialVersionUID=17L;
        private final String currency;
        private final long minor;
        VersionedAmount(String c,long v){currency=Objects.requireNonNull(c);if(c.isBlank()||v<0)throw new IllegalArgumentException("bad amount");minor=v;}
        String currency(){return currency;}long minor(){return minor;}
        @Serial private void readObject(ObjectInputStream s)throws IOException,ClassNotFoundException{
            s.defaultReadObject();if(currency==null||currency.isBlank()||minor<0)throw new InvalidObjectException("bad trusted archival amount");
        }
    }
    private static void item86()throws Exception{
        VersionedAmount one=new VersionedAmount("USD",150);
        byte[] archive=trustedWrite(one);
        VersionedAmount two=(VersionedAmount)trustedRead(archive,null);
        ok(two.currency().equals("USD"),"86-currency");ok(two.minor()==150,"86-minor");
        ok(one!=two,"86-not-reference-identity");
        ok(archive.length>0,"86-nonempty-wire");
        Field uid=VersionedAmount.class.getDeclaredField("serialVersionUID");uid.setAccessible(true);ok(uid.getLong(null)==17L,"86-explicit-uid");
        fails(IllegalArgumentException.class,()->new VersionedAmount("",2),"86-invalid-currency");
        fails(IllegalArgumentException.class,()->new VersionedAmount("USD",-1),"86-negative");
        fails(NullPointerException.class,()->new VersionedAmount(null,2),"86-null");
        Field field=VersionedAmount.class.getDeclaredField("minor");field.setAccessible(true);
        VersionedAmount compromised=new VersionedAmount("USD",2);field.setLong(compromised,-5);
        byte[] intentionallyInvalid=trustedWrite(compromised);
        fails(InvalidObjectException.class,()->trustedRead(intentionallyInvalid,null),"86-read-validation");
        ok(!VersionedAmount.class.isEnum(),"86-not-enum");
    }
    private static final class CodeCollection implements Serializable {
        @Serial private static final long serialVersionUID=87L;
        private transient List<String> values;
        private transient Set<String> index;
        CodeCollection(List<String> codes){set(codes);}
        private void set(List<String> codes){
            Objects.requireNonNull(codes);if(codes.size()>8)throw new IllegalArgumentException("too many");
            if(codes.stream().anyMatch(s->s==null||!s.matches("[A-Z]{1,8}")))throw new IllegalArgumentException("invalid code");
            if(new HashSet<>(codes).size()!=codes.size())throw new IllegalArgumentException("duplicate");
            values=List.copyOf(codes);index=Set.copyOf(values);
        }
        List<String> values(){return values;}
        boolean contains(String code){return index.contains(code);}
        @Serial private void writeObject(ObjectOutputStream out)throws IOException{
            out.defaultWriteObject();out.writeInt(values.size());for(String c:values)out.writeUTF(c);
        }
        @Serial private void readObject(ObjectInputStream in)throws IOException,ClassNotFoundException{
            in.defaultReadObject();int count=in.readInt();if(count<0||count>8)throw new InvalidObjectException("code count outside limit");
            ArrayList<String> copy=new ArrayList<>(count);
            for(int i=0;i<count;i++)copy.add(in.readUTF());
            try{set(copy);}catch(IllegalArgumentException|NullPointerException e){InvalidObjectException bad=new InvalidObjectException("invalid code set");bad.initCause(e);throw bad;}
        }
    }
    private static void item87()throws Exception{
        CodeCollection original=new CodeCollection(List.of("A","BBB"));
        CodeCollection result=(CodeCollection)trustedRead(trustedWrite(original),null);
        ok(result.values().equals(List.of("A","BBB")),"87-logical-values");
        ok(result.contains("A"),"87-derived-cache-rebuilt");
        ok(!result.contains("X"),"87-missing");
        ok(result.index!=original.index,"87-not-sharing-cache");
        ok(result.values()!=original.values(),"87-new-list-instance");
        ok(result.values().size()==2,"87-length");
        fails(UnsupportedOperationException.class,()->result.values().add("X"),"87-immutable");
        ok(((CodeCollection)trustedRead(trustedWrite(new CodeCollection(List.of())),null)).values().isEmpty(),"87-empty");
        ok(((CodeCollection)trustedRead(trustedWrite(new CodeCollection(List.of("ONE"))),null)).contains("ONE"),"87-single");
        fails(IllegalArgumentException.class,()->new CodeCollection(List.of("A","A")),"87-duplicate");
        fails(IllegalArgumentException.class,()->new CodeCollection(Collections.nCopies(9,"A")),"87-too-many");
        fails(IllegalArgumentException.class,()->new CodeCollection(List.of("invalid")),"87-wrong-case");
        Field values=CodeCollection.class.getDeclaredField("values");values.setAccessible(true);
        CodeCollection bad=new CodeCollection(List.of("OK"));values.set(bad,List.of("A","A"));
        fails(InvalidObjectException.class,()->trustedRead(trustedWrite(bad),null),"87-invalid-trusted-fixture");
    }
    private static final class DateWindow implements Serializable {
        @Serial private static final long serialVersionUID=88L;
        private Date start,end;
        DateWindow(Date s,Date e){assign(s,e);}
        private void assign(Date s,Date e){
            Date left=new Date(Objects.requireNonNull(s).getTime());Date right=new Date(Objects.requireNonNull(e).getTime());
            if(left.after(right))throw new IllegalArgumentException("inverted interval");start=left;end=right;
        }
        Date start(){return new Date(start.getTime());}
        Date end(){return new Date(end.getTime());}
        @Serial private void readObject(ObjectInputStream in)throws IOException,ClassNotFoundException{
            in.defaultReadObject();
            try{assign(start,end);}catch(IllegalArgumentException|NullPointerException err){InvalidObjectException broken=new InvalidObjectException("invalid date window");broken.initCause(err);throw broken;}
        }
    }
    private static void item88()throws Exception{
        Date left=new Date(1000),right=new Date(3000);DateWindow w=new DateWindow(left,right);
        left.setTime(10_000);right.setTime(-10_000);
        ok(w.start().getTime()==1000,"88-constructor-copy-left");ok(w.end().getTime()==3000,"88-constructor-copy-right");
        w.start().setTime(9000);w.end().setTime(-9000);
        ok(w.start().getTime()==1000,"88-accessor-left");ok(w.end().getTime()==3000,"88-accessor-right");
        DateWindow decoded=(DateWindow)trustedRead(trustedWrite(w),null);
        ok(decoded.start().getTime()==1000,"88-decoded-left");ok(decoded.end().getTime()==3000,"88-decoded-right");
        ok(decoded.start()!=w.start(),"88-not-shared-reference");
        decoded.start().setTime(8000);ok(decoded.start().getTime()==1000,"88-postdecode-alias-safe");
        fails(IllegalArgumentException.class,()->new DateWindow(new Date(5),new Date(4)),"88-constructor-invariant");
        fails(NullPointerException.class,()->new DateWindow(null,new Date(1)),"88-null-left");
        fails(NullPointerException.class,()->new DateWindow(new Date(1),null),"88-null-right");
        Field s=DateWindow.class.getDeclaredField("start");Field e=DateWindow.class.getDeclaredField("end");s.setAccessible(true);e.setAccessible(true);
        DateWindow forged=new DateWindow(new Date(1),new Date(2));s.set(forged,new Date(3));e.set(forged,new Date(2));
        fails(InvalidObjectException.class,()->trustedRead(trustedWrite(forged),null),"88-reject-violated-invariant");
    }
    private enum EnumPolicy { INSTANCE; String decide(){return "approved";} }
    private static final class LegacySingleton implements Serializable {
        @Serial private static final long serialVersionUID=89L;
        static final LegacySingleton INSTANCE=new LegacySingleton();
        private LegacySingleton(){}
        String kind(){return "legacy";}
    }
    private static final class ResolvedLegacy implements Serializable {
        @Serial private static final long serialVersionUID=891L;
        static final ResolvedLegacy INSTANCE=new ResolvedLegacy();
        private ResolvedLegacy(){}
        @Serial private Object readResolve(){return INSTANCE;}
    }
    private static void item89()throws Exception{
        EnumPolicy original=EnumPolicy.INSTANCE;
        EnumPolicy decoded=(EnumPolicy)trustedRead(trustedWrite(original),null);
        ok(original==decoded,"89-enum-same-identity");
        ok(original.equals(decoded),"89-enum-equal");
        ok(decoded.decide().equals("approved"),"89-policy-functional");
        ok(EnumPolicy.values().length==1,"89-single-constant");
        ok(Enum.valueOf(EnumPolicy.class,"INSTANCE")==original,"89-stable-name");
        fails(IllegalArgumentException.class,()->Enum.valueOf(EnumPolicy.class,"UNKNOWN"),"89-unknown-constant");
        ok(original.name().equals("INSTANCE"),"89-serialized-name");
        LegacySingleton legacy=LegacySingleton.INSTANCE;
        LegacySingleton rebuilt=(LegacySingleton)trustedRead(trustedWrite(legacy),null);
        ok(legacy!=rebuilt,"89-legacy-identity-lost");
        ok(legacy.getClass()==rebuilt.getClass(),"89-class-same-not-object");
        ok(rebuilt.kind().equals("legacy"),"89-legacy-behavior");
        ResolvedLegacy resolved=(ResolvedLegacy)trustedRead(trustedWrite(ResolvedLegacy.INSTANCE),null);
        ok(resolved==ResolvedLegacy.INSTANCE,"89-readresolve-replacement");
        ok(!EnumPolicy.class.isAssignableFrom(LegacySingleton.class),"89-different-contract");
        ok(EnumPolicy.INSTANCE.ordinal()==0,"89-one-index");
    }
    public static void main(String[] ignored)throws Exception{
        item85();item86();item87();item88();item89();
        System.out.println("CodeFit original Java21 serialization assertions="+checks);
    }
}
