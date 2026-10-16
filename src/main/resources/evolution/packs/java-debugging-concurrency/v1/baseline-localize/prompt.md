# A batch assertion points at the caller

An alert says expected=3 actual=2. The failing assertion is at ReportTest:42; ReportService:18 calls accepted(); BatchCounter:9 initializes int accepted=0, then runs for(int i=1;i<rows.length;i++) if(rows[i]>0) accepted++. Input rows=[7,-1,4,5]. Identify the first faulty executable boundary, the minimal failing input, and the expected result using the shape location=<Class:line>;input=[<values>];expected=<n>. Use 1 as the smallest positive test value. Explain separately why the assertion line is a symptom, not the faulty iteration boundary.

Deliver a reproduction, diagnosis, correction and verification notes. Record assistance honestly. Public checks are examples, not complete safety proofs. No score or evidence is created by opening or manually completing this task.
